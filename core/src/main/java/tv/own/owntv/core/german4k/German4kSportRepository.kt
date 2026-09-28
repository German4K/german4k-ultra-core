package tv.own.owntv.core.german4k

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import tv.own.owntv.core.CoreBuildInfo

private val Context.german4kSportStore: DataStore<Preferences> by preferencesDataStore(name = "german4k_sport")

/**
 * Spielplan des Sport-Hubs: hält die letzte Antwort, fragt alle 60 s nach, solange ein Bildschirm
 * [starteAbfrage] gerufen hat. Die letzte gute Antwort liegt im DataStore, damit der Bereich auch
 * offline sofort etwas zeigt ([offline] = true, Stand bleibt sichtbar).
 */
class German4kSportRepository(
    private val context: Context,
    private val panel: German4kPanelClient,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _antwort = MutableStateFlow<German4kSportAntwort?>(null)
    val antwort: StateFlow<German4kSportAntwort?> = _antwort.asStateFlow()
    private val _offline = MutableStateFlow(false)
    val offline: StateFlow<Boolean> = _offline.asStateFlow()

    @Volatile private var abfrage: Job? = null

    init {
        scope.launch {
            val text = runCatching { context.german4kSportStore.data.first()[KEY_LAST] }.getOrNull()
            val cached = text?.let { German4kSportAntwort.parse(it) }
            if (cached != null && _antwort.value == null) { _antwort.value = cached; _offline.value = false }
        }
    }

    /** Jetzt vom Server holen. Erfolg → speichern; `ok=false` → Cache leeren, Antwort mit Grund zeigen;
     *  Transportfehler → offline, letzte Antwort bleibt. */
    suspend fun ladeJetzt(): German4kSportAntwort? {
        val neu = panel.sport(German4kDeviceId.get(context), CoreBuildInfo.versionName)
        if (neu == null) {
            _offline.value = true
            return null
        }
        _offline.value = false
        _antwort.value = neu
        runCatching {
            context.german4kSportStore.edit { p -> if (neu.ok) p[KEY_LAST] = neu.toJson() else p.remove(KEY_LAST) }
        }.onFailure { Log.w(TAG, "Cache: ${it.message}") }
        return neu
    }

    /** Idempotent: ein zweiter Aufruf startet keine zweite Schleife. */
    @Synchronized
    fun starteAbfrage() {
        if (abfrage?.isActive == true) return
        abfrage = scope.launch {
            while (isActive) {
                ladeJetzt()
                delay(INTERVALL_MS)
            }
        }
    }

    @Synchronized
    fun stoppeAbfrage() {
        abfrage?.cancel()
        abfrage = null
    }

    private companion object {
        val KEY_LAST = stringPreferencesKey("sport_last_answer")
        const val TAG = "German4kSport"
        const val INTERVALL_MS = 60_000L
    }
}
