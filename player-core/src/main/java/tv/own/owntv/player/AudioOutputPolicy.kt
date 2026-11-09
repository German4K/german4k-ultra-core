package tv.own.owntv.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import kotlinx.coroutines.flow.distinctUntilChanged
import tv.own.owntv.core.german4k.German4kFeatures
import tv.own.owntv.core.german4k.German4kSchalter
import tv.own.owntv.core.player.SurroundMode
import tv.own.owntv.core.player.TonAusgang
import tv.own.owntv.core.player.TonDownmix
import tv.own.owntv.core.player.TonWahl
import tv.own.owntv.core.player.TonWeg

/**
 * Session-wide state for the audio-output safety net, shared by **every** engine (mpv, the Live
 * preview/fullscreen ExoPlayer, the VOD ExoPlayer).
 *
 * Why global and not per-engine: the fault being guarded against lives in the device's audio HAL or
 * in the HDMI/ARC sink, not in a stream. Once one engine has proved that this TV cannot actually
 * play what it claims to support, every other engine must inherit that lesson immediately —
 * otherwise the user zaps to the next channel, lands on the other engine, and loses sound again.
 *
 * In-memory only, deliberately. A latch is a statement about "this output, right now" (an HDMI
 * handshake, a soundbar that woke up in the wrong mode); persisting it would silently downgrade a
 * genuinely capable receiver forever. Changing the setting also clears it — that is the user asking
 * for a fresh attempt.
 */
object AudioOutputPolicy {

    /** Never advance audio for this long while playing with an audio track = the output is dead. */
    const val NO_AUDIO_GRACE_MS = 6_000L

    /** Underruns within [UNDERRUN_WINDOW_MS] that mean the sink is starving, not hiccuping. */
    const val UNDERRUN_LIMIT = 4
    const val UNDERRUN_WINDOW_MS = 10_000L

    @Volatile private var latched = false
    @Volatile var latchReason: String? = null
        private set

    /** True once the output has been caught failing; forces stereo PCM everywhere for this session. */
    val stereoLatched: Boolean get() = latched

    /**
     * True when this mode + the current latch permit anything other than plain stereo PCM.
     * [SurroundMode.STEREO] and a tripped latch are the same answer for different reasons.
     */
    // German4K 3.1.1: Auto = Stereo auf ALLEN Geräten (Schalter ton_auto aus = weiterhin so). Seit 2.6
    // galt das nur für Fire TV (dort verlor der Ton nach ~2 Folgen 5.1-AAC systemweit); dieselben Ausfälle
    // kamen danach von Android-TV-Boxen und Fernsehern mit Soundbar.
    // German4K 3.2.1 (T1/T2): mit ton_auto entscheidet der erkannte Ausgang ([TonWahl]).
    fun allowsMultichannel(mode: SurroundMode): Boolean = wegFuer(mode).mehrkanal

    /**
     * German4K 3.2.1 (T2): der Tonweg für [mode] — Schalter `ton_auto`, Sitzungssperre und erkannter
     * Ausgang ([TonAusgang.aktuell]) eingerechnet. Bei jedem Player-Bau neu gefragt, also wirkt ein
     * umgelegter Schalter oder ein neuer HDMI-Ausgang beim nächsten Bau (die Engines bauen dafür neu,
     * siehe [tonLage]).
     */
    fun wegFuer(mode: SurroundMode): TonWeg =
        TonWahl.weg(mode, tonAuto(), latched, TonAusgang.aktuell.value).also { TonAusgang.letzterWeg.value = it }

    /** German4K 3.2.1: Schalter ton_auto (fehlt = an). */
    fun tonAuto(): Boolean = German4kFeatures.an(German4kSchalter.TON_AUTO)

    /** German4K 3.2.1 (T5): Schalter ton_dialog (fehlt = an). Wirkt beim nächsten Player-Bau / mpv-Audio-Neustart. */
    fun tonDialog(): Boolean = German4kFeatures.an(German4kSchalter.TON_DIALOG)

