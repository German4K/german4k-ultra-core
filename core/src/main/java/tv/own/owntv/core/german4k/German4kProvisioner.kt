package tv.own.owntv.core.german4k

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import tv.own.owntv.core.CoreBuildInfo
import tv.own.owntv.core.R
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType
import tv.own.owntv.core.network.ConnectivityObserver
import tv.own.owntv.core.repository.EpgRepository
import tv.own.owntv.core.repository.SourceRepository
import tv.own.owntv.core.epg.EpgSourceStore
import tv.own.owntv.core.settings.PlaylistAutoRefresh
import tv.own.owntv.core.settings.PlaylistRefresh
import tv.own.owntv.core.settings.SettingsRepository
import java.io.File
import tv.own.owntv.core.setup.SourceImporter
import tv.own.owntv.core.sync.SyncScopeChoice

private val Context.german4kStore: DataStore<Preferences> by preferencesDataStore(name = "german4k_panel")

/**
 * Zero-setup provisioning for German4K Ultra: ask the German4K panel for this device, then make the
 * app's sources match what the panel says — create the profile and import on first run, update
 * credentials / add missing hosts / drop withdrawn ones on later runs. The customer never types a
 * server address; pairing happens on the panel side (/mac in the shop bot, the website QR page, or
 * username + password entered here via [login]).
 *
 * Runs in its own scope so a screen can leave while an import finishes; [state] tells the UI what to show.
 */
