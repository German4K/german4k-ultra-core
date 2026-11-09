package tv.own.owntv.player

import android.content.Context
import tv.own.owntv.core.german4k.German4kFeatures
import tv.own.owntv.core.german4k.German4kSchalter

/**
 * German4K 3.2.1 (q-3, C): die Ton- und Untertitelwahl je Film/Folge, damit „Fortsetzen" sie wieder setzt.
 *
 * Vorher galt eine Wahl nur, solange der Titel lief — beim Fortsetzen begann er wieder mit der Standardspur
 * (Deutsch statt der gewählten „English"). Gespeichert wird die engine-neutrale [SpurWunsch]-Kennung, die auch
 * den Engine-Wechsel trägt (ger ≙ de, Titel, Bild/Text), damit sie auf mpv UND ExoPlayer wiedergefunden wird.
 *
 * SharedPreferences statt Room (Schema bleibt 41); höchstens [MAX_TITEL] Titel, die ältesten fallen heraus.
 */
internal class SpurMerker(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(DATEI, Context.MODE_PRIVATE)

    /** Gemerkte Wahl für [key]; null = nichts gemerkt. */
    fun lesen(key: String): Gemerkt? = runCatching {
        val roh = prefs.getString(key, null) ?: return null
        decode(roh)
    }.getOrNull()

    fun audio(key: String, w: SpurWunsch) = schreiben(key) { it.copy(audio = w) }
    fun untertitel(key: String, w: SpurWunsch?) = schreiben(key) { it.copy(sub = w, subAus = w == null) }

    private fun schreiben(key: String, aendern: (Gemerkt) -> Gemerkt) {
        runCatching {
            val neu = aendern(lesen(key) ?: Gemerkt()).copy(zeitMs = System.currentTimeMillis())
            val ed = prefs.edit().putString(key, encode(neu))
            val alle = prefs.all
            if (alle.size > MAX_TITEL) {
                alle.entries
                    .sortedBy { e -> (e.value as? String)?.let { decode(it)?.zeitMs } ?: 0L }
                    .take(alle.size - MAX_TITEL)
                    .forEach { if (it.key != key) ed.remove(it.key) }
            }
            ed.apply()
        }
    }

    /** [subAus] = der Zuschauer hat „Aus" gewählt (≠ nichts gemerkt). */
    data class Gemerkt(
        val audio: SpurWunsch? = null,
        val sub: SpurWunsch? = null,
        val subAus: Boolean = false,
        val zeitMs: Long = 0L,
    )

    companion object {
        private const val DATEI = "g4k_spur_merker"
        const val MAX_TITEL = 400
        private const val FELD = '\u001F'
        private const val TEIL = '\u001E'

        fun aktiv(): Boolean = UntertitelWechsel.aktiv() && German4kFeatures.an(German4kSchalter.VOD_SPUR_MERKEN)

        internal fun encodeWunsch(w: SpurWunsch?): String =
            if (w == null) "" else listOf(w.lang.orEmpty(), if (w.image) "1" else "0", w.ordinal.toString(), w.label.orEmpty())
                .joinToString(FELD.toString())

        internal fun decodeWunsch(s: String): SpurWunsch? {
            if (s.isEmpty()) return null
            val f = s.split(FELD)
            if (f.size < 4) return null
            return SpurWunsch(
                lang = f[0].ifEmpty { null },
                image = f[1] == "1",
                ordinal = f[2].toIntOrNull() ?: -1,
                label = f[3].ifEmpty { null },
            )
        }

        internal fun encode(g: Gemerkt): String =
            listOf(encodeWunsch(g.audio), encodeWunsch(g.sub), if (g.subAus) "1" else "0", g.zeitMs.toString())
                .joinToString(TEIL.toString())

        internal fun decode(s: String): Gemerkt? {
            val t = s.split(TEIL)
            if (t.size < 4) return null
            return Gemerkt(
                audio = decodeWunsch(t[0]),
                sub = decodeWunsch(t[1]),
                subAus = t[2] == "1",
                zeitMs = t[3].toLongOrNull() ?: 0L,
            )
        }
    }
}

/**
 * German4K 3.2.1 (q-3, D): Bild-Untertitel an der Spur erkennen — auf BEIDEN ExoPlayer-Wegen gleich.
 * Media3 zerlegt eingebettete Untertitel beim Extrahieren, die Sample-MIME ist dann
 * `application/x-media3-cues` und das Ursprungsformat steht in `codecs`.
 */
internal object BildUntertitel {
    fun istBild(sampleMime: String?, codecs: String?): Boolean {
        val mime = if (sampleMime == androidx.media3.common.MimeTypes.APPLICATION_MEDIA3_CUES) codecs ?: sampleMime else sampleMime
        return mime == androidx.media3.common.MimeTypes.APPLICATION_PGS ||
            mime == androidx.media3.common.MimeTypes.APPLICATION_VOBSUB ||
            mime == androidx.media3.common.MimeTypes.APPLICATION_DVBSUBS
    }
}

/**
 * German4K 3.2.1 (q-3, A): Spurwahl, die ein Live-Engine-Wechsel mpv → ExoPlayer mitnimmt. ExoPlayer meldet
 * seine Spuren erst nach dem Öffnen und teils schrittweise (Ton vor Untertitel) — jede Hälfte wird angewandt,
 * sobald ihre Liste da ist, und danach vergessen.
 */
internal class LiveSpurUebergabe {
    @Volatile var sub: SpurWunsch? = null
        private set
    @Volatile var audio: SpurWunsch? = null
        private set

    val offen: Boolean get() = sub != null || audio != null

    /**
     * German4K 3.2.1 (p-14, Fund 10): die mitgenommene Tonspur wurde in der Exo-Liste wirklich gefunden (gewählt oder
     * schon aktiv). Nur dann entfällt die Titelwahl „Deutsch" — eine übergebene, aber nicht gefundene Spur darf sie nicht
     * abschalten, sonst bliebe Exos Eigenwahl stehen.
     */
    @Volatile var tonGetroffen: Boolean = false
        private set

    fun setzen(sub: SpurWunsch?, audio: SpurWunsch?) { this.sub = sub; this.audio = audio; tonGetroffen = false }
    fun leeren() { sub = null; audio = null; tonGetroffen = false }

    /**
     * Was jetzt zu wählen ist: (Untertitel-ID, Ton-ID) aus den aktuellen Listen, je null = noch nichts/nichts
     * Passendes. Eine Hälfte, deren Liste schon da ist, gilt als erledigt — auch ohne Treffer, damit ein
     * späteres Spur-Update nicht doch noch eine fremde Sprache erzwingt.
     */
    fun anwenden(text: List<TrackOption>, ton: List<TrackOption>): Pair<Int?, Int?> {
        var subId: Int? = null
        var tonId: Int? = null
        sub?.let { w ->
            if (text.isNotEmpty()) {
                subId = UntertitelWechsel.finde(text, w)?.takeIf { !it.selected }?.mpvId
                sub = null
            }
        }
        audio?.let { w ->
            if (ton.isNotEmpty()) {
                val treffer = UntertitelWechsel.finde(ton, w)
                if (treffer != null) tonGetroffen = true
                tonId = treffer?.takeIf { !it.selected }?.mpvId
                audio = null
            }
        }
        return subId to tonId
    }

    companion object {
        fun aktiv(): Boolean = UntertitelWechsel.aktiv() && German4kFeatures.an(German4kSchalter.LIVE_SPUR_WECHSEL)
    }
}
