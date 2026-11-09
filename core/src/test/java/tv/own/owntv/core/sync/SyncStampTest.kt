package tv.own.owntv.core.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/** German4K: lastSyncAt nach Phasenfehlern (SyncManager.syncStampFor). */
class SyncStampTest {
    private val now = 1_800_000_000_000L
    private val h12 = 12 * 3600_000L

    @Test
    fun `clean run stamps now`() {
        assertEquals(now, SyncManager.syncStampFor(now, hadPhaseErrors = false, thresholdMs = h12))
    }

    @Test
    fun `phase errors make the interval refresh due after the retry delay`() {
        val stamp = SyncManager.syncStampFor(now, hadPhaseErrors = true, thresholdMs = h12)
        // Not due yet right now (no immediate loop) …
        assert(now - stamp < h12)
        // … but due exactly after the retry delay.
        assertEquals(h12, now + SyncManager.PHASE_ERROR_RETRY_MS - stamp)
    }

    @Test
    fun `no interval keeps now`() {
        assertEquals(now, SyncManager.syncStampFor(now, hadPhaseErrors = true, thresholdMs = null))
    }

    @Test
    fun `stamp is never null-like or in the future`() {
        assertEquals(1L, SyncManager.syncStampFor(1_000L, hadPhaseErrors = true, thresholdMs = h12))
    }
}
