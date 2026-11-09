package tv.own.owntv.core.german4k

import kotlin.math.pow

/**
 * German4K 3.2.1 (U39): WCAG-Kontrast für Schrift auf farbigen Flächen (Kopfleisten-Chips).
 * Farben als ARGB-Int; Alpha wird ignoriert (gerechnet wird gegen die deckende Fläche).
 */
object German4kKontrast {
    /** WCAG AA für normale Schrift. */
    const val MINDEST = 4.5

    private fun kanal(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }

    /** Relative Leuchtdichte nach WCAG 2.x. */
    fun leuchtdichte(argb: Int): Double =
        0.2126 * kanal((argb shr 16) and 0xFF) + 0.7152 * kanal((argb shr 8) and 0xFF) + 0.0722 * kanal(argb and 0xFF)

    /** Kontrastverhältnis 1…21. */
    fun verhaeltnis(a: Int, b: Int): Double {
        val la = leuchtdichte(a)
        val lb = leuchtdichte(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /**
     * [wunsch], wenn es auf [flaeche] mindestens [mindest] erreicht; sonst Weiß oder Schwarz — je nachdem,
     * was auf dieser Fläche mehr Kontrast hat.
     */
    fun schrift(flaeche: Int, wunsch: Int, mindest: Double = MINDEST): Int {
        if (verhaeltnis(flaeche, wunsch) >= mindest) return wunsch
        val weiss = 0xFFFFFFFF.toInt()
        val schwarz = 0xFF000000.toInt()
        return if (verhaeltnis(flaeche, weiss) >= verhaeltnis(flaeche, schwarz)) weiss else schwarz
    }

    /**
     * Deckende Fokusfläche aus [akzent], auf der weiße Schrift mindestens [mindest] erreicht: der Akzent selbst,
     * wenn er dunkel genug ist, sonst schrittweise zu Schwarz gemischt (Farbton bleibt). Für fokussierte
     * Kopfleisten-Chips („helle Schrift auf Lila").
     */
    fun flaecheFuerWeiss(akzent: Int, mindest: Double = MINDEST): Int {
        val weiss = 0xFFFFFFFF.toInt()
        var r = (akzent shr 16) and 0xFF
        var g = (akzent shr 8) and 0xFF
        var b = akzent and 0xFF
        fun farbe() = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        repeat(40) {
            if (verhaeltnis(farbe(), weiss) >= mindest) return farbe()
            r = (r * 0.92).toInt(); g = (g * 0.92).toInt(); b = (b * 0.92).toInt()
        }
        return farbe()
    }

    /**
     * German4K 3.2.1 (U51): erste Farbe aus [kandidaten] mit ≥ [MINDEST] auf [grund]; sonst die mit dem höchsten
     * Kontrast. Für gewählte SECONDARY-Knöpfe (Glas-Voreinstellung „Ausgewogen": dunkle Schrift auf dunklem Grund).
     */
    fun lesbar(grund: Int, vararg kandidaten: Int): Int =
        kandidaten.firstOrNull { verhaeltnis(it, grund) >= MINDEST }
            ?: kandidaten.maxByOrNull { verhaeltnis(it, grund) }
            ?: grund
}
