package tv.own.owntv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** German4K 3.1.1: Neuladen nach langer Pause bzw. Pufferhänger nach dem Fortsetzen. */
class PauseResumeTest {
    @Test fun `long vod pause reloads`() = assertTrue(PauseResume.reloadOnResume(61_000, isLive = false, isArchive = false, positionMs = 5_000))
    @Test fun `short pause does not reload`() = assertFalse(PauseResume.reloadOnResume(30_000, isLive = false, isArchive = false, positionMs = 5_000))
    @Test fun `exactly sixty seconds does not reload`() = assertFalse(PauseResume.reloadOnResume(60_000, isLive = false, isArchive = false, positionMs = 5_000))
    @Test fun `live never reloads`() = assertFalse(PauseResume.reloadOnResume(600_000, isLive = true, isArchive = false, positionMs = 5_000))
    @Test fun `archive never reloads`() = assertFalse(PauseResume.reloadOnResume(600_000, isLive = false, isArchive = true, positionMs = 5_000))
    @Test fun `position zero never reloads`() = assertFalse(PauseResume.reloadOnResume(600_000, isLive = false, isArchive = false, positionMs = 0))
    @Test fun `unknown pause does not reload`() = assertFalse(PauseResume.reloadOnResume(0, isLive = false, isArchive = false, positionMs = 5_000))

    @Test fun `stall after ten seconds without movement`() = assertTrue(PauseResume.stalled(nowMs = 20_000, stallSinceMs = 10_000, stallPosMs = 5_000, positionMs = 5_000))
    @Test fun `no stall before ten seconds`() = assertFalse(PauseResume.stalled(nowMs = 19_000, stallSinceMs = 10_000, stallPosMs = 5_000, positionMs = 5_000))
    @Test fun `moving position is no stall`() = assertFalse(PauseResume.stalled(nowMs = 30_000, stallSinceMs = 10_000, stallPosMs = 5_000, positionMs = 6_000))
    @Test fun `not buffering is no stall`() = assertFalse(PauseResume.stalled(nowMs = 30_000, stallSinceMs = 0, stallPosMs = 5_000, positionMs = 5_000))
}
