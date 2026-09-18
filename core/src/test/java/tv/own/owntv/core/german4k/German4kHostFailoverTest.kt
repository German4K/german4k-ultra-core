package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class German4kHostFailoverTest {

    private val primary = "https://german4k.tv"
    private val alt = "http://tv.german4k.tv"

    @Before
    fun setUp() {
        German4kHostFailover.clear()
        German4kHostFailover.register(listOf(listOf(primary, alt)))
    }

    @Test
    fun `urls of a foreign host pass through untouched`() {
        val u = "https://example.org/live/a/b/1.ts"
        assertEquals(u, German4kHostFailover.rewrite(u, now = 1000))
        assertFalse(German4kHostFailover.demote(u, "x", now = 1000))
        assertFalse(German4kHostFailover.knows(u))
    }

    @Test
    fun `primary host stays while healthy`() {
        val u = "$primary/live/user/pass/26.ts"
        assertEquals(u, German4kHostFailover.rewrite(u, now = 1000))
        assertEquals(listOf(primary, alt), German4kHostFailover.hostsFor(u, now = 1000))
    }

    @Test
    fun `a demoted primary sends the same url to the alternative, case-insensitively`() {
        val u = "HTTPS://German4K.tv/player_api.php?username=u&password=p"
        assertTrue(German4kHostFailover.demote(u, "timeout", now = 5000))
        assertEquals("$alt/player_api.php?username=u&password=p", German4kHostFailover.rewrite(u, now = 6000))
        // and an already-rewritten url stays on the alternative
        assertEquals("$alt/x", German4kHostFailover.rewrite("$alt/x", now = 6000))
    }

    @Test
    fun `after the cooldown the primary is preferred again`() {
        val u = "$primary/x"
        German4kHostFailover.demote(u, "timeout", now = 5000)
        assertEquals("$alt/x", German4kHostFailover.rewrite(u, now = 5000 + German4kHostFailover.COOLDOWN_MS - 1))
        assertEquals(u, German4kHostFailover.rewrite(u, now = 5000 + German4kHostFailover.COOLDOWN_MS + 1))
    }

    @Test
    fun `when every host failed the least recently failed one is used and demote reports no switch`() {
        German4kHostFailover.demote("$primary/x", "a", now = 1000)
        assertFalse(German4kHostFailover.demote("$alt/x", "b", now = 2000))
        assertEquals("$primary/x", German4kHostFailover.rewrite("$alt/x", now = 3000))
    }

    @Test
    fun `groups with a single host are ignored`() {
        German4kHostFailover.register(listOf(listOf("https://only.example")))
        assertFalse(German4kHostFailover.knows("https://only.example/a"))
    }

    @Test
    fun `host failure classifier`() {
        assertTrue(German4kHostFailover.looksLikeHostFailure("Read timed out"))
        assertTrue(German4kHostFailover.looksLikeHostFailure("unexpected end of stream on http://up4.german4k.tv/..."))
        assertTrue(German4kHostFailover.looksLikeHostFailure("HTTP 503 for https://german4k.tv/live"))
        assertTrue(German4kHostFailover.looksLikeHostFailure("Response code: 502"))
        assertTrue(German4kHostFailover.looksLikeHostFailure("'Das Erste' — gave up after 35s"))
        assertFalse(German4kHostFailover.looksLikeHostFailure("HTTP 403 for https://german4k.tv/live"))
        assertFalse(German4kHostFailover.looksLikeHostFailure("Response code: 458"))
        assertFalse(German4kHostFailover.looksLikeHostFailure("Decoder init failed"))
        assertFalse(German4kHostFailover.looksLikeHostFailure(null))
    }
}
