package tv.own.owntv.player

import androidx.media3.common.Format
import androidx.media3.exoplayer.ExoPlayer

/** Live measured bitrate once available, else the declared value, else 0. */
/**
 * German4K 3.2.1 (q-3, E): Bitrate-Zeile oder null, wenn die Bitrate unbekannt ist — nie „0 Mbit/s" für einen
 * laufenden Strom. Gemessen (10-s-Fenster) vor erklärter [Format.bitrate]; Schalter aus = [bitrateRow].
 */
fun bitrateRowOderNull(f: Format, throughputTracker: ThroughputTracker): StreamInfoRow? {
    if (!BitrateFenster.aktiv()) return bitrateRow(f, throughputTracker)
    val bps = throughputTracker.fensterBps?.takeIf { it > 0 } ?: f.bitrate.takeIf { it > 0 }?.toLong() ?: return null
    return StreamInfoRow(StreamInfoLabel.BITRATE, StreamInfoValue.Bitrate(bps))
}

fun bitrateRow(f: Format, throughputTracker: ThroughputTracker): StreamInfoRow {
    val bps = if (throughputTracker.hasMeasured) throughputTracker.bitsPerSecond else f.bitrate.takeIf { it > 0 }?.toLong() ?: 0L
    return StreamInfoRow(StreamInfoLabel.BITRATE, StreamInfoValue.Bitrate(bps))
}

/** Buffered duration + dropped frames since [dropsBaseline]. We can't reset ExoPlayer's own drop
 *  counter, so callers snapshot it per item and we subtract instead. */
fun bufferRow(p: ExoPlayer, dropsBaseline: Int): StreamInfoRow? {
    val drops = p.videoDecoderCounters?.let { it.ensureUpdated(); (it.droppedBufferCount - dropsBaseline).coerceAtLeast(0).toLong() }
    val buffered = p.totalBufferedDuration.takeIf { it > 0 }
    return if (buffered != null || drops != null) {
        StreamInfoRow(StreamInfoLabel.BUFFER, StreamInfoValue.Buffer(bufferedMs = buffered, droppedFrames = drops))
    } else null
}

/** Snapshot of the current drop count, for [bufferRow]'s baseline. */
fun currentDroppedFrames(p: ExoPlayer?): Int {
    val counters = p?.videoDecoderCounters ?: return 0
    counters.ensureUpdated()
    return counters.droppedBufferCount
}

/** German4K 3.2.1 (t-13): bisher gerenderte Video-Frames des aktuellen Decoders; null = kein Video-Decoder. */
fun renderedVideoFrames(p: ExoPlayer?): Int? {
    val counters = p?.videoDecoderCounters ?: return null
    counters.ensureUpdated()
    return counters.renderedOutputBufferCount
}