    /**
     * German4K 3.2.1 (T1): ändert sich, wenn ein anderer Ausgang erkannt wird oder `ton_auto`/`ton_dialog`
     * umgelegt werden. Engines vergleichen dann [wegFuer] mit dem Weg, mit dem sie gebaut sind, und bauen
     * nur bei Abweichung neu.
     */
    val tonLage: kotlinx.coroutines.flow.Flow<Any> = kotlinx.coroutines.flow.combine(
        TonAusgang.aktuell,
        German4kFeatures.flow,
    ) { ausgang, f -> Triple(ausgang, f[German4kSchalter.TON_AUTO] != "aus", f[German4kSchalter.TON_DIALOG] != "aus") }
        .distinctUntilChanged()

    // German4K: Geräteerkennung wie German4kDeviceCaps (Feature `amazon.hardware.fire_tv`). Ohne
    // Context (Engine vor [noteDevice] gebaut) greift der Build-Fallback: Hersteller Amazon.
    @Volatile private var fireTv: Boolean? = null

    /** German4K: einmal mit einem Context aufrufen, damit die Fire-TV-Erkennung das System-Feature nutzt. */
    fun noteDevice(context: Context) {
        if (fireTv != null) return
        fireTv = runCatching {
            context.packageManager.hasSystemFeature("amazon.hardware.fire_tv") || amazonBuild()
        }.getOrDefault(amazonBuild())
    }

    // Seit 3.1.1 nicht mehr für [allowsMultichannel] nötig; bleibt, damit ein Rückweg eine Zeile ist.
    @Suppress("unused")
    private fun isFireTv(): Boolean = fireTv ?: amazonBuild()

    private fun amazonBuild(): Boolean = android.os.Build.MANUFACTURER.equals("Amazon", ignoreCase = true)

    /** Trip the latch. Idempotent — the first reason is the interesting one. */
    fun latchStereo(reason: String) {
        if (latched) return
        latched = true
        latchReason = reason
        android.util.Log.w("AudioOutputPolicy", "forcing stereo for this session: $reason")
    }

    /** The user changed the surround setting (German4K 3.2.1: or the HDMI output changed): give the output another chance. */
    fun clearLatch() {
        latched = false
        latchReason = null
    }
}

/**
 * A [DefaultRenderersFactory] that can be pinned to plain stereo PCM.
 *
 * When [forceStereo] is set the audio sink is built with [AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES]
 * — Media3's "minimum capabilities supported by all devices": 16-bit stereo PCM, **no encoded
 * passthrough**. `MediaCodecAudioRenderer` asks the sink what it supports before choosing between
 * passthrough and in-app decoding, so capping the sink is what actually removes the bitstream path;
 * a track-selection constraint alone would not (the selector still picks a 5.1 track when it is the
 * only one).
 *
 * The no-argument [DefaultAudioSink.Builder] is deprecated precisely *because* passing a `Context`
 * makes the sink query the real device capabilities and ignore anything set here — which is the one
 * thing we must not let it do. If a future Media3 removes it, the `runCatching` falls back to the
 * stock sink and we lose the guarantee rather than the playback.
 */
