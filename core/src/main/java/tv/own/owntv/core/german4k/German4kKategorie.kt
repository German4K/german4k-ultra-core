package tv.own.owntv.core.german4k

import java.util.Locale

/**
 * German4K 3.0/32 (L2): Kategorienamen in den Listen links (Live, Filme, Serien, Handy-Liste).
 *
 * Die Anbieterliste schmückt Ordner mit Emoji ("▶️ Neu", "⚽ Fußball"). Die Länderflagge davor ist
 * eine Information und bleibt, der übrige Schmuck am Anfang fällt weg. Außerdem führen manche Listen
 * einen eigenen Ordner „Alle Filme" / „Alle Serien" / „Alle Sender" — doppelt zu unserem eigenen
 * „Alle"-Eintrag ganz oben, deshalb wird er ausgeblendet ([istAlleOrdner]).
 */
object German4kKategorie {
    private const val REGIONAL_A = 0x1F1E6
    private const val REGIONAL_Z = 0x1F1FF
    private const val VARIATION = 0xFE0F
    private const val ZWJ = 0x200D
    private const val KEYCAP = 0x20E3
    private const val TRENNER = "|•·-–—:"

    private fun istRegional(cp: Int) = cp in REGIONAL_A..REGIONAL_Z

    private fun istSchmuck(cp: Int): Boolean =
        cp == VARIATION || cp == ZWJ || cp == KEYCAP ||
            cp in 0x1F3FB..0x1F3FF ||
            (!istRegional(cp) && Character.getType(cp) == Character.OTHER_SYMBOL.toInt())

    /** Name ohne führenden Emoji-Schmuck; Flaggen am Anfang bleiben. Nie leer, wenn [name] es nicht ist. */
    fun anzeige(name: String): String {
        val flaggen = StringBuilder()
        var i = 0
        var entfernt = false
        while (i < name.length) {
            val cp = name.codePointAt(i)
            val n = Character.charCount(cp)
            when {
                Character.isWhitespace(cp) -> i += n
                istRegional(cp) && i + n < name.length && istRegional(name.codePointAt(i + n)) -> {
                    val m = Character.charCount(name.codePointAt(i + n))
                    flaggen.append(name, i, i + n + m)
                    i += n + m
                }
                istSchmuck(cp) -> { i += n; entfernt = true }
                entfernt && TRENNER.indexOf(name[i]) >= 0 -> i += 1
                else -> break
            }
        }
        val rest = name.substring(i).trim()
        if (rest.isEmpty()) return name.trim()
        return if (flaggen.isEmpty()) rest else "$flaggen $rest"
    }

    private val ALLE = setOf("alle filme", "alle serien", "alle sender", "all movies", "all series", "all channels")

    /** Ein Anbieterordner, der nur „alle Filme/Serien/Sender" heißt (mit oder ohne Flagge/Schmuck). */
    fun istAlleOrdner(name: String): Boolean {
        val ohneFlagge = ohneFlagge(anzeige(name))
        return ohneFlagge.lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ") in ALLE
    }

    private fun schlicht(name: String) = ohneFlagge(anzeige(name)).lowercase(Locale.ROOT).replace(Regex("""\s+"""), " ").trim()

    /** German4K 3.2.1 (D3): Server-Ordner „🆕 Neu diese Woche“ (Ultra 3.2 N3). */
    fun istNeuOrdner(name: String): Boolean = schlicht(name) == NEU_ORDNER

    /** Server-Ordner „🔥 Angesagt“. */
    fun istAngesagtOrdner(name: String): Boolean = schlicht(name) == ANGESAGT_ORDNER

    /**
     * German4K 3.2.1 (D3): „Neu diese Woche“ gehört zu den Trend-Ordnern oben — direkt hinter „Angesagt“,
     * ohne diesen ganz nach vorn. Die Ultra entfernt das Emoji und sortierte ihn bisher ans Ende.
     * Alles andere behält seine Reihenfolge.
     */
    fun <T> neuNachVorn(liste: List<T>, name: (T) -> String): List<T> {
        val i = liste.indexOfFirst { istNeuOrdner(name(it)) }
        if (i < 0) return liste
        val rest = liste.toMutableList().also { it.removeAt(i) }
        val angesagt = rest.indexOfFirst { istAngesagtOrdner(name(it)) }
        rest.add(if (angesagt >= 0) angesagt + 1 else 0, liste[i])
        return rest
    }

    /** German4K 3.2.1 (U6): Server-Ordner „🏆 Top 10 Filme/Serien“ (lib/tv/angesagt.ts). */
    fun istTopOrdner(name: String): Boolean = schlicht(name) in TOP_ORDNER

    /**
     * German4K 3.2.1 (U6): Bei Sortierung A–Z ordnete die Ultra auch die Ordner alphabetisch — die Trend-Ordner
     * (Emoji vorn, Emoji fällt in der Anzeige weg, beim Sortieren nicht) landeten zwischen den Länderflaggen und
     * am Ende (Serien standen auf A–Z, Filme auf Playlist). Hier rücken sie in fester Reihenfolge
     * Top 10 → Angesagt → Neu diese Woche an den Anfang; alles andere behält seine Reihenfolge.
     */
    fun <T> trendNachVorn(liste: List<T>, name: (T) -> String): List<T> {
        fun rang(n: String): Int = when {
            istTopOrdner(n) -> 0
            istAngesagtOrdner(n) -> 1
            istNeuOrdner(n) -> 2
            else -> -1
        }
        val raenge = liste.map { rang(name(it)) }
        if (raenge.all { it < 0 }) return liste
        val trend = liste.indices.filter { raenge[it] >= 0 }.sortedBy { raenge[it] }.map { liste[it] }
        return trend + liste.indices.filter { raenge[it] < 0 }.map { liste[it] }
    }

    private val TOP_ORDNER = setOf("top 10 filme", "top 10 serien")
    private const val NEU_ORDNER = "neu diese woche"
    private const val ANGESAGT_ORDNER = "angesagt"
}
