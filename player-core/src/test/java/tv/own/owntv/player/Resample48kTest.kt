package tv.own.owntv.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

// German4K: Fire TV blieb bei 44,1 kHz stumm — der Stereo-Sink rechnet auf 48 kHz um, ohne andere Formate zu brechen.
class Resample48kTest {
    @Test fun rechnet441AufAchtundvierzig() {
        val p = resample48kProcessor()
        val out = p.configure(AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        assertEquals(48_000, out.sampleRate)
        p.flush()
        assertTrue(p.isActive)
        val inBuf = ByteBuffer.allocateDirect(44_100 * 4).order(ByteOrder.nativeOrder())
        while (inBuf.hasRemaining()) inBuf.putShort(1000)
        inBuf.flip()
        p.queueInput(inBuf)
        p.queueEndOfStream()
        var bytes = 0
        repeat(50) { bytes += p.output.remaining().also { _ -> p.output.position(p.output.limit()) } }
        assertTrue("Ausgabe ~1 s bei 48 kHz, war $bytes Byte", bytes in 180_000..200_000)
    }

    @Test fun achtundvierzigBleibtUnberuehrt() {
        val p = resample48kProcessor()
        assertEquals(AudioFormat.NOT_SET, p.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_16BIT)))
        assertFalse(p.isActive)
    }

    @Test fun floatWirftNicht() {
        val p = resample48kProcessor()
        assertEquals(AudioFormat.NOT_SET, p.configure(AudioFormat(44_100, 2, C.ENCODING_PCM_FLOAT)))
        assertFalse(p.isActive)
    }
}