@UnstableApi
class OwnTVRenderersFactory(
    context: Context,
    private val forceStereo: Boolean,
    /** German4K 3.2.1 (T2): null = 3.2-Verhalten aus [forceStereo]. */
    private val tonWeg: TonWeg? = null,
) : DefaultRenderersFactory(context) {

    /**
     * German4K 3.2.1 (T7): AAC-Mehrkanal → AC3 als erster Audio-Renderer, nur auf einem Bitstream-Weg mit
     * Schalter `ton_ac3`. Er teilt sich den Sink mit den übrigen Audio-Renderern (wie in Media3 üblich, es
     * ist immer nur einer aktiv) und lehnt jede Spur ab, die nicht passt — dann gilt die alte Reihenfolge.
     */
    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: androidx.media3.exoplayer.mediacodec.MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: android.os.Handler,
        eventListener: androidx.media3.exoplayer.audio.AudioRendererEventListener,
        out: java.util.ArrayList<androidx.media3.exoplayer.Renderer>,
    ) {
        val start = out.size
        super.buildAudioRenderers(
            context, extensionRendererMode, mediaCodecSelector, enableDecoderFallback, audioSink, eventHandler, eventListener, out,
        )
        val weg = tonWeg ?: return
        if (!Ac3Transcode.kandidat(weg)) return
        runCatching {
            out.add(start, FfmpegAc3TranscodeRenderer(eventHandler, eventListener, audioSink) { Ac3Transcode.darf(weg, it) })
        }.onFailure { android.util.Log.w("AudioOutputPolicy", "ac3 transcode renderer unavailable", it) }
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean,
    ): AudioSink? {
        val weg = tonWeg ?: if (forceStereo) TonWeg.STEREO else TonWeg.ALT_GERAET
        android.util.Log.i("AudioOutputPolicy", "exo audio sink: $weg (ausgang=${TonAusgang.aktuell.value?.technisch()})")
        when (weg) {
            TonWeg.ALT_GERAET, TonWeg.DURCHREICHEN ->
                return super.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams)
            // German4K 3.2.1 (T2): Mehrkanal-PCM — Sink ohne Bitstream-Fähigkeit (Dolby/DTS wird in der App
            // dekodiert), aber ohne Downmix-Prozessor: 5.1/7.1 gehen als PCM an den AudioTrack.
            TonWeg.MEHRKANAL_PCM -> return runCatching<AudioSink?> {
                @Suppress("DEPRECATION")
                DefaultAudioSink.Builder()
                    .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                    .setAudioProcessors(arrayOf(resample48kProcessor()))
                    .setEnableFloatOutput(false)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .build()
            }.getOrElse {
                android.util.Log.w("AudioOutputPolicy", "pcm-only sink unavailable, using device capabilities", it)
                super.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams)
            }
            // German4K 3.2.1 (T1): Automatisch an einem HDMI, das Dolby/DTS annimmt, aber kein Mehrkanal-PCM
            // (oder Fire TV): Bitstream durch, PCM wie im Stereo-Weg heruntermischen. Die Prozessoren wirken
            // nur auf PCM, Durchleitung läuft an ihnen vorbei.
            TonWeg.DURCHREICHEN_PCM_STEREO -> return runCatching<AudioSink?> {
                DefaultAudioSink.Builder(context)
                    .setAudioProcessors(arrayOf(stereoDownmixProcessor(), resample48kProcessor()))
                    .setEnableFloatOutput(false)
                    .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                    .build()
            }.getOrElse {
                android.util.Log.w("AudioOutputPolicy", "passthrough+stereo sink unavailable, using device capabilities", it)
                super.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams)
            }
            TonWeg.STEREO -> Unit
        }
        return runCatching<AudioSink?> {
            @Suppress("DEPRECATION")
            DefaultAudioSink.Builder()
                .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
                // German4K: Die Stereo-Kappung oben nimmt nur den Passthrough weg — ein dekodiertes
                // 5.1-AAC ging trotzdem als 6-Kanal-PCM an den AudioTrack (Fire TV: Ton weg). Erst
                // dieser Prozessor mischt wirklich auf 2 Kanäle herunter.
                // German4K: danach auf 48 kHz umrechnen — Fire TV blieb bei AAC 44,1 kHz Live stumm
                // (Kunde, 02.10.), 48 kHz desselben Senders hatte Ton.
                .setAudioProcessors(arrayOf(stereoDownmixProcessor(), resample48kProcessor()))
                // German4K: Float-Ausgabe umgeht in Media3 die eigenen AudioProcessors — dann gäbe es
                // keinen Downmix. Stereo-Pinning heißt ohnehin 16-bit-PCM.
                .setEnableFloatOutput(false)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                .build()
        }.getOrElse {
            android.util.Log.w("AudioOutputPolicy", "stereo-only sink unavailable, using device capabilities", it)
            super.buildAudioSink(context, enableFloatOutput, enableAudioOutputPlaybackParams)
        }
    }
}

