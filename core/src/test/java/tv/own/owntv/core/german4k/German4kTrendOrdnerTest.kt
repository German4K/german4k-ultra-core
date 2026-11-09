package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Test

/** German4K 3.2.1 (U6): Trend-Ordner stehen auch bei A–Z oben (Serien-Abnahme abn-tv/87). */
class German4kTrendOrdnerTest {

    @Test
    fun alphabetischeListeBekommtTrendOrdnerNachVorn() {
        // So sortiert die Ultra bei A–Z: Emoji-Namen nach den Buchstaben, zwischen den Flaggen.
        val az = listOf(
            "Amazon Prime", "Apple TV+", "Netflix", "Sky", "⚽ FIFA+ Serien",
            "🆕 Neu diese Woche", "🇩🇪 Amazon Prime", "🏆 Top 10 Serien", "🔥 Angesagt",
        )
        assertEquals(
            listOf(
                "🏆 Top 10 Serien", "🔥 Angesagt", "🆕 Neu diese Woche",
                "Amazon Prime", "Apple TV+", "Netflix", "Sky", "⚽ FIFA+ Serien", "🇩🇪 Amazon Prime",
            ),
            German4kKategorie.trendNachVorn(az) { it },
        )
    }

    @Test
    fun ohneTrendOrdnerBleibtAllesGleich() {
        val l = listOf("Netflix", "Sky", "Top 10 Netflix")
        assertEquals(l, German4kKategorie.trendNachVorn(l) { it })
    }

    @Test
    fun filmeReihenfolgeBleibt() {
        val l = listOf("🏆 Top 10 Filme", "🔥 Angesagt", "🆕 Neu diese Woche", "Action")
        assertEquals(l, German4kKategorie.trendNachVorn(l) { it })
    }
}
