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

// German4K 3.2.1 (A1, p-6): Gerätegedächtnis „Hardware-Decoder von mpv hängt“ (Schalter mpv_hw_waechter).
//
// Hängt der mpv-Kern auf diesem Gerät mit dem Hardware-Decoder für dieselbe Codec-/Auflösungsklasse zweimal
// (der Wächter in OwnTVPlayer heilt jedes Mal über den Software-Decoder), startet ein Titel dieser Klasse künftig
// gleich ohne Hardware-Decoder — bzw. über 1080p, wo der Software-Decoder nicht reicht (Decode-Guard), als VOD auf
// ExoPlayer. Die Klasse eines Titels kennt man erst, wenn er einmal gespielt hat; darum merkt sich der Speicher je
// Titel (Pin-Schlüssel oder URL-Hash, nie eine URL) seine Klasse.

private val Context.mpvHwStore: DataStore<Preferences> by preferencesDataStore(name = "g4k_mpv_hw")

/** Eine Codec-/Auflösungsklasse mit gezählten Hängern. */
data class MpvHwHaenger(val anzahl: Int, val zuletztMs: Long)

/** Wie ein Titel startet. */
enum class MpvHwStart { HARDWARE, SOFTWARE, EXO }

/** Reine Logik (ohne Android, unit-getestet). */
object MpvHwMerker {
    /** Ab so vielen Hängern einer Klasse startet sie ohne Hardware-Decoder. */
    const val SCHWELLE = 2
    /** Älter als das: wieder mit Hardware versuchen (Firmware-Update, neuer Decoder). */
    const val VERFALL_MS = 30L * 24 * 60 * 60 * 1000
    const val MAX_TITEL = 500
    const val MAX_KLASSEN = 40
    /** Über dieser Höhe schafft der Software-Decoder es nicht (OwnTVPlayer.enforceDecodeGuard bricht ab). */
    const val SOFTWARE_MAX_HOEHE = 1080

    /** Codec-Familie aus mpvs `video-codec` (Kurz- oder Langname), null = unbekannt. */
    fun codecFamilie(codec: String?): String? {
        val c = codec?.trim()?.lowercase() ?: return null
        return when {
            c.isEmpty() -> null
            c.startsWith("h264") || c.startsWith("avc") || c.startsWith("h.264") -> "avc"
            c.startsWith("hevc") || c.startsWith("h265") || c.startsWith("h.265") -> "hevc"
            c.startsWith("av1") -> "av1"
            c.startsWith("vp9") -> "vp9"
            c.startsWith("vp8") -> "vp8"
            c.startsWith("mpeg2") || c.startsWith("mpeg-2") -> "mpeg2"
            c.startsWith("mpeg4") || c.startsWith("mpeg-4") -> "mpeg4"
            c.startsWith("vc1") || c.startsWith("vc-1") -> "vc1"
            else -> c.substringBefore(' ').take(16)
        }
    }

    /** Auflösungsklasse: sd ≤ 576, hd ≤ 720, fhd ≤ 1080, uhd darüber; null = unbekannt. */
    fun klasse(hoehe: Int): String? = when {
        hoehe <= 0 -> null
        hoehe <= 576 -> "sd"
        hoehe <= 720 -> "hd"
        hoehe <= SOFTWARE_MAX_HOEHE -> "fhd"
        else -> "uhd"
    }

    /** Schlüssel „codec|klasse“, null wenn eines fehlt. */
    fun schluessel(codec: String?, hoehe: Int): String? {
        val f = codecFamilie(codec) ?: return null
        val k = klasse(hoehe) ?: return null
        return "$f|$k"
    }

    /**
     * Startweg für einen Titel, dessen Klasse [klassenSchluessel] (null = unbekannt) bekannt ist.
     * [exoErlaubt] = Einstellung lässt eine Übergabe an ExoPlayer zu und es ist ein VOD (kein Live, kein Archiv).
     */
    fun start(
        haenger: Map<String, MpvHwHaenger>,
        klassenSchluessel: String?,
        exoErlaubt: Boolean,
        jetztMs: Long,
    ): MpvHwStart {
        val k = klassenSchluessel ?: return MpvHwStart.HARDWARE
        val h = haenger[k] ?: return MpvHwStart.HARDWARE
        if (h.anzahl < SCHWELLE || verfallen(h, jetztMs)) return MpvHwStart.HARDWARE
        return if (k.endsWith("|uhd")) {
            if (exoErlaubt) MpvHwStart.EXO else MpvHwStart.HARDWARE // Software schafft uhd nicht; Wächter heilt weiter
        } else {
            MpvHwStart.SOFTWARE
        }
    }

    fun verfallen(h: MpvHwHaenger, jetztMs: Long): Boolean = jetztMs - h.zuletztMs > VERFALL_MS