/**
 * German4K: echter Stereo-Downmix für den gepinnten Sink. Media3 liefert Standardmatrizen nur bis
 * 6 Kanäle (5.1); 6.1/7.1 werden hier von Hand gemischt (AudioTrack-Reihenfolge: FL FR FC LFE BL BR
 * [BC | SL SR]). Mono/Stereo laufen als Identität durch — ohne Matrix für eine Kanalzahl würde der
 * Prozessor das Format ablehnen und der Sink gar nicht starten.
 */
@UnstableApi
/** German4K: rechnet 16-bit-PCM jeder Quellrate auf 48 kHz um. */
internal fun resample48kProcessor(): androidx.media3.common.audio.AudioProcessor = Resample48kProcessor()

/**
 * German4K: Sonic auf 48 kHz, aber nur für 16-bit-PCM mit anderer Rate. Sonic selbst wirft bei jedem
 * anderen Format (Float, 24 bit) — das hieße Abbruch statt Ton. Dann bleibt dieser Schritt einfach aus.
 */
@UnstableApi
private class Resample48kProcessor : androidx.media3.common.audio.AudioProcessor {
    private val sonic = androidx.media3.common.audio.SonicAudioProcessor().apply { setOutputSampleRateHz(48_000) }
    private var aktiv = false

    override fun configure(
        inputAudioFormat: androidx.media3.common.audio.AudioProcessor.AudioFormat,
    ): androidx.media3.common.audio.AudioProcessor.AudioFormat {
        aktiv = inputAudioFormat.encoding == androidx.media3.common.C.ENCODING_PCM_16BIT &&
            inputAudioFormat.sampleRate > 0 && inputAudioFormat.sampleRate != 48_000
        if (!aktiv) return androidx.media3.common.audio.AudioProcessor.AudioFormat.NOT_SET
        return sonic.configure(inputAudioFormat)
    }

    override fun isActive(): Boolean = aktiv && sonic.isActive
    override fun queueInput(inputBuffer: java.nio.ByteBuffer) = sonic.queueInput(inputBuffer)
    override fun queueEndOfStream() = sonic.queueEndOfStream()
    override fun getOutput(): java.nio.ByteBuffer = sonic.output
    override fun isEnded(): Boolean = sonic.isEnded
    override fun getDurationAfterProcessorApplied(durationUs: Long): Long =
        if (aktiv) sonic.getDurationAfterProcessorApplied(durationUs) else durationUs
    // German4K: beide flush-Varianten — media3 verlangt mindestens eine, ruft je nach Pfad aber beide.
    override fun flush() = sonic.flush(androidx.media3.common.audio.AudioProcessor.StreamMetadata.DEFAULT)
    override fun flush(streamMetadata: androidx.media3.common.audio.AudioProcessor.StreamMetadata) = sonic.flush(streamMetadata)
    override fun reset() { sonic.reset(); aktiv = false }
}

internal fun stereoDownmixProcessor(): ChannelMixingAudioProcessor {
    val p = ChannelMixingAudioProcessor()
    p.putChannelMixingMatrix(ChannelMixingMatrix(1, 1, floatArrayOf(1f)))
    p.putChannelMixingMatrix(ChannelMixingMatrix(2, 2, floatArrayOf(1f, 0f, 0f, 1f)))
    // German4K 3.2.1 (T5): Dialog in die Mitte — feste Matrix je Layout (3…8 Kanäle), siehe [TonDownmix].
    if (AudioOutputPolicy.tonDialog()) {
        for (n in 3..8) {
            val k = TonDownmix.dialogMitte(n) ?: continue
            runCatching { p.putChannelMixingMatrix(ChannelMixingMatrix(n, 2, k)) }
        }
        return p
    }
    for (n in 3..6) {
        runCatching { ChannelMixingMatrix.createForConstantPower(n, 2) }
            .onSuccess { p.putChannelMixingMatrix(it) }
    }
    // Koeffizienten zeilenweise je Eingangskanal: (links, rechts). LFE fällt weg wie bei Media3s 5.1.
    val c = 0.7071f
    val s7 = floatArrayOf(1f, 0f, 0f, 1f, c, c, 0f, 0f, c, 0f, 0f, c, 0.5f, 0.5f)
    val s8 = floatArrayOf(1f, 0f, 0f, 1f, c, c, 0f, 0f, 0.5f, 0f, 0f, 0.5f, 0.5f, 0f, 0f, 0.5f)
    runCatching { p.putChannelMixingMatrix(ChannelMixingMatrix(7, 2, s7)) }
    runCatching { p.putChannelMixingMatrix(ChannelMixingMatrix(8, 2, s8)) }
    return p
}

