package tv.own.owntv.player

import tv.own.owntv.core.german4k.German4kFeatures
import tv.own.owntv.core.german4k.German4kSchalter

/**
 * German4K 3.2.1 (p-12/1, Schalter `exo_text_bruecke`): Brücke für die laufende Text-Untertitelzeile beim Wechsel
 * mpv → ExoPlayer (Film/Serie).
 *
 * Media3 liest einen eingebetteten Untertitel nur ab der Einstiegsstelle: eine Zeile, die vor der Wechselposition
 * begann, kommt nie an — bis zur nächsten Zeile blieb der Untertitel leer (mit der 10-min-Zeile der Testdatei: ganz).
 * mpv kennt die Zeile (`sub-text`, Ende `sub-end`); sie wird beim Wechsel übernommen und gezeigt, bis ExoPlayer selbst
 * Cues liefert, die Zeile endet, gesprungen wird oder sich die Untertitelwahl ändert. Reine Logik, Thread-sicher genug
 * für mpv-Thread (setzen) und Main (lesen).
 */
internal class ExoTextBruecke {
    @Volatile var text: String? = null
        private set
    @Volatile var endeMs: Long = 0L
        private set

    /** Zeile [zeile] bis [endeMs] (Medienzeit) übernehmen; leere Zeile oder unbekanntes Ende = nichts. */
    fun setzen(zeile: String?, endeMs: Long) {
        val t = zeile?.trim()?.takeIf { it.isNotEmpty() }
        if (t == null || endeMs <= 0L) { leeren(); return }
        this.endeMs = endeMs
        this.text = t
    }

    fun leeren() { text = null; endeMs = 0L }

    val aktiv: Boolean get() = text != null

    /**
     * ExoPlayer meldet Cues ([nichtLeer] = mindestens eine Zeile) bei [positionMs]. Liefert die Brückenzeile, die
     * stattdessen zu zeigen ist, sonst null. Eigene Cues beenden die Brücke endgültig.
     */
    fun beiCues(nichtLeer: Boolean, positionMs: Long): String? {
        if (nichtLeer) { leeren(); return null }
        return gueltigBei(positionMs)
    }

    /** Positions-Takt: die Brückenzeile bei [positionMs] oder null (abgelaufen → endet). */
    fun gueltigBei(positionMs: Long): String? {
        val t = text ?: return null
        if (positionMs >= endeMs) { leeren(); return null }
        return t
    }

    // Wertgleichheit, weil die Brücke in der data class ItemState steckt (frisches Item == frisches Item).
    override fun equals(other: Any?): Boolean = other is ExoTextBruecke && other.text == text && other.endeMs == endeMs
    override fun hashCode(): Int = 31 * (text?.hashCode() ?: 0) + endeMs.hashCode()

    companion object {
        fun aktiv(): Boolean = German4kFeatures.an(German4kSchalter.EXO_TEXT_BRUECKE)

        /** mpv-Eigenschaft `sub-end` (Sekunden, Medienzeit) → ms; unbekannt/ungültig = 0. */
        fun endeAusMpv(subEndSek: Double?): Long =
            subEndSek?.takeIf { it.isFinite() && it > 0.0 }?.let { (it * 1000.0).toLong() } ?: 0L
    }
}
