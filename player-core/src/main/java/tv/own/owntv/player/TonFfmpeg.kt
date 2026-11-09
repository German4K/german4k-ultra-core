package tv.own.owntv.player

import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import tv.own.owntv.core.german4k.German4kFeatures
import tv.own.owntv.core.german4k.German4kSchalter
import tv.own.owntv.core.player.TonWeg

/**
 * German4K 3.2.1 (T3): FFmpeg-Tondecoder für ExoPlayer (AC3, E-AC3, DTS, TrueHD/MLP), damit ein Gerät ohne
 * eigenen Dolby/DTS-Decoder nicht nur wegen des Tons zu mpv ausweichen muss.
 *
 * - `libffmpegJNI.so` (player-core/src/main/jniLibs) ist selbst gebaut: FFmpeg 7.1.1 (LGPL, nur diese
 *   Decoder + swresample) statisch in die JNI-Hülle aus Media3 1.11.1 gelinkt, FFmpeg-Symbole nicht
 *   exportiert (`--exclude-libs,ALL`) — keine zweite libavcodec.so neben der von libmpv. Bauskript:
 *   ~/german4k-app-rebrand/ultra/ffmpeg-321/bau.sh (nicht im Repo).
 * - Geladen wird die Bibliothek erst, wenn der Schalter `ton_ffmpeg` an ist UND ein Player gebaut wird;
 *   scheitert das Laden, läuft alles ohne Erweiterung weiter (wie 3.2). Einmal geladen bleibt sie bis zum
 *   Neustart im Prozess; Schalter aus wirkt ab dem nächsten Player-Bau (Renderer-Modus OFF).
 * - Modus: ON (Erweiterung nur, wenn MediaCodec das Format nicht kann); in der Stereo-Stufe PREFER
 *   (FFmpeg dekodiert Dolby/DTS selbst — kein Hersteller-Decoder, der Mehrkanal falsch ausgibt).
 */
@UnstableApi
object TonFfmpeg {
    @Volatile private var geladen: Boolean? = null

    /** Schalter an und Bibliothek ladbar. Lädt beim ersten Aufruf mit Schalter an. */
    fun verfuegbar(): Boolean {
        if (!German4kFeatures.an(German4kSchalter.TON_FFMPEG)) return false
        return ladbar()
    }

    /**
     * German4K 3.2.1 (T7): Bibliothek laden, ohne den Schalter `ton_ffmpeg` zu fragen — die AC3-Umwandlung
     * hat ihren eigenen Schalter (`ton_ac3`). Die Dolby/DTS-Decoder bleiben trotzdem aus, solange
     * `ton_ffmpeg` aus ist ([modus] fragt [verfuegbar]).
     */
    fun ladbar(): Boolean {
        geladen?.let { return it }
        val ok = try {
            System.loadLibrary("ffmpegJNI")
            androidx.media3.decoder.ffmpeg.FfmpegLibrary.isAvailable()
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.w(TAG, "libffmpegJNI not loadable, continuing without", e)
            false
        } catch (t: Throwable) {
            android.util.Log.w(TAG, "ffmpeg extension unavailable, continuing without", t)
            false
        }
        geladen = ok
        if (ok) android.util.Log.i(TAG, "ffmpeg audio decoder ${runCatching { androidx.media3.decoder.ffmpeg.FfmpegLibrary.getVersion() }.getOrNull()}")
        return ok
    }

    /** Renderer-Modus für [weg]; OFF, solange der Schalter aus ist oder die Bibliothek fehlt. */
    fun modus(weg: TonWeg): Int = when {
        !verfuegbar() -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
        weg == TonWeg.STEREO -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
        else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
    }

    private const val TAG = "TonFfmpeg"
}