/**
 * Watches an ExoPlayer's audio output and reports the two failures a user experiences as "picture
 * but no sound" and "sound keeps cutting out".
 *
 * This is a listener plus a [poll] that the owning engine calls from the health tick it already
 * runs — it deliberately owns no timer of its own, so it cannot keep an engine alive after release.
 *
 * Detection, in order of confidence:
 *
 *  1. **`onAudioSinkError`** — the sink told us outright. Immediate. Excludes
 *     `UnexpectedDiscontinuityException`, which reports a gap in the *stream's* timestamps and is
 *     self-healing; see [onAudioSinkError].
 *  2. **Armed but never advancing.** `onAudioInputFormatChanged` proves an audio track was selected
 *     and handed to a decoder; `onAudioPositionAdvancing` fires when the AudioTrack's playback head
 *     actually starts moving, i.e. when sound genuinely leaves the device. If the first happens and
 *     the second does not within [AudioOutputPolicy.NO_AUDIO_GRACE_MS] of *playing* time, the output
 *     accepted the format and produced silence — the exact RMK62 signature. Playing time, not wall
 *     clock: a channel that spends eight seconds buffering has not failed at anything.
 *  3. **Repeated underruns.** One is a hiccup; [AudioOutputPolicy.UNDERRUN_LIMIT] inside
 *     [AudioOutputPolicy.UNDERRUN_WINDOW_MS] is a sink that cannot keep up with the format it said
 *     it could play.
 *
 * A hit calls back once per player instance; the owner is expected to latch stereo and rebuild.
 */
@UnstableApi
class AudioWatchdog : AnalyticsListener {

    /** Set when a failure is detected; consumed by [poll]. Cleared on [reset]. */
    @Volatile private var pendingReason: String? = null
    @Volatile private var fired = false

    @Volatile private var armed = false
    @Volatile private var advancing = false
    /** Accumulated *playing* milliseconds since the audio format was accepted. */
    @Volatile private var playingSinceArmMs = 0L
    @Volatile private var lastTickMs = 0L

    private val underrunTimes = ArrayDeque<Long>()

    /** The audio format currently feeding the sink, for the stream-info readout. */
    @Volatile var audioFormat: Format? = null
        private set
    /** True when the renderer chose passthrough (the TV/receiver decodes) rather than in-app decode. */
    @Volatile var passthrough = false
        private set
    /** Whether a decoder was initialised for the current audio format. See [onAudioPositionAdvancing]. */
    @Volatile private var decoderInitialized = false
    /** German4K 3.2.1 (T3): wann der letzte Decoder initialisiert wurde (Reihenfolge Decoder-Renderer). */
    @Volatile private var decoderInitAtMs = 0L
    /** German4K 3.2.1 (P4): Ausgabe-Zähler beim letzten [poll] (-1 = keiner), siehe [TonFortschritt]. */
    @Volatile private var ausgabeZaehlerVorher = -1L
    /** German4K 3.2.1 (P4): aufeinanderfolgende Zähler-Anstiege im Abspielen. */
    @Volatile private var ausgabeAnstiege = 0
    /** German4K 3.2.1 (P4): Encoding des aktiven AudioTracks ([C.ENCODING_INVALID] = noch keiner). */
    @Volatile private var senkenEncoding = C.ENCODING_INVALID
    /** German4K 3.2.1 (P4): aktiver Ton-Renderer ist `g4k-ac3-transcode`. */
    @Volatile private var transcodeAktiv = false

