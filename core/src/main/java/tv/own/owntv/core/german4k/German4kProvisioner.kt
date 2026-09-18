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
import tv.own.owntv.core.settings.SettingsRepository
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

        var fromCache = false
        val answer: German4kPanelAnswer = try {
            panel.fetch(deviceId, version, username, password).also { cache(it) }
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
        if (firstRun) importer.createProfile(context.getString(R.string.g4k_profile_name), avatarId = 0, isKids = false, pin = null)
        else importer.useProfile(activeProfile)

        val existing = sourceDao.getAllOnce().filter { it.type == SourceType.XTREAM }
        val managed = managedIds().toMutableSet()
        val kept = mutableSetOf<Long>()
        var freshDefault: SourceEntity? = null

        for (src in answer.sources.sortedByDescending { it.isDefault }) {
            val match = existing.firstOrNull { sameSource(it, src) }
            if (match != null) {
                if (match.password != src.password || match.name != src.name) {
                    sourceDao.update(match.copy(name = src.name, password = src.password))
                }
                kept += match.id
                managed += match.id.toString()
                continue
            }
            _state.value = State.Importing(answer, importer)
            // The default host syncs live now; the fallback hosts sync in the background so the first
            // start is not paid for twice. Movies/series always come later (big catalogues).
            val live = if (src.isDefault || kept.isEmpty()) SyncScopeChoice.Now else SyncScopeChoice.Later
            importer.xtream(
                name = src.name, server = src.server, username = src.username, password = src.password,
                live = live, movies = SyncScopeChoice.Later, series = SyncScopeChoice.Later,
            )
            when (val st = importer.state.value) {
                is SourceImporter.ImportState.Success -> st.source?.let { kept += it.id; managed += it.id.toString(); if (freshDefault == null) freshDefault = it }
                is SourceImporter.ImportState.Failed -> {
                    if (kept.isEmpty()) throw IllegalStateException(failureText(st.failure))
                    Log.w(TAG, "fallback host ${src.server} failed: ${st.failure}")
                }
                else -> Unit
            }
        }

        saveManaged(managed)
        dropStale(kept)
        if (firstRun) importer.finish()
        return freshDefault
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

    private fun sameSource(entity: SourceEntity, src: German4kSource): Boolean =
        entity.url.trimEnd('/').equals(src.server.trimEnd('/'), ignoreCase = true) &&
            (entity.username ?: "").equals(src.username, ignoreCase = true)

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

    private object Keys {
        val LAST_ANSWER = stringPreferencesKey("last_answer")
        val MANAGED_IDS = stringSetPreferencesKey("managed_source_ids")
    }

    companion object {
        private const val TAG = "German4kProvisioner"
    }
}