class German4kProvisioner(
    private val context: Context,
    private val panel: German4kPanelClient,
    private val sourceDao: SourceDao,
    private val profileDao: ProfileDao,
    private val sourceRepository: SourceRepository,
    private val settings: SettingsRepository,
    private val connectivity: ConnectivityObserver,
    private val epgRepository: EpgRepository,
    private val epgStore: EpgSourceStore,
    private val newImporter: () -> SourceImporter,
) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        /** An import is running; watch [importer] for stage/progress. */
        data class Importing(val answer: German4kPanelAnswer, val importer: SourceImporter) : State
        /** Device known to the panel but not paired to a line — show the three ways to pair.
         *  [loginFailed]: this answer came back from [login] with wrong credentials; show the panel note. */
        data class Uncoupled(val answer: German4kPanelAnswer, val loginFailed: Boolean = false) : State
        /** Panel says no (device locked, IP throttled, access expired): show the note, nothing to play. */
        data class Locked(val answer: German4kPanelAnswer) : State
        /** Sources match the panel; [profileId] is the active profile (set on first run by the import). */
        data class Ready(val answer: German4kPanelAnswer, val fromCache: Boolean, val profileId: Long?) : State
        /** No network and nothing cached. */
        data object Offline : State
        data class Failed(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    /** The latest panel answer (cached one first, then live) — hint, expiry, update and features for the UI. */
    private val _answer = MutableStateFlow<German4kPanelAnswer?>(null)
    val answer: StateFlow<German4kPanelAnswer?> = _answer.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** Contact the panel and reconcile sources. Safe to call on every app start; overlapping calls coalesce. */
    fun provision() { start(null, null) }

    /** Way 1: the customer typed username + password on the TV. The panel checks them and pairs the device. */
    fun login(username: String, password: String) { start(username, password) }

    fun reset() { _state.value = State.Idle }

    private var lastRunAt = 0L

    private fun start(username: String?, password: String?) {
        if (job?.isActive == true && username == null) return
        // Profile switch / gate re-entry fires provision() again right after the first-run import: skip
        // a second panel call when the last one is fresh (a login always goes through).
        val now = android.os.SystemClock.elapsedRealtime()
        if (username == null && now - lastRunAt < 30_000L && _state.value !is State.Idle) return
        lastRunAt = now
        job?.cancel()
        job = scope.launch { run(username, password) }
    }

    private suspend fun run(username: String?, password: String?) {
        _state.value = State.Checking
        val deviceId = German4kDeviceId.get(context)
        if (deviceId.isEmpty()) { _state.value = State.Failed(context.getString(R.string.g4k_device_id_unreadable)); return }
        val version = CoreBuildInfo.versionName

        // Hosts from the last answer are valid before the network is: a start without connectivity to the
        // primary host must already know the alternative.
        cached()?.let { registerHosts(it); German4kFeatures.set(it.features); if (_answer.value == null) _answer.value = it }
        var fromCache = false
        val answer: German4kPanelAnswer = try {
            panel.fetch(deviceId, version, username, password).also {
                cache(it); registerHosts(it); _answer.value = it
                German4kUpdateSource.offer(it.update); German4kFeatures.set(it.features)
                pendingTestNote?.let(::debugTestNote)
                pendingTestStunden?.let(::debugTestzugang)
                // Absturz vom letzten Mal und, wenn das Panel darum bittet, das Fehlerprotokoll.
                German4kDiagnose.nachPanelAntwort(context, panel, deviceId, it)
            }
        } catch (e: Exception) {
            Log.w(TAG, "panel unreachable: ${e.message}")
            val cached = cached()
            if (cached == null || cached.sources.isEmpty()) { _state.value = State.Offline; return }
            fromCache = true
            cached
        }

        when {
            answer.locked -> { _state.value = State.Locked(answer); return }
            answer.sources.isEmpty() -> {
                // Still reconcile: a line that was withdrawn must disappear from the device.
                runCatching { dropStale(emptySet()) }
                _state.value = State.Uncoupled(answer, loginFailed = username != null); return
            }
        }

        try {
            val fresh = reconcile(answer)
            val pid = settings.activeProfileId.first().takeIf { it >= 0L }
            _state.value = State.Ready(answer, fromCache, pid)
            // Guide for the default host, once — like OwnTV's semi-auto EPG after the wizard, but without
            // asking. Runs after Ready so the customer is already in the shell while it downloads.
            fresh?.let { syncGuide(it) }
        } catch (e: Exception) {
            Log.e(TAG, "reconcile failed", e)
            _state.value = State.Failed(e.message ?: context.getString(R.string.g4k_failed_title))
        }
    }

    /** Make the DB match [answer]: update matching sources, import missing ones, drop withdrawn ones.
     *  Returns the default source when it was imported fresh (its guide still needs a first sync). */
    private suspend fun reconcile(answer: German4kPanelAnswer): SourceEntity? {
        val importer = newImporter()
        val activeProfile = settings.activeProfileId.first()
        val firstRun = activeProfile < 0L || profileDao.getById(activeProfile) == null
        if (firstRun) {
            // A start killed during the first import leaves our profile behind without making it
            // active — reuse it instead of creating a second "German4K" every time.
            val ours = profileDao.getAllOnce().firstOrNull { it.name == context.getString(R.string.g4k_profile_name) }
            if (ours != null) importer.useProfile(ours.id)
            else importer.createProfile(context.getString(R.string.g4k_profile_name), avatarId = 0, isKids = false, pin = null)
        } else {
            importer.useProfile(activeProfile)
        }

        val existing = sourceDao.getAllOnce().filter { it.type == SourceType.XTREAM }
        val managed = managedIds().toMutableSet()
        val kept = mutableSetOf<Long>()
        var freshDefault: SourceEntity? = null
        var defaultId: Long? = null

        for (src in answer.sources.sortedByDescending { it.isDefault }) {
            val match = existing.firstOrNull { sameSource(it, src) }
            if (match != null) {
                val ua = German4kUserAgent.wert()
                if (match.password != src.password || match.name != src.name || match.userAgent != ua || !match.url.trimEnd('/').equals(src.server.trimEnd('/'), ignoreCase = true)) {
                    sourceDao.update(match.copy(name = src.name, password = src.password, url = src.server, userAgent = ua))
                }
                kept += match.id
                managed += match.id.toString()
                if (src.isDefault && defaultId == null) defaultId = match.id
                continue
            }
            _state.value = State.Importing(answer, importer)
            // The default host syncs live now; the fallback hosts sync in the background so the first
            // start is not paid for twice. Movies/series always come later (big catalogues).
            val live = if (src.isDefault || kept.isEmpty()) SyncScopeChoice.Now else SyncScopeChoice.Later
            importer.xtream(
                name = src.name, server = src.server, username = src.username, password = src.password,
                userAgent = German4kUserAgent.wert(),
                autoRefresh = PlaylistRefresh(PlaylistAutoRefresh.HOURS_12),
                live = live, movies = SyncScopeChoice.Later, series = SyncScopeChoice.Later,
            )
            when (val st = importer.state.value) {
                is SourceImporter.ImportState.Success -> st.source?.let { kept += it.id; managed += it.id.toString(); if (freshDefault == null) freshDefault = it; if (src.isDefault && defaultId == null) defaultId = it.id }
                is SourceImporter.ImportState.Failed -> {
                    if (kept.isEmpty()) throw IllegalStateException(failureText(st.failure))
                    Log.w(TAG, "fallback host ${src.server} failed: ${st.failure}")
                }
                else -> Unit
            }
        }

        saveManaged(managed)
        dropStale(kept)
        if (firstRun) {
            // One clean list, not "all playlists" with every channel twice: the first host is the
            // active playlist; the fallback host stays selectable under "All playlists".
            defaultId?.let { settings.setDefaultSource(it) }
            installBackground()
            importer.finish()
        }
        return freshDefault
    }

    /** German4K home background from the app's assets — only if the customer has not chosen one. */
    private suspend fun installBackground() {
        runCatching {
            if (settings.bgImagePath.first().isNotBlank()) return
            val target = File(context.filesDir, "german4k-hintergrund.jpg")
            if (!target.exists()) context.assets.open("german4k/hintergrund.jpg").use { input -> target.outputStream().use { input.copyTo(it) } }
            settings.setBgImagePath(target.absolutePath)
        }.onFailure { Log.w(TAG, "background install failed: ${it.message}") }
    }

    private suspend fun syncGuide(source: SourceEntity) {
        for (url in epgRepository.guideUrls(source)) {
            val epgSource = epgStore.getAll().firstOrNull { it.url == url } ?: epgStore.add(source.name, url, source.userAgent)
            val now = System.currentTimeMillis()
            try {
                epgRepository.refreshUrl(epgSource.id, epgSource.url, epgSource.userAgent) { _, _ -> }
                epgStore.setSynced(epgSource.id, now, null)
            } catch (e: Exception) {
                Log.w(TAG, "guide sync failed: ${e.message}")
                epgStore.setSynced(epgSource.id, now, e.message)
            }
        }
    }

    /** Delete sources we created earlier that the panel no longer delivers (never the customer's own). */
    private suspend fun dropStale(keep: Set<Long>) {
        val managed = managedIds()
        if (managed.isEmpty()) return
        val all = sourceDao.getAllOnce()
        val remaining = managed.toMutableSet()
        for (s in all) {
            if (s.id.toString() in managed && s.id !in keep) {
                runCatching { sourceRepository.deleteSource(s) }
                remaining -= s.id.toString()
            }
        }
        saveManaged(remaining)
    }

    /** Same line on any of the panel's hosts — the stored URL may sit on an alternative host from an older answer. */
    private fun sameSource(entity: SourceEntity, src: German4kSource): Boolean {
        val hosts = (listOf(src.server) + src.altServers).map { it.trimEnd('/').lowercase() }
        return entity.url.trimEnd('/').lowercase() in hosts && (entity.username ?: "").equals(src.username, ignoreCase = true)
    }

    /** Hand the host groups of this answer to the failover (one group per source with alternatives). */
    private fun registerHosts(answer: German4kPanelAnswer) {
        German4kHostFailover.register(answer.sources.map { listOf(it.server) + it.altServers })
    }

    private fun failureText(f: SourceImporter.SetupFailure): String = when (f) {
        is SourceImporter.SetupFailure.Sync -> f.failure.toString()
        else -> f.toString()
    }

    private suspend fun cache(answer: German4kPanelAnswer) {
        context.german4kStore.edit { it[Keys.LAST_ANSWER] = answer.toJson() }
    }

    private suspend fun cached(): German4kPanelAnswer? =
        context.german4kStore.data.first()[Keys.LAST_ANSWER]?.let { runCatching { German4kPanelAnswer.parse(it) }.getOrNull() }

    private suspend fun managedIds(): Set<String> = context.german4kStore.data.first()[Keys.MANAGED_IDS] ?: emptySet()

    private suspend fun saveManaged(ids: Set<String>) {
        context.german4kStore.edit { it[Keys.MANAGED_IDS] = ids }
    }

    /** Last panel answer, for screens that only need the hint text / pairing links. */
    suspend fun lastAnswer(): German4kPanelAnswer? = cached()

    /** "Verstanden": hide this hint id for the rest of the day (maintenance hints are never hidden). */
    suspend fun hinweisGesehen(noteId: String) {
        context.german4kStore.edit { it[Keys.HINWEIS_GESEHEN] = "$noteId|${today()}" }
    }

    suspend fun hinweisSchonGesehen(noteId: String): Boolean =
        context.german4kStore.data.first()[Keys.HINWEIS_GESEHEN] == "$noteId|${today()}"

    private fun today(): String = java.time.LocalDate.now().toString()

    /** Debug builds only: show a sample hint of [typ] without the panel (adb: `--es g4k_note_test verlaengern`). */
    fun debugTestNote(typ: String) {
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        // The intent usually arrives before the panel answered: remember it and apply it after the next answer.
        val base = _answer.value ?: run { pendingTestNote = typ; return }
        pendingTestNote = null
        val expire = java.time.LocalDate.now().plusDays(5).toString()
        val (title, body) = when (typ) {
            "verlaengern" -> "Zugang läuft bald ab" to "Dein Zugang läuft am $expire ab. Verlängern: QR-Code scannen oder german4k.com – dann läuft alles ohne Unterbrechung weiter."
            "abgelaufen" -> "Zugang abgelaufen" to "Der Zugang auf diesem Gerät ist abgelaufen. Verlängern: QR-Code scannen oder german4k.com – danach ist die Liste sofort wieder da."
            "wartung" -> "Wartungsarbeiten" to "Beim Rechenzentrum laufen gerade angekündigte Wartungsarbeiten. Bild und Liste können in dieser Zeit fehlen – das ist keine Störung deines Zugangs."
            else -> "Hinweis" to "Testhinweis vom Panel."
        }
        // Like the panel: an expired or locked line comes without sources.
        val expired = typ == "abgelaufen" || typ == "gesperrt"
        _answer.value = base.copy(
            noteTyp = typ, noteId = "$typ:test", noteTitle = title, noteContent = body,
            expireDate = when (typ) { "verlaengern" -> expire; "abgelaufen" -> java.time.LocalDate.now().minusDays(1).toString(); else -> base.expireDate },
            locked = typ == "gesperrt",
            sources = if (expired) emptyList() else base.sources,
        )
    }

    /**
     * Debug builds only: tut so, als liefe ein Test mit [stunden] Reststunden
     * (adb: `--ei g4k_test 4`). Zeigt Chip und Hinweis ohne echten Testzugang.
     */
    fun debugTestzugang(stunden: Int) {
        if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val base = _answer.value ?: run { pendingTestStunden = stunden; return }
        pendingTestStunden = null
        val knapp = stunden <= 6
        val dauer = if (stunden <= 1) "weniger als einer Stunde" else "$stunden Stunden"
        _answer.value = base.copy(
            test = German4kTest(stunden, "https://german4k.com/kaufen"),
            noteTyp = "test",
            noteId = "test:debug-$stunden",
            noteTitle = if (knapp) "Dein Test läuft bald ab" else "Dein Testzugang läuft",
            noteContent = if (knapp) {
                "Dein Testzugang endet in $dauer. Scanne den Code, dann geht es ohne Unterbrechung weiter."
            } else {
                "Du schaust gerade mit einem Testzugang — er läuft noch $dauer. Wenn es dir gefällt, scanne den Code und hol dir den vollen Zugang."
            },
        )
    }

    private var pendingTestNote: String? = null
    private var pendingTestStunden: Int? = null

    private object Keys {
        val LAST_ANSWER = stringPreferencesKey("last_answer")
        val MANAGED_IDS = stringSetPreferencesKey("managed_source_ids")
        val HINWEIS_GESEHEN = stringPreferencesKey("hinweis_gesehen")
    }

    companion object {
        private const val TAG = "German4kProvisioner"
    }
}
