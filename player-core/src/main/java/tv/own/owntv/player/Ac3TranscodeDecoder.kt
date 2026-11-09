package tv.own.owntv.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.Decoder
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.DecoderOutputBuffer
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import tv.own.owntv.core.player.TonAc3
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** German4K 3.2.1 (T7): Fehler der AC3-Umwandlung (sperrt sie für den Rest des Prozesses, siehe [Ac3Transcode]). */
class Ac3TranscodeException(message: String) : DecoderException(message)

/**
 * German4K 3.2.1 (T7): AAC-Mehrkanal → AC3-Frames für den Bitstream-Weg des AudioSinks.
 *
 * Kein [androidx.media3.decoder.SimpleDecoder]: der kennt nur „ein Eingang → ein Ausgang“, AAC hat aber
 * 1024 (HE-AAC 2048) Samples je Paket, AC3 1536 je Frame. Der Sink zählt bei Bitstream je `handleBuffer`
 * genau EIN Access-Unit (DecoderAudioRenderer übergibt `encodedAccessUnitCount = 1`) — darum gibt dieser
 * Decoder je Ausgabepuffer genau einen AC3-Frame aus, null, einen oder mehrere je Eingang, mit eigenem
 * Zeitstempel. Läuft synchron im Wiedergabe-Faden (Kodieren eines Frames ≈ 1 ms); solange Ausgaben
 * warten, gibt es keinen neuen Eingangspuffer.
 *
 * Zeitstempel: Basis = erster Eingang nach Start/Flush, dazu die pts des Encoders (1/48000 s, inkl.
 * Encoder-Delay). Weicht ein Eingang um mehr als [SPRUNG_US] vom Erwarteten ab (Live-Sprung), wird neu
 * aufgesetzt — der Sink gleicht den Sprung selbst aus.
 */