    /** Einen Hänger der Klasse [k] zählen (verfallene Zählung beginnt neu). */
    fun zaehlen(haenger: LinkedHashMap<String, MpvHwHaenger>, k: String, jetztMs: Long) {
        val alt = haenger[k]?.takeUnless { verfallen(it, jetztMs) }
        setzen(haenger, k, MpvHwHaenger((alt?.anzahl ?: 0) + 1, jetztMs), MAX_KLASSEN)
    }

    fun <V> setzen(map: LinkedHashMap<String, V>, key: String, v: V, max: Int) {
        map.remove(key)
        map[key] = v
        val it = map.entries.iterator()
        while (map.size > max && it.hasNext()) { it.next(); it.remove() }
    }

    fun haengerLesen(text: String?): LinkedHashMap<String, MpvHwHaenger> {
        val out = LinkedHashMap<String, MpvHwHaenger>()
        text?.lineSequence()?.forEach { z ->
            val t = z.split('\t')
            if (t.size < 3 || t[0].isBlank()) return@forEach
            val n = t[1].toIntOrNull() ?: return@forEach
            val ms = t[2].toLongOrNull() ?: return@forEach
            out[t[0]] = MpvHwHaenger(n, ms)
        }
        return out
    }

    fun haengerSchreiben(m: Map<String, MpvHwHaenger>): String =
        m.entries.joinToString("\n") { (k, h) -> "$k\t${h.anzahl}\t${h.zuletztMs}" }

    fun titelLesen(text: String?): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        text?.lineSequence()?.forEach { z ->
            val t = z.split('\t')
            if (t.size < 2 || t[0].isBlank() || t[1].isBlank()) return@forEach
            out[t[0]] = t[1]
        }
        return out
    }

    fun titelSchreiben(m: Map<String, String>): String = m.entries.joinToString("\n") { (k, v) -> "$k\t$v" }
}

/**
 * Persistenter Speicher zu [MpvHwMerker]; eine Instanz je Prozess ([get]). Der Player fragt synchron auf dem
 * Main-Thread ([start]) — darum ein Speicher-Abbild, das [laden] einmal füllt; Schreiben asynchron.
 */
class MpvHwStore private constructor(private val context: Context) {
    private val haengerKey = stringPreferencesKey("haenger")
    private val titelKey = stringPreferencesKey("titel")
    private val lock = Mutex()
    @Volatile private var haenger: LinkedHashMap<String, MpvHwHaenger> = LinkedHashMap()
    @Volatile private var titel: LinkedHashMap<String, String> = LinkedHashMap()
    @Volatile private var geladen = false

    suspend fun laden() = lock.withLock {
        if (geladen) return@withLock
        val p = context.mpvHwStore.data.first()
        haenger = MpvHwMerker.haengerLesen(p[haengerKey])
        titel = MpvHwMerker.titelLesen(p[titelKey])
        geladen = true
    }

    /** Synchron, aus dem Abbild: Startweg für [titelSchluessel] (vor [laden] immer HARDWARE). */
    fun start(titelSchluessel: String, exoErlaubt: Boolean, jetztMs: Long): MpvHwStart =
        MpvHwMerker.start(haenger, titel[titelSchluessel], exoErlaubt, jetztMs)

    /** Klasse eines gespielten Titels merken; schreibt nur bei Änderung. */
    suspend fun merkeTitel(titelSchluessel: String, klassenSchluessel: String) = lock.withLock {
        if (!geladen || titel[titelSchluessel] == klassenSchluessel) return@withLock
        val m = LinkedHashMap(titel)
        MpvHwMerker.setzen(m, titelSchluessel, klassenSchluessel, MpvHwMerker.MAX_TITEL)
        titel = m
        context.mpvHwStore.edit { it[titelKey] = MpvHwMerker.titelSchreiben(m) }
    }

    /** Ein Hänger des Hardware-Decoders für [klassenSchluessel]; liefert die neue Zahl. */
    suspend fun merkeHaenger(titelSchluessel: String, klassenSchluessel: String, jetztMs: Long): Int = lock.withLock {
        if (!geladen) return@withLock 0
        val h = LinkedHashMap(haenger)
        MpvHwMerker.zaehlen(h, klassenSchluessel, jetztMs)
        haenger = h
        val t = LinkedHashMap(titel)
        MpvHwMerker.setzen(t, titelSchluessel, klassenSchluessel, MpvHwMerker.MAX_TITEL)
        titel = t
        context.mpvHwStore.edit {
            it[haengerKey] = MpvHwMerker.haengerSchreiben(h)
            it[titelKey] = MpvHwMerker.titelSchreiben(t)
        }
        h[klassenSchluessel]?.anzahl ?: 0
    }

    companion object {
        @Volatile private var instanz: MpvHwStore? = null
        fun get(context: Context): MpvHwStore =
            instanz ?: synchronized(this) { instanz ?: MpvHwStore(context.applicationContext).also { instanz = it } }
    }
}
