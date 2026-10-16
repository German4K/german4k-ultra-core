package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.german4k.German4kHealth

/** German4K 3.2 (F5/N6): „Leitung belegt“ erkennen — und nur das. */
class German4kLeitungTest {
    @Test
    fun `458 and 460 are a busy line, 403 404 500 are not`() {
        assertTrue(German4kLeitung.belegtStatus(458))
        assertTrue(German4kLeitung.belegtStatus(460))
        listOf(null, 200, 403, 404, 429, 500, 511).forEach { assertFalse("$it", German4kLeitung.belegtStatus(it)) }
    }

    @Test
    fun `mpv error lines and provider texts`() {
        assertTrue(German4kLeitung.belegtText("http: HTTP error 458"))
        assertTrue(German4kLeitung.belegtText("Failed to open http://x/movie/u/p/1.mkv: HTTP error 460 "))
        assertTrue(German4kLeitung.belegtText("response code: 458"))
        assertTrue(German4kLeitung.belegtText("Max connections reached for this line"))
        assertTrue(German4kLeitung.belegtText("Connection limit has been reached"))
        assertFalse(German4kLeitung.belegtText("HTTP error 403 Forbidden"))
        assertFalse(German4kLeitung.belegtText("mkv: Failed to seek when reading header element"))
        assertFalse(German4kLeitung.belegtText("hardware decoder produced no usable video"))
        assertFalse(German4kLeitung.belegtText(null))
        // 4580 ist keine 458.
        assertFalse(German4kLeitung.belegtText("HTTP error 4580"))
    }

    @Test
    fun `the reason we report is classified as a busy line by the overlay`() {
        assertEquals(German4kHealth.Klasse.LEITUNG_BELEGT, German4kHealth.klassifiziere(German4kLeitung.grund(458)))
        assertEquals(German4kHealth.Klasse.LEITUNG_BELEGT, German4kHealth.klassifiziere(German4kLeitung.grund(460)))
        assertEquals(German4kHealth.Klasse.LEITUNG_BELEGT, German4kHealth.klassifiziere(German4kLeitung.grund(null)))
    }
}
