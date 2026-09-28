package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.player.VodOpenWatchdog.Verdict
import tv.own.owntv.player.VodOpenWatchdog.VOD_OPEN_TIMEOUT_MS

/** German4K: VOD-Start-Wächter (JJ, Philips, MKV endloser Spinner in mpv). */
class VodOpenWatchdogTest {

    private fun decide(
        fileLoaded: Boolean = true,
        paused: Boolean = false,
        errorShown: Boolean = false,
        exoActive: Boolean = false,
        prev: Long = 0L,
        pos: Long = 0L,
        window: Long = 0L,
    ) = VodOpenWatchdog.decide(fileLoaded, paused, errorShown, exoActive, prev, pos, window)

    @Test
    fun `loaded but position never moves fires after the timeout`() {
        assertEquals(Verdict.WAIT, decide(window = VOD_OPEN_TIMEOUT_MS - 1))
        assertEquals(Verdict.FIRE, decide(window = VOD_OPEN_TIMEOUT_MS))
        // Stale position (mpv never reported time-pos) is the same hang.
        assertEquals(Verdict.FIRE, decide(prev = 1_234_000, pos = 1_234_000, window = VOD_OPEN_TIMEOUT_MS))
    }

    @Test
    fun `playback progress stands the watchdog down`() {
        assertEquals(Verdict.STAND_DOWN, decide(prev = 0, pos = 1_000, window = VOD_OPEN_TIMEOUT_MS))
        assertEquals(Verdict.STAND_DOWN, decide(prev = 60_000, pos = 62_000, window = VOD_OPEN_TIMEOUT_MS))
    }

    @Test
    fun `a seek or resume jump restarts the window instead of counting as progress`() {
        assertEquals(Verdict.RESTART_WINDOW, decide(prev = 0, pos = 1_800_000, window = VOD_OPEN_TIMEOUT_MS))
        assertEquals(Verdict.RESTART_WINDOW, decide(prev = 1_800_000, pos = 600_000, window = VOD_OPEN_TIMEOUT_MS))
    }

    @Test
    fun `user pause never fires`() {
        assertEquals(Verdict.RESTART_WINDOW, decide(paused = true, window = VOD_OPEN_TIMEOUT_MS * 5))
    }

    @Test
    fun `before FILE_LOADED the existing open ladder owns the item`() {
        assertEquals(Verdict.RESTART_WINDOW, decide(fileLoaded = false, window = VOD_OPEN_TIMEOUT_MS * 5))
    }

    @Test
    fun `error on screen or ExoPlayer in charge stands down`() {
        assertEquals(Verdict.STAND_DOWN, decide(errorShown = true, window = VOD_OPEN_TIMEOUT_MS))
        assertEquals(Verdict.STAND_DOWN, decide(exoActive = true, window = VOD_OPEN_TIMEOUT_MS))
    }

    @Test
    fun `first tick without a previous position only waits`() {
        assertEquals(Verdict.WAIT, decide(prev = -1, pos = 5_000, window = 1_000))
    }
}
