package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Test

/** German4K 3.1: was nach dem natürlichen Ende eines Items passiert. */
class NaturalEndTest {
    private fun e(auto: Boolean = true, cancelled: Boolean = false, queue: Int = 0, index: Int = 0, archive: Boolean = false, chained: Boolean = false) =
        NaturalEnd.decide(auto, cancelled, queue, index, archive, chained)

    @Test fun `single movie stops`() = assertEquals(NaturalEnd.Action.STOP, e())
    @Test fun `archive without queue asks the live view model`() = assertEquals(NaturalEnd.Action.ARCHIVE_ENDED, e(archive = true))
    @Test fun `queue advances`() = assertEquals(NaturalEnd.Action.NEXT, e(queue = 3, index = 0))
    @Test fun `queue end reports`() = assertEquals(NaturalEnd.Action.QUEUE_ENDED, e(queue = 3, index = 2))
    @Test fun `autoplay off stops`() = assertEquals(NaturalEnd.Action.STOP, e(auto = false, queue = 3))
    @Test fun `chained item reports even with autoplay off`() = assertEquals(NaturalEnd.Action.CHAINED_ENDED, e(auto = false, chained = true))
    @Test fun `chained item ignores a cancelled next countdown`() = assertEquals(NaturalEnd.Action.CHAINED_ENDED, e(cancelled = true, chained = true))
}
