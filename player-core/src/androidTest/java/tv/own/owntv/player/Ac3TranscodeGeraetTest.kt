package tv.own.owntv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * German4K 3.2.1 (T7): AAC-Mehrkanal → AC3 auf einem echten Gerät/Emulator, durch ExoPlayer.
 *
 * Der Emulator hat kein HDMI mit Dolby. Darum ein Aufnahme-Sink: er meldet „AC3 als Bitstream geht“,
 * schreibt jeden AC3-Frame, den der Renderer liefert, mit (Datei unter filesDir/ac3-test/ zum Prüfen mit
 * ffprobe) und spielt für die Uhr gleich lange Stille als PCM ab. So läuft der echte Renderer-/Sink-Weg
 * samt Zeitstempeln, nur der letzte Meter (HDMI) fehlt.
 */
@UnstableApi
@RunWith(AndroidJUnit4::class)
class Ac3TranscodeGeraetTest {

    private val ctx: Context = InstrumentationRegistry.getInstrumentation().context

    @Test fun aac51_mp4_wird_ac3_51() {
        val r = spiele("aac51_48k.mp4")
        assertEquals(FfmpegAc3TranscodeRenderer.NAME, r.renderer)
        pruefeAc3(r, kanaele = 6, dauerUs = 4_000_000)
    }

    /** Live-Weg: MPEG-TS mit ADTS, 44,1 kHz → Resampler auf 48 kHz. */
    @Test fun aac51_ts_44k_wird_ac3_51_48k() {
        val r = spiele("aac51_44k.ts")
        assertEquals(FfmpegAc3TranscodeRenderer.NAME, r.renderer)
        pruefeAc3(r, kanaele = 6, dauerUs = 4_000_000)
    }

    /** 7.1 (AAC-Kanalkonfiguration 12) → AC3 5.1 (Dolby Digital kann höchstens 5.1). */
    @Test fun aac71_mp4_wird_ac3_51() {
        val r = spiele("aac71_48k.mp4")
        assertEquals(FfmpegAc3TranscodeRenderer.NAME, r.renderer)
        pruefeAc3(r, kanaele = 6, dauerUs = 4_000_000)
    }

    @Test fun stereo_bleibt_beim_geraetedecoder() {
        val r = spiele("aac20_48k.mp4")
        assertNotEquals(FfmpegAc3TranscodeRenderer.NAME, r.renderer)
        assertEquals(0, r.frames.size)
    }

    @Test fun ohne_freigabe_bleibt_beim_geraetedecoder() {
        val r = spiele("aac51_48k.mp4", darf = false)
        assertNotEquals(FfmpegAc3TranscodeRenderer.NAME, r.renderer)
        assertEquals(0, r.frames.size)
    }

    /**
     * German4K 3.2.1 (T7, Review Lippensync): das erste AC3-Frame liegt dort, wo der PCM-Weg (Gerätedecoder)
     * das erste Sample hinlegt — nicht um den gekürzten Encoder-Delay der Quelle später. Das AC3-Encoder-Delay
     * (256 Samples ≈ 5,3 ms) legt das erste Frame VOR den Start; der Decoder verwirft es (outputStartTimeUs),
     * das erste aufgenommene liegt dann ein Frame (32 ms) später. Darum: Abstand + 5,3 ms ist ein Vielfaches
     * von 32 ms (±1 ms). Mit dem alten Versatz (AAC-Delay 1024 Samples) lag der Rest bei ~21 ms.
     */
    @Test fun ac3_zeitstempel_wie_pcm_weg() {
        for (asset in listOf("aac51_48k.mp4", "aac51_44k.ts")) {
            val pcm = spiele(asset, darf = false).ersterPcmUs!!
            val ac3 = spiele(asset).frames.first().first
            android.util.Log.i("Ac3TranscodeTest", "$asset: erstes AC3 $ac3 us, erstes PCM $pcm us, Abstand ${ac3 - pcm} us")
            val rest = Math.floorMod(ac3 - pcm + 5_333L, 32_000L)
            assertTrue("$asset: AC3 ${ac3 - pcm} us neben PCM (Rest $rest)", ac3 - pcm >= -6_000 && (rest <= 1_000 || rest >= 31_000))
        }
    }

    private class Ergebnis(val renderer: String?, val frames: List<Pair<Long, ByteArray>>, val format: Format?, val ersterPcmUs: Long?)

