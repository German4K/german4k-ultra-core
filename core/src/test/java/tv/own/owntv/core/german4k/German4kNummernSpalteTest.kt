package tv.own.owntv.core.german4k

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** German4K 3.2.1 (U53): keine leere Nummernspalte bei Listen ohne Sendernummern. */
class German4kNummernSpalteTest {
    @Test fun `ohne Nummern keine Spalte`() {
        assertFalse(German4kNummernSpalte.zeigen(true, listOf(null, null), an = true))
        assertFalse(German4kNummernSpalte.zeigen(true, emptyList(), an = true))
    }

    @Test fun `eine Nummer reicht, gemischte Liste bleibt buendig`() {
        assertTrue(German4kNummernSpalte.zeigen(true, listOf(null, 7), an = true))
    }

    @Test fun `Einstellung aus bleibt aus, Schalter aus = alte Spalte`() {
        assertFalse(German4kNummernSpalte.zeigen(false, listOf(1, 2), an = true))
        assertTrue(German4kNummernSpalte.zeigen(true, listOf(null), an = false))
    }
}
