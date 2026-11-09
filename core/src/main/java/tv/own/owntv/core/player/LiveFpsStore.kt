package tv.own.owntv.core.player

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// German4K 3.2.1 (B7): je Live-Sender die zuletzt sicher gemessene Bildrate und der letzte echte ExoPlayer-Fehler.
//
// Anlass: 50-fps-Sport ruckelt auf Fire Sticks unter mpv, ExoPlayer spielt ihn flüssig. Ein Sender, der einmal per
// Watchdog auf mpv gefallen ist oder dort angeheftet wurde, käme sonst nie mehr zu ExoPlayer zurück.

private val Context.liveFpsStore: DataStore<Preferences> by preferencesDataStore(name = "g4k_live_fps")

/** Ein gemerkter Sender. [exoFehlerMs] = Wanduhr des letzten echten ExoPlayer-Fehlers (0 = keiner). */
data class LiveFpsEintrag(val fps: Float, val gesehenMs: Long, val exoFehlerMs: Long = 0L) {
    val hochFps: Boolean get() = fps >= LiveFpsWahl.HOCH_FPS
}

/**
 * Reine Tabellenlogik (ohne Android, unit-getestet): LRU mit höchstens [MAX] Einträgen, Zeilenformat
 * `schlüssel\tfps\tgesehen\texoFehler`. Der Schlüssel ist [enginePinKey] oder ein URL-Hash — nie eine URL.
 */
object LiveFpsTabelle {
    const val MAX = 500

    fun lesen(text: String?): LinkedHashMap<String, LiveFpsEintrag> {
        val out = LinkedHashMap<String, LiveFpsEintrag>()
        text?.lineSequence()?.forEach { zeile ->
            val t = zeile.split('\t')
            if (t.size < 3 || t[0].isBlank()) return@forEach
            val fps = t[1].toFloatOrNull() ?: return@forEach
            val gesehen = t[2].toLongOrNull() ?: return@forEach
            val fehler = t.getOrNull(3)?.toLongOrNull() ?: 0L
            out[t[0]] = LiveFpsEintrag(fps, gesehen, fehler)
        }
        return out
    }

    fun schreiben(map: Map<String, LiveFpsEintrag>): String =
        map.entries.joinToString("\n") { (k, e) -> "$k\t${e.fps}\t${e.gesehenMs}\t${e.exoFehlerMs}" }

    /** [key] ans Ende (zuletzt benutzt) setzen und die ältesten über [max] verwerfen. */
    fun setzen(map: LinkedHashMap<String, LiveFpsEintrag>, key: String, e: LiveFpsEintrag, max: Int = MAX) {
        map.remove(key)
        map[key] = e
        val it = map.entries.iterator()
        while (map.size > max && it.hasNext()) { it.next(); it.remove() }
    }

    /** Schlüssel für einen Sender: stabiler Pin-Schlüssel, sonst Hash der Stream-URL (keine Tokens im Speicher). */
    fun schluessel(pinKey: String?, streamUrl: String): String =
        pinKey ?: "u:" + Integer.toHexString(streamUrl.hashCode())
}

/**
 * Reine Entscheidung (unit-getestet): startet dieser Tune wegen gemessener hoher Bildrate auf ExoPlayer, obwohl
 * Pin/Einstellung/Panel-Lehre ihn auf mpv schicken würden?
 *
 * - Schalter `live_fps_exo` aus → nie.
 * - DRM → ohnehin ExoPlayer, nichts zu tun.
 * - Würde ohnehin auf ExoPlayer starten → nichts zu tun.
 * - Einstellung „Nur mpv“ → ausdrückliche Kundenwahl, nie.
 * - Panel lehnt ExoPlayers Segmente ab → ExoPlayer kann dort nicht, nie.
 * - Echter ExoPlayer-Fehler auf diesem Sender in den letzten 24 h → nie (sonst Kreislauf Exo-Fehler ↔ hochfps).
 * - Sonst: gemessen ≥ [HOCH_FPS] → ExoPlayer zuerst (mit Rückfall auf mpv).
 */
object LiveFpsWahl {
    const val HOCH_FPS = 48f
    const val FEHLER_SPERRE_MS = 24L * 60 * 60 * 1000