@UnstableApi
class Ac3TranscodeDecoder(format: Format) :
    Decoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, Ac3TranscodeException> {

    private val handle: Long
    private val quelleKanaele = format.channelCount

    /** Kanäle des AC3 (höchstens 6). */
    val ausgangKanaele: Int

    private val input = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT).apply {
        ensureSpaceForWrite(if (format.maxInputSize > 0) format.maxInputSize else EINGANG_START)
    }
    private var inputHeld = false
    private val wartend = ArrayDeque<SimpleDecoderOutputBuffer>()
    private val frei = ArrayDeque<SimpleDecoderOutputBuffer>()
    private val owner = DecoderOutputBuffer.Owner<SimpleDecoderOutputBuffer> { ob ->
        ob.clear()
        frei.addLast(ob)
    }

    private val outBuf: ByteBuffer = ByteBuffer.allocateDirect(AUSGANG_BYTES).order(ByteOrder.nativeOrder())
    private val sizes = IntArray(MAX_FRAMES)
    private val pts = LongArray(MAX_FRAMES)
    private val stats = LongArray(2)

    private var baseUs = C.TIME_UNSET
    private var outputStartTimeUs = C.TIME_UNSET
    private var ersteMarke = false
    private var uebersprungen = 0
    private var kaputtInFolge = 0
    private var endeGemeldet = false
    @Volatile private var released = false
    private var fehler: Ac3TranscodeException? = null

    init {
        val extra = format.initializationData.firstOrNull()
        val trim = if (format.encoderDelay > 0) format.encoderDelay else 0
        val h = Ac3Jni.nInit(extra, quelleKanaele, TonAc3.BITRATE, trim)
        if (h == 0L) {
            Ac3Transcode.sperren("init failed (${format.channelCount} ch ${format.sampleRate} Hz)")
            throw Ac3TranscodeException("ac3 transcoder init failed")
        }
        handle = h
        ausgangKanaele = Ac3Jni.nOutChannels(h)
    }

    override fun getName(): String = NAME

    override fun setOutputStartTimeUs(outputStartTimeUs: Long) {
        this.outputStartTimeUs = outputStartTimeUs
    }

    override fun dequeueInputBuffer(): DecoderInputBuffer? {
        fehler?.let { throw it }
        if (inputHeld || endeGemeldet || wartend.size >= MAX_WARTEND) return null
        input.clear()
        inputHeld = true
        return input
    }

    override fun queueInputBuffer(inputBuffer: DecoderInputBuffer) {
        fehler?.let { throw it }
        inputHeld = false
        if (inputBuffer.isEndOfStream) {
            umwandeln(null, 0, C.TIME_UNSET)
            endeGemeldet = true
            wartend.addLast(neuerPuffer().apply { addFlag(C.BUFFER_FLAG_END_OF_STREAM) })
            return
        }
        if (inputBuffer.isFirstSample) ersteMarke = true
        val data = inputBuffer.data ?: return
        val size = data.limit() // nach flip(): Position 0, limit = Länge
        if (size <= 0) return
        umwandeln(data, size, inputBuffer.timeUs)
    }

    private fun umwandeln(data: ByteBuffer?, size: Int, timeUs: Long) {
        if (data != null) {
            if (baseUs == C.TIME_UNSET) {
                baseUs = timeUs
            } else if (stats[1] > 0) {
                val erwartet = baseUs + stats[0] * 1_000_000L / stats[1]
                if (kotlin.math.abs(timeUs - erwartet) > SPRUNG_US) {
                    android.util.Log.i(TAG, "timestamp jump ${(timeUs - erwartet) / 1000} ms — resetting")
                    if (Ac3Jni.nReset(handle) != 0) fail("reset failed")
                    baseUs = timeUs
                    stats[0] = 0
                }
            }
        }
        outBuf.clear()
        val n = Ac3Jni.nTranscode(handle, data, size, outBuf, sizes, pts, stats)
        if (n == Ac3Jni.SKIP) {
            if (++kaputtInFolge >= MAX_KAPUTT) fail("$kaputtInFolge broken AAC packets in a row")
            return
        }
        if (n < 0) fail("native error $n")
        kaputtInFolge = 0
        if (baseUs == C.TIME_UNSET) return // Ende ohne einen einzigen Eingang
        var offset = 0
        for (i in 0 until n) {
            val len = sizes[i]
            val frameUs = baseUs + pts[i] * 1_000_000L / TonAc3.RATE
            if (outputStartTimeUs != C.TIME_UNSET && frameUs < outputStartTimeUs) {
                uebersprungen++
                offset += len
                continue
            }
            val ob = neuerPuffer()
            val ziel = ob.init(frameUs, len)
            val quelle = outBuf.duplicate()
            quelle.position(offset)
            quelle.limit(offset + len)
            ziel.put(quelle)
            ziel.flip()
            offset += len
            if (uebersprungen > 0) {
                ob.skippedOutputBufferCount = uebersprungen
                uebersprungen = 0
            }
            if (ersteMarke) {
                ob.addFlag(C.BUFFER_FLAG_FIRST_SAMPLE)
                ersteMarke = false
            }
            wartend.addLast(ob)
        }
    }

    private fun neuerPuffer(): SimpleDecoderOutputBuffer = frei.removeFirstOrNull() ?: SimpleDecoderOutputBuffer(owner)

    private fun fail(grund: String): Nothing {
        Ac3Transcode.sperren(grund)
        val e = Ac3TranscodeException("ac3 transcode: $grund")
        fehler = e
        throw e
    }

    override fun dequeueOutputBuffer(): SimpleDecoderOutputBuffer? {
        fehler?.let { throw it }
        return wartend.removeFirstOrNull()
    }

    override fun flush() {
        while (true) {
            val ob = wartend.removeFirstOrNull() ?: break
            ob.release()
        }
        inputHeld = false
        endeGemeldet = false
        baseUs = C.TIME_UNSET
        ersteMarke = false
        uebersprungen = 0
        kaputtInFolge = 0
        stats[0] = 0
        stats[1] = 0
        if (!released && Ac3Jni.nReset(handle) != 0) {
            Ac3Transcode.sperren("reset failed")
            fehler = Ac3TranscodeException("ac3 transcode: reset failed")
        }
    }

    override fun release() {
        if (released) return
        released = true
        wartend.clear()
        Ac3Jni.nRelease(handle)
    }

    companion object {
        const val NAME = "g4k-ac3-transcode"
        private const val TAG = "Ac3Transcode"
        private const val EINGANG_START = 8 * 1024
        /** 32 Frames à höchstens 3840 Byte (640 kbit/s bei 32 kHz) — je Eingang kommen höchstens 2–3. */
        private const val MAX_FRAMES = 32
        private const val AUSGANG_BYTES = MAX_FRAMES * 3840
        private const val MAX_WARTEND = 2
        private const val MAX_KAPUTT = 50
        private const val SPRUNG_US = 200_000L
    }
}