    /** German4K 3.2.1 (p-13/8): Tonposition lief seit dem letzten Formatwechsel an (AudioTrack nimmt ab). */
    val tonPositionLief: Boolean get() = advancing
    /** German4K 3.2.1 (p-13/8): Underruns im laufenden Fenster. */
    val underruns: Int get() = synchronized(underrunTimes) { underrunTimes.size }

    /** Call when a new load starts. */
    fun reset() {
        pendingReason = null; fired = false
        armed = false; advancing = false
        playingSinceArmMs = 0L; lastTickMs = 0L
        synchronized(underrunTimes) { underrunTimes.clear() }
        audioFormat = null; passthrough = false; decoderInitialized = false; decoderInitAtMs = 0L
        ausgabeZaehlerVorher = -1L; ausgabeAnstiege = 0 // German4K 3.2.1 (P4)
        senkenEncoding = C.ENCODING_INVALID; transcodeAktiv = false // German4K 3.2.1 (P4)
    }

    override fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
    ) {
        // The format actually reaching the sink, including the one chosen at startup — without this a
        // log only ever showed what the user switched *to*, never what they switched *from*, so a
        // stereo→5.1 change was indistinguishable from a change between two identical formats.
        android.util.Log.i(
            "AudioOutputPolicy",
            "audio format -> ${format.sampleMimeType} ${format.channelCount}ch ${format.sampleRate}Hz " +
                "lang=${format.language} reuse=${decoderReuseEvaluation?.result}",
        )
        audioFormat = format
        armed = true
        advancing = false
        // German4K 3.2.1 (T3): Decoder-Renderer (FFmpeg) melden erst „Decoder fertig“, dann das Format — der
        // Reset hätte den eben initialisierten Decoder vergessen und „passthrough“ gemeldet.
        decoderInitialized = decoderInitAtMs > 0 && eventTime.realtimeMs - decoderInitAtMs in 0..50
        // German4K 3.2.1 (p-19, Schalter ton_auto): ein weiterverwendeter Decoder (reuse ≠ NO) meldet sich nicht neu
        // an — Das Erste FHD (AAC → AAC) stand sonst als „passthrough“ im Log, obwohl PCM lief.
        if (TonPfad.an() && TonPfad.decoderWeiter(decoderReuseEvaluation?.result)) decoderInitialized = true
        playingSinceArmMs = 0L
        lastTickMs = 0L
        ausgabeZaehlerVorher = -1L; ausgabeAnstiege = 0 // German4K 3.2.1 (P4)
    }

    /** German4K 3.2.1 (P4): eindeutiges Bitstream-Kriterium — was die Senke wirklich bekommt. */
    override fun onAudioTrackInitialized(
        eventTime: AnalyticsListener.EventTime,
        audioTrackConfig: AudioSink.AudioTrackConfig,
    ) {
        senkenEncoding = audioTrackConfig.encoding
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        decoderInitialized = true
        decoderInitAtMs = eventTime.realtimeMs // German4K 3.2.1 (T3)
        transcodeAktiv = decoderName == Ac3TranscodeDecoder.NAME // German4K 3.2.1 (P4)
        // Media3 names the pass-through "decoder" after the encoding it is bitstreaming, and never
        // after a real codec. This catches the path where a codec *is* created; the other path is
        // caught in [onAudioPositionAdvancing].
        passthrough = decoderName.startsWith("audio.raw", ignoreCase = true) ||
            decoderName.startsWith("audio.passthrough", ignoreCase = true) ||
            // German4K 3.2.1 (T7): AAC → AC3 in der App, der Receiver dekodiert den Bitstream.
            decoderName == Ac3TranscodeDecoder.NAME
        // Whether the TV is decoding the bitstream or we are is the single most useful fact about an
        // audio problem, and it was only ever visible in the stream-info overlay, never in a log.
        android.util.Log.i(
            "AudioOutputPolicy",
            "audio decoder: $decoderName (init ${initializationDurationMs}ms, passthrough=$passthrough)",
        )
    }

    override fun onAudioPositionAdvancing(
        eventTime: AnalyticsListener.EventTime,
        playoutStartSystemTimeMs: Long,
    ) {
        advancing = true
        // The renderer has committed to a path by the time sound actually leaves the device, so this
        // is the safe moment to conclude what it chose. **No decoder at all** means MediaCodec was
        // bypassed and the encoded stream is going straight to the TV — Media3 reports no decoder
        // whatsoever on that path, so the decoder *name* checked above never arrives and passthrough
        // read `false` for every bitstreamed E-AC3/AC3/DTS track. Deferring to here rather than
        // deciding in onAudioInputFormatChanged avoids the window where the decoder simply has not
        // been created yet.
        if (!decoderInitialized && !passthrough) {
            // German4K 3.2.1 (p-19): erst fragen, was der AudioTrack wirklich bekommt — PCM heißt nie Durchleitung.
            val senkePcm = senkenEncoding != C.ENCODING_INVALID && Util.isEncodingLinearPcm(senkenEncoding)
            if (TonPfad.an() && !TonPfad.durchleitung(decoderInitialized, senkenEncoding != C.ENCODING_INVALID, senkePcm)) {
                android.util.Log.i("AudioOutputPolicy", "audio path: decoded in app — the sink gets PCM (encoding=$senkenEncoding)")
                return
            }
            passthrough = true
            android.util.Log.i("AudioOutputPolicy", "audio path: passthrough — the TV is decoding this stream")
        }
    }

    override fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long,
    ) {
        val now = android.os.SystemClock.elapsedRealtime()
        // Individual underruns were silent until the limit tripped, which made "sound keeps cutting
        // out" impossible to confirm from a log: below the limit there was no evidence at all, and at
        // the limit only a verdict. One line each is cheap — healthy playback produces none.
        android.util.Log.w(
            "AudioOutputPolicy",
            "audio underrun: buffer=${bufferSize}B/${bufferSizeMs}ms, ${elapsedSinceLastFeedMs}ms since last feed",
        )
        val hit = synchronized(underrunTimes) {
            underrunTimes.addLast(now)
            while (underrunTimes.isNotEmpty() && now - underrunTimes.first() > AudioOutputPolicy.UNDERRUN_WINDOW_MS) {
                underrunTimes.removeFirst()
            }
            underrunTimes.size >= AudioOutputPolicy.UNDERRUN_LIMIT
        }
        if (hit) raise("audio output underran ${AudioOutputPolicy.UNDERRUN_LIMIT}× in ${AudioOutputPolicy.UNDERRUN_WINDOW_MS / 1000}s")
    }

    override fun onAudioSinkError(eventTime: AnalyticsListener.EventTime, audioSinkError: Exception) {
        // A timestamp discontinuity is a statement about the *stream*, not about the output. Media3
        // raises it whenever a buffer's presentation time lands more than 200ms from where the running
        // frame count says it should (DefaultAudioSink), then re-anchors its own clock and carries on
        // playing — a routine event on files whose container timestamps jump. Treating it as sink
        // failure latched a whole session to stereo over one imperfect file, and since forcing a stereo
        // sink cannot repair a gap that lives in the file, the rebuilt player hit the same spot and
        // tripped again: repeated mid-film restarts on a device whose audio was never in trouble.
        // If the output really is dead, the no-advance tier catches it seconds later anyway.
        if (audioSinkError is AudioSink.UnexpectedDiscontinuityException) {
            android.util.Log.i(
                "AudioOutputPolicy",
                "audio timestamp discontinuity: ${audioSinkError.actualPresentationTimeUs - audioSinkError.expectedPresentationTimeUs}us " +
                    "off expected — the sink resyncs itself, not treating this as an output failure",
            )
            return
        }
        raise("audio sink error: ${audioSinkError.message ?: audioSinkError.javaClass.simpleName}")
    }

    private fun raise(reason: String) {
        if (fired) return
        fired = true
        pendingReason = reason
    }

    /**
     * Advance the "playing time since armed" clock and return a failure reason once, or null.
     * [isPlaying] must be the player's real playing state — paused time must not count.
     */
    fun poll(isPlaying: Boolean, ausgabeZaehler: Long = -1L): String? {
        pendingReason?.let { pendingReason = null; return it }
        if (!armed || advancing) { lastTickMs = 0L; return null }
        val now = android.os.SystemClock.elapsedRealtime()
        if (!isPlaying) {
            lastTickMs = 0L
            // German4K 3.2.1 (P4): Vorfüllen des Puffers vor dem Abspielen ist kein Ton.
            ausgabeZaehlerVorher = -1L; ausgabeAnstiege = 0
            return null
        }
        // German4K 3.2.1 (P4): bei Bitstream (Passthrough/AC3-Transcode) zählt auch der Ausgabe-Zähler des
        // Renderers als „Ton läuft“ — dort meldet der AudioTrack seine Position teils spät (Fire TV, T7).
        // Nur im Abspielen und erst nach mehreren Anstiegen in Folge.
        val senkePassthrough = senkenEncoding != C.ENCODING_INVALID && !Util.isEncodingLinearPcm(senkenEncoding)
        if (ausgabeZaehler >= 0 && TonFortschritt.aktiv(transcodeAktiv, senkePassthrough)) {
            ausgabeAnstiege = TonFortschritt.anstiege(ausgabeZaehlerVorher, ausgabeZaehler, ausgabeAnstiege)
            ausgabeZaehlerVorher = ausgabeZaehler
            if (TonFortschritt.laeuft(ausgabeAnstiege)) {
                advancing = true
                lastTickMs = 0L
                android.util.Log.i("AudioOutputPolicy", "audio path: bitstream output is being consumed — counting as sound")
                return null
            }
        }
        if (lastTickMs != 0L) playingSinceArmMs += (now - lastTickMs).coerceAtMost(2_000L)
        lastTickMs = now
        if (playingSinceArmMs < AudioOutputPolicy.NO_AUDIO_GRACE_MS) return null
        if (fired) return null
        fired = true
        val what = audioFormat?.let { MimeTypes.normalizeMimeType(it.sampleMimeType ?: "") } ?: "audio"
        return "no sound from the audio output after ${AudioOutputPolicy.NO_AUDIO_GRACE_MS / 1000}s ($what)"
    }

    /** Human-readable audio line for the stream-info overlay, or null when nothing is known yet. */
    fun describe(): String? {
        val f = audioFormat ?: return null
        val codec = f.sampleMimeType?.substringAfterLast('/')?.uppercase() ?: "?"
        val channels = if (f.channelCount != Format.NO_VALUE) "${f.channelCount}ch" else null
        val rate = if (f.sampleRate != Format.NO_VALUE) "${f.sampleRate / 1000}kHz" else null
        val path = if (passthrough) "passthrough" else "decoded"
        return listOfNotNull(codec, channels, rate, path).joinToString(" · ")
    }
}

