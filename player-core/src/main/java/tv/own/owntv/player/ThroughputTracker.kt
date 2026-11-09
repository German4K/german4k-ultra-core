package tv.own.owntv.player

import android.os.SystemClock
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener

/**
 * Live network throughput (bits/sec): bytes since [bitsPerSecond] was last read, divided by time since
 * then. Assumes a single regular poller (the debug overlay) — a second reader would steal bytes meant
 * for the first. Starts disabled — call [setEnabled] to activate.
 */
class ThroughputTracker : TransferListener {
    private var pendingBytes = 0L
    private var lastReadMs = 0L
    @Volatile private var enabled = false
    @Volatile private var everTransferred = false

    /** True once any byte has been transferred while enabled — distinguishes "never measured" from
     *  "measured, currently 0". */
    val hasMeasured: Boolean
        get() = everTransferred

    val bitsPerSecond: Long
        get() = readAndReset()

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}

    /** German4K 3.2.1 (t-9): wann zuletzt Netz-Bytes flossen (elapsedRealtime, 0 = nie) — unabhängig von [enabled]. */
    @Volatile var letzteBytesMs = 0L
        private set

    override fun onBytesTransferred(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean, bytesTransferred: Int) {
        if (isNetwork && bytesTransferred > 0) letzteBytesMs = SystemClock.elapsedRealtime() // German4K 3.2.1 (t-9)
        if (!enabled) return
        everTransferred = true
        synchronized(this) { pendingBytes += bytesTransferred }
        fenster.add(SystemClock.elapsedRealtime(), bytesTransferred) // German4K 3.2.1 (q-3, E)
    }

    @Synchronized
    private fun readAndReset(): Long {
        val now = SystemClock.elapsedRealtime()
        if (lastReadMs == 0L) {
            lastReadMs = now
            return 0L
        }
        val elapsedMs = now - lastReadMs
        val bps = if (elapsedMs > 0) pendingBytes * 8_000 / elapsedMs else 0L
        pendingBytes = 0L
        lastReadMs = now
        return bps
    }

    /** Enabling starts fresh rather than counting bytes from whenever tracking was last on. */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        if (enabled) reset()
    }

    @Synchronized
    fun reset() {
        pendingBytes = 0L
        lastReadMs = 0L
        everTransferred = false
        fenster.reset() // German4K 3.2.1 (q-3, E)
    }

    // German4K 3.2.1 (q-3, E): gleitendes 10-s-Fenster statt „seit der letzten Abfrage".
    private val fenster = BitrateFenster()

    /** Bits/s über die letzten 10 s; null = nichts gemessen (Messung aus oder noch kein Byte). */
    val fensterBps: Long? get() = fenster.bps(SystemClock.elapsedRealtime())
}
