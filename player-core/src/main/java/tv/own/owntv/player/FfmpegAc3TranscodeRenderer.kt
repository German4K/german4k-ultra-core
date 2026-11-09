package tv.own.owntv.player

import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.CryptoConfig
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import tv.own.owntv.core.german4k.German4kFeatures
import tv.own.owntv.core.german4k.German4kSchalter
import tv.own.owntv.core.player.TonAc3
import tv.own.owntv.core.player.TonAusgang
import tv.own.owntv.core.player.TonWeg

/**
 * German4K 3.2.1 (T7): AAC-Mehrkanal (5.1/7.1) → AC3 → HDMI-Bitstream. Für Fire TV, das kein
 * Mehrkanal-PCM ausgibt, Dolby aber durchreicht (Kunde 20.10.).
 *
 * Steht in der Renderer-Liste VOR dem MediaCodec-AAC-Renderer ([OwnTVRenderersFactory]); die Spurwahl
 * nimmt bei Gleichstand den ersten. Meldet er „kann ich nicht“, läuft alles wie bisher (Gerätedecoder,
 * PCM/Stereo). Er sagt nur „kann ich“, wenn [TonAc3.aktiv] gilt, der Sink AC3 als Bitstream annimmt und
 * der native Teil sich öffnen lässt — so endet ein Problem nie in Stille, sondern im alten Weg.
 * Ein Fehler zur Laufzeit sperrt die Umwandlung für den Prozess ([Ac3Transcode.sperren]); der nächste
 * Prepare (Reconnect, Software-Rettung, Neuaufbau) wählt dann den normalen Renderer.
 *
 * Nur ExoPlayer — mpv (libmpv ohne Encoder) bleibt wie es ist.
 */
@UnstableApi
class FfmpegAc3TranscodeRenderer(
    eventHandler: Handler?,
    eventListener: AudioRendererEventListener?,
    audioSink: AudioSink,
    /** Darf diese Spur umgewandelt werden (Schalter, Weg, Ausgang)? Für Tests austauschbar. */
    private val darf: (Format) -> Boolean,
) : DecoderAudioRenderer<Ac3TranscodeDecoder>(eventHandler, eventListener, audioSink) {

    override fun getName(): String = NAME

    override fun supportsFormatInternal(format: Format): Int {
        if (format.sampleMimeType != MimeTypes.AUDIO_AAC || format.channelCount <= 2) return C.FORMAT_UNSUPPORTED_SUBTYPE
        if (Ac3Transcode.gesperrt) return C.FORMAT_UNSUPPORTED_SUBTYPE
        val ok = runCatching {
            darf(format) &&
                sinkSupportsFormat(ac3Format(TonAc3.ac3Kanaele(format.channelCount))) &&
                Ac3Transcode.probe(format.channelCount)
        }.getOrElse {
            android.util.Log.w(TAG, "supportsFormat failed", it)
            false
        }
        if (!ok) return C.FORMAT_UNSUPPORTED_SUBTYPE
        if (format.cryptoType != C.CRYPTO_TYPE_NONE) return C.FORMAT_UNSUPPORTED_DRM
        return C.FORMAT_HANDLED
    }

    override fun createDecoder(format: Format, cryptoConfig: CryptoConfig?): Ac3TranscodeDecoder {
        val d = Ac3TranscodeDecoder(format)
        val text = TonAc3.umwandlung(format.channelCount, d.ausgangKanaele)
        TonAusgang.letzteUmwandlung.value = text
        android.util.Log.i(TAG, "transcoding $text (${format.sampleRate} Hz, ${format.codecs})")
        return d
    }

    override fun getOutputFormat(decoder: Ac3TranscodeDecoder): Format = ac3Format(decoder.ausgangKanaele)

    companion object {
        const val NAME = "G4kAc3TranscodeRenderer"
        private const val TAG = "Ac3Transcode"

        fun ac3Format(kanaele: Int): Format = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AC3)
            .setChannelCount(kanaele)
            .setSampleRate(TonAc3.RATE)
            .setAverageBitrate(TonAc3.BITRATE)
            .build()
    }
}

/** German4K 3.2.1 (T7): Prozesszustand der AC3-Umwandlung (Sperre nach Fehler, Probe-Ergebnis). */
@UnstableApi
object Ac3Transcode {
    @Volatile var gesperrt = false
        private set
    @Volatile var sperrGrund: String? = null
        private set
    private val probeErgebnis = java.util.concurrent.ConcurrentHashMap<Int, Boolean>()

    /** Kommt der Renderer überhaupt in die Liste? Billig (kein Laden der Bibliothek). */
    fun kandidat(weg: TonWeg): Boolean =
        weg.durchreichen && !gesperrt && German4kFeatures.an(German4kSchalter.TON_AC3)

    /** Volle Prüfung für eine Spur, mit dem Weg, mit dem der Player gebaut ist. */
    fun darf(weg: TonWeg, format: Format): Boolean =
        !AudioOutputPolicy.stereoLatched &&
            TonAc3.aktiv(
                German4kFeatures.an(German4kSchalter.TON_AC3), weg, TonAusgang.aktuell.value,
                format.sampleMimeType, format.channelCount,
            ) &&
            TonFfmpeg.ladbar()

    /** Lässt sich der native Teil für [kanaele] öffnen? Einmal je Kanalzahl gemessen. */
    fun probe(kanaele: Int): Boolean = probeErgebnis.getOrPut(kanaele) {
        if (!TonFfmpeg.ladbar()) return@getOrPut false
        runCatching {
            val h = Ac3Jni.nInit(null, kanaele, TonAc3.BITRATE, 0)
            if (h != 0L) Ac3Jni.nRelease(h)
            h != 0L
        }.getOrElse {
            android.util.Log.w("Ac3Transcode", "probe failed", it)
            false
        }
    }

    /** Nach einem Fehler: bis zum Neustart der App nie wieder umwandeln. */
    fun sperren(grund: String) {
        if (gesperrt) return
        gesperrt = true
        sperrGrund = grund
        TonAusgang.letzteUmwandlung.value = null
        android.util.Log.w("Ac3Transcode", "disabled for this session: $grund")
    }
}