/** Media3 channel-count cap that matches [mode]; [C.INDEX_UNSET] semantics are not used here. */
fun maxAudioChannelsFor(mode: SurroundMode): Int =
    if (AudioOutputPolicy.allowsMultichannel(mode)) Int.MAX_VALUE else 2

/**
 * German4K 3.2.1 (p-19): Diagnose „Durchleitung oder in der App dekodiert“ — reine Logik (Unit-Tests).
 * Maßgeblich ist, was der AudioTrack bekommt: PCM = dekodiert, Bitstream = Durchleitung. Erst ohne bekannte
 * Senke zählt „kein Decoder angemeldet“ als Durchleitung (3.2-Regel). Schalter `ton_auto` aus = 3.2-Verhalten.
 */
object TonPfad {
    fun an(): Boolean = tv.own.owntv.core.german4k.German4kFeatures.an(tv.own.owntv.core.german4k.German4kSchalter.TON_AUTO)

    /** Media3 `DecoderReuseEvaluation.result`: 0 = REUSE_RESULT_NO (neuer Decoder folgt), sonst bleibt der alte. */
    fun decoderWeiter(reuseResult: Int?): Boolean = reuseResult != null && reuseResult != 0

    fun durchleitung(decoderAktiv: Boolean, senkeBekannt: Boolean, senkePcm: Boolean): Boolean = when {
        senkeBekannt -> !senkePcm
        else -> !decoderAktiv
    }
}