    fun exoWegenHochFps(
        schalterAn: Boolean,
        eintrag: LiveFpsEintrag?,
        setting: EnginePreference,
        pin: Boolean?,
        panelLehntAb: Boolean,
        drm: Boolean,
        jetztMs: Long,
    ): Boolean {
        if (!schalterAn || drm || eintrag == null || !eintrag.hochFps) return false
        if (setting == EnginePreference.MPV_ONLY) return false
        val wuerdeMpv = pin ?: (panelLehntAb || setting.startsOnMpv)
        if (!wuerdeMpv) return false
        if (pin == null && panelLehntAb) return false
        if (exoFehlerFrisch(eintrag.exoFehlerMs, jetztMs)) return false
        return true
    }

    /** German4K 3.2.1 (B7, Review): Merker „Kunde hat mpv gewählt“ — sperrt hochfps dauerhaft (Zeit in der Zukunft). */
    const val KUNDE_MPV_DAUERHAFT = Long.MAX_VALUE

    /** Zeit für den Exo-Fehler-Merker: Kundenwahl mpv (Toggle/Pin) dauerhaft, automatischer Exo-Fehler 24 h. */
    fun fehlerZeit(kundenWahlMpv: Boolean, jetztMs: Long): Long = if (kundenWahlMpv) KUNDE_MPV_DAUERHAFT else jetztMs

    /** Fehler aus den letzten 24 h. Eine Zeit in der Zukunft (Uhr zurückgestellt) zählt vorsichtshalber als frisch. */
    fun exoFehlerFrisch(fehlerMs: Long, jetztMs: Long): Boolean =
        fehlerMs > 0L && jetztMs - fehlerMs < FEHLER_SPERRE_MS
}

/** Persistenter Speicher zu [LiveFpsTabelle]; eine Instanz je Prozess ([get]). Lesen aus dem Speicher-Abbild. */
class LiveFpsStore private constructor(private val context: Context) {
    private val key = stringPreferencesKey("eintraege")
    private val lock = Mutex()
    private var cache: LinkedHashMap<String, LiveFpsEintrag>? = null

    private suspend fun map(): LinkedHashMap<String, LiveFpsEintrag> =
        cache ?: LiveFpsTabelle.lesen(context.liveFpsStore.data.first()[key]).also { cache = it }

    suspend fun eintrag(k: String): LiveFpsEintrag? = lock.withLock { map()[k] }

    /** Sichere Messung merken. Schreibt nur, wenn sich hochfps ändert oder der Sender neu ist (keine Dauerschreiberei). */
    suspend fun merkeFps(k: String, fps: Float, jetztMs: Long) = lock.withLock {
        val m = map()
        val alt = m[k]
        if (alt != null && alt.hochFps == (fps >= LiveFpsWahl.HOCH_FPS)) return@withLock
        LiveFpsTabelle.setzen(m, k, LiveFpsEintrag(fps, jetztMs, alt?.exoFehlerMs ?: 0L))
        speichern(m)
    }

    /**
     * Echter ExoPlayer-Fehler (Rückfall auf mpv) oder Kundenwechsel weg von ExoPlayer. [fehlerMs] siehe
     * [LiveFpsWahl.fehlerZeit] — German4K 3.2.1 (B7, Review): Kundenwahl mpv gilt dauerhaft.
     */
    suspend fun merkeExoFehler(k: String, jetztMs: Long, fehlerMs: Long = jetztMs) = lock.withLock {
        val m = map()
        val alt = m[k] ?: LiveFpsEintrag(0f, jetztMs)
        LiveFpsTabelle.setzen(m, k, alt.copy(gesehenMs = jetztMs, exoFehlerMs = fehlerMs))
        speichern(m)
    }

    private suspend fun speichern(m: Map<String, LiveFpsEintrag>) {
        val text = LiveFpsTabelle.schreiben(m)
        context.liveFpsStore.edit { it[key] = text }
    }

    companion object {
        @Volatile private var instanz: LiveFpsStore? = null
        fun get(context: Context): LiveFpsStore =
            instanz ?: synchronized(this) { instanz ?: LiveFpsStore(context.applicationContext).also { instanz = it } }
    }
}