    private fun pruefeAc3(r: Ergebnis, kanaele: Int, dauerUs: Long) {
        val f = r.format!!
        assertEquals(MimeTypes.AUDIO_AC3, f.sampleMimeType)
        assertEquals(kanaele, f.channelCount)
        assertEquals(48_000, f.sampleRate)
        assertTrue("zu wenige Frames: ${r.frames.size}", r.frames.size > 100)
        var letzte = Long.MIN_VALUE
        for ((ts, b) in r.frames) {
            // AC3-Sync 0x0B77, fscod 00 = 48 kHz, frmsizecod 0x24/0x25 = 640 kbit/s (2560 Byte)
            assertEquals(0x0B, b[0].toInt() and 0xFF)
            assertEquals(0x77, b[1].toInt() and 0xFF)
            assertEquals(0, (b[4].toInt() and 0xC0) shr 6)
            assertEquals(2560, b.size)
            // acmod (3 Bit nach bsid/bsmod): 7 = 3/2; lfeon gesetzt bei 5.1
            val acmod = (b[6].toInt() and 0xE0) shr 5
            assertEquals(7, acmod)
            if (letzte != Long.MIN_VALUE) assertEquals("Abstand ${ts - letzte}", 32_000.0, (ts - letzte).toDouble(), 2.0)
            letzte = ts
        }
        val gesamtUs = r.frames.size * 32_000L
        assertTrue("Dauer $gesamtUs", kotlin.math.abs(gesamtUs - dauerUs) < 200_000)
    }

    private fun spiele(asset: String, darf: Boolean = true): Ergebnis {
        val frames = mutableListOf<Pair<Long, ByteArray>>()
        var format: Format? = null
        var renderer: String? = null
        var ersterPcmUs: Long? = null
        val fertig = CountDownLatch(1)
        var fehler: Throwable? = null
        val main = Handler(Looper.getMainLooper())
        var player: ExoPlayer? = null

        main.post {
            val echt = DefaultAudioSink.Builder(ctx).build()
            val sink = AufnahmeSink(echt, frames, { format = it }) { if (ersterPcmUs == null) ersterPcmUs = it }
            val factory = RenderersFactory { handler, _, audioListener, _, _ ->
                arrayOf<Renderer>(
                    FfmpegAc3TranscodeRenderer(handler, audioListener, sink) { darf },
                    MediaCodecAudioRenderer(ctx, MediaCodecSelector.DEFAULT, handler, audioListener, sink),
                )
            }
            val p = ExoPlayer.Builder(ctx, factory).build()
            player = p
            p.addAnalyticsListener(object : AnalyticsListener {
                override fun onAudioDecoderInitialized(
                    eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long,
                ) {
                    renderer = if (decoderName == Ac3TranscodeDecoder.NAME) FfmpegAc3TranscodeRenderer.NAME else decoderName
                }
            })
            p.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) fertig.countDown()
                }
                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    fehler = error
                    fertig.countDown()
                }
            })
            p.setMediaItem(MediaItem.fromUri("asset:///$asset"))
            p.prepare()
            p.play()
        }
        val ok = fertig.await(40, TimeUnit.SECONDS)
        main.post { player?.release() }
        fehler?.let { throw AssertionError("Wiedergabefehler bei $asset", it) }
        assertTrue("$asset: kein Ende nach 40 s", ok)
        if (frames.isNotEmpty()) {
            val dir = File(ctx.filesDir, "ac3-test").apply { mkdirs() }
            File(dir, "$asset.ac3").outputStream().use { out -> frames.forEach { out.write(it.second) } }
        }
        return Ergebnis(renderer, frames.toList(), format, ersterPcmUs)
    }

    /** Sink, der AC3 annimmt, mitschreibt und für die Uhr Stille in den echten PCM-Sink gibt. */
    private class AufnahmeSink(
        private val echt: AudioSink,
        private val frames: MutableList<Pair<Long, ByteArray>>,
        private val formatGesehen: (Format) -> Unit,
        private val pcmGesehen: (Long) -> Unit = {},
    ) : ForwardingAudioSink(echt) {
        private var ac3 = false
        private var aktuell: ByteBuffer? = null
        private val stille: ByteBuffer = ByteBuffer.allocateDirect(1536 * 4).order(ByteOrder.LITTLE_ENDIAN)

        override fun supportsFormat(format: Format): Boolean =
            format.sampleMimeType == MimeTypes.AUDIO_AC3 || super.supportsFormat(format)

        override fun getFormatSupport(format: Format): Int =
            if (format.sampleMimeType == MimeTypes.AUDIO_AC3) AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY
            else super.getFormatSupport(format)

        override fun configure(audioSinkConfig: AudioSink.AudioSinkConfig) {
            val f = audioSinkConfig.format
            ac3 = f.sampleMimeType == MimeTypes.AUDIO_AC3
            if (!ac3) return super.configure(audioSinkConfig)
            formatGesehen(f)
            val pcm = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_RAW).setChannelCount(2)
                .setSampleRate(f.sampleRate).setPcmEncoding(C.ENCODING_PCM_16BIT).build()
            super.configure(AudioSink.AudioSinkConfig.Builder(pcm).build())
        }

        override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
            if (!ac3) {
                pcmGesehen(presentationTimeUs)
                return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
            }
            if (aktuell !== buffer) {
                aktuell = buffer
                val b = ByteArray(buffer.remaining())
                buffer.duplicate().get(b)
                frames.add(presentationTimeUs to b)
                stille.clear()
                while (stille.hasRemaining()) stille.put(0)
                stille.flip()
            }
            if (!super.handleBuffer(stille, presentationTimeUs, 1)) return false
            if (stille.hasRemaining()) return false
            buffer.position(buffer.limit())
            aktuell = null
            return true
        }

        override fun flush() {
            aktuell = null
            super.flush()
        }
    }
}
