package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** German4K 3.2.1 (D3): „🆕 Neu diese Woche“ steht oben bei den Trend-Ordnern. */
class German4kNeuOrdnerTest {
    @Test fun `erkennt den Server-Ordner mit und ohne Emoji`() {
        assertTrue(German4kKategorie.istNeuOrdner("🆕 Neu diese Woche"))
        assertTrue(German4kKategorie.istNeuOrdner("Neu  diese Woche"))
        assertFalse(German4kKategorie.istNeuOrdner("▶️ Neu"))
        assertTrue(German4kKategorie.istAngesagtOrdner("🔥 Angesagt"))
    }

    @Test fun `Neu kommt direkt hinter Angesagt`() {
        val liste = listOf("🏆 Top 10 Filme", "🔥 Angesagt", "DE Action", "DE Drama", "🆕 Neu diese Woche")
        assertEquals(
            listOf("🏆 Top 10 Filme", "🔥 Angesagt", "🆕 Neu diese Woche", "DE Action", "DE Drama"),
            German4kKategorie.neuNachVorn(liste) { it },
        )
    }

    @Test fun `ohne Angesagt ganz nach vorn, ohne Neu unveraendert`() {
        assertEquals(listOf("🆕 Neu diese Woche", "A", "B"), German4kKategorie.neuNachVorn(listOf("A", "B", "🆕 Neu diese Woche")) { it })
        val ohne = listOf("A", "B")
        assertEquals(ohne, German4kKategorie.neuNachVorn(ohne) { it })
    }
}
