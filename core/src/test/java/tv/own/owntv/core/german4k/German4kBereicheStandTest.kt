package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** German4K: `bereiche_stand` aus der Panel-Antwort, inklusive Cache-Rundreise. */
class German4kBereicheStandTest {
    @Test
    fun `parses and round-trips the stand`() {
        val a = German4kPanelAnswer.parse("""{"sources":[],"bereiche_stand":"2026-09-28T10:15:00Z"}""")
        assertEquals("2026-09-28T10:15:00Z", a.bereicheStand)
        assertEquals("2026-09-28T10:15:00Z", German4kPanelAnswer.parse(a.toJson()).bereicheStand)
    }

    @Test
    fun `null, blank and missing are null`() {
        assertNull(German4kPanelAnswer.parse("""{"bereiche_stand":null}""").bereicheStand)
        assertNull(German4kPanelAnswer.parse("""{"bereiche_stand":""}""").bereicheStand)
        assertNull(German4kPanelAnswer.parse("""{}""").bereicheStand)
        val leer = German4kPanelAnswer.parse("""{}""")
        assertNull(German4kPanelAnswer.parse(leer.toJson()).bereicheStand)
    }
}
