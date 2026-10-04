package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.player.ExoVodStartWatchdog.Verdict

/** German4K 3.1: ExoPlayer als erster Film-Player (Holger714, Steiner646) — hängt er ohne Fehler, übernimmt mpv. */
class ExoVodStartWatchdogTest {
    private fun d(primary: Boolean = true, active: Boolean = true, paused: Boolean = false, error: Boolean = false, prev: Long = 0, pos: Long = 0, window: Long = 0) =
        ExoVodStartWatchdog.decide(primary, active, paused, error, prev, pos, window)

    @Test fun `not exo primary - not ours`() = assertEquals(Verdict.STAND_DOWN, d(primary = false))
    @Test fun `error shown - not ours`() = assertEquals(Verdict.STAND_DOWN, d(error = true))
    @Test fun `exo not started yet - window restarts`() = assertEquals(Verdict.RESTART_WINDOW, d(active = false))
    @Test fun `progress - done`() = assertEquals(Verdict.STAND_DOWN, d(prev = 1_000, pos = 2_000))
    @Test fun `resume seek - window restarts`() = assertEquals(Verdict.RESTART_WINDOW, d(prev = 0, pos = 710_000))
    @Test fun `paused - window restarts`() = assertEquals(Verdict.RESTART_WINDOW, d(paused = true, window = 60_000))
    @Test fun `still waiting`() = assertEquals(Verdict.WAIT, d(window = 19_000))
    @Test fun `20 s without progress - hand over`() = assertEquals(Verdict.FIRE, d(window = 20_000))
}
