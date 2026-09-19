package tv.own.owntv.core.german4k

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.util.Log
import android.view.Display

/**
 * What this device can actually do — decided once per start, then reused everywhere.
 *
 * Why this exists: too many support tickets are really a device limit, not a broken stream.
 * A first-generation Fire TV Stick has no 4K HEVC decoder, so every UHD channel ends in
 * "STREAM FAILED" and the customer reports an outage. A TV without Dolby Vision shows the
 * green-purple picture of a DV profile 5 stream and the customer reports a broken channel.
 * Neither can be seen from the server, and neither is worth a chat — the app knows it.
 *
 * Everything here is read defensively: a missing codec list or an odd vendor implementation
 * must never keep the app from starting, so anything that throws becomes "we don't know",
 * and "we don't know" always means "let the customer try".
 */
object German4kDeviceCaps {

    data class Caps(
        /** HEVC (H.265) can be decoded at all — every UHD and most FHD channels need it. */
        val hevc: Boolean,
        /** HEVC up to 4K: the decoder reports a maximum size of at least 3840×2160. */
        val hevc4k: Boolean,
        /** The display reports HDR10 (or HLG). */
        val hdr10: Boolean,
        /** The display reports Dolby Vision — profile 5 streams look wrong without it. */
        val dolbyVision: Boolean,
        /** An Amazon Fire TV device (its own settings paths, no Play Store, old Android bases). */
        val fireTv: Boolean,
        /** Android API level and the human-readable model, for diagnostics. */
        val api: Int,
        val model: String,
        /** Screen is a TV (leanback) rather than a tablet or phone. */
        val tv: Boolean,
    ) {
        /** Short one-liner for a diagnostics report. */
        fun summary(): String =
            "$model · Android ${Build.VERSION.RELEASE} (API $api)${if (fireTv) " · Fire TV" else ""}" +
                " · HEVC ${if (hevc) "ja" else "nein"}${if (hevc4k) " (4K)" else ""}" +
                " · HDR ${if (hdr10) "10" else "nein"}${if (dolbyVision) "+DV" else ""}"
    }

    @Volatile
    private var cached: Caps? = null

    /**
     * Debug builds only: tut so, als könne dieses Gerät kein 4K (`--ez g4k_no4k true`).
     *
     * Der Emulator hat einen 4K-Decoder, der alte Fire TV Stick eines Kunden nicht — ohne diesen
     * Schalter ließe sich der Ausweichweg auf eine kleinere Qualitätsstufe hier gar nicht vorführen.
     */
    @Volatile
    var debugKein4k: Boolean = false
        set(wert) { field = wert; cached = null }

    /** Cheap after the first call; safe to call from any thread. */
    fun get(context: Context): Caps {
        cached?.let { return it }
        val frisch = compute(context)
        val wirksam = if (debugKein4k) frisch.copy(hevc4k = false) else frisch
        cached = wirksam
        return wirksam
    }

    private fun compute(context: Context): Caps {
        val hevc = runCatching { hevcSupport() }.getOrElse {
            Log.w(TAG, "codec list unreadable: ${it.message}")
            // Unknown means "let them try": claiming no HEVC would hide half the list.
            HevcSupport(supported = true, uhd = true)
        }
        val hdr = runCatching { hdrSupport(context) }.getOrElse { HdrSupport(false, false) }
        val fireTv = runCatching {
            context.packageManager.hasSystemFeature("amazon.hardware.fire_tv") ||
                Build.MANUFACTURER.equals("Amazon", ignoreCase = true)
        }.getOrDefault(false)
        val tv = runCatching {
            context.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK)
        }.getOrDefault(false)
        return Caps(
            hevc = hevc.supported,
            hevc4k = hevc.uhd,
            hdr10 = hdr.hdr10,
            dolbyVision = hdr.dolbyVision,
            fireTv = fireTv,
            api = Build.VERSION.SDK_INT,
            model = "${Build.MANUFACTURER} ${Build.MODEL}",
            tv = tv,
        ).also { Log.i(TAG, it.summary()) }
    }

    private data class HevcSupport(val supported: Boolean, val uhd: Boolean)

    private fun hevcSupport(): HevcSupport {
        var supported = false
        var uhd = false
        for (info in MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos) {
            if (info.isEncoder) continue
            if (info.supportedTypes.none { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, ignoreCase = true) }) continue
            supported = true
            val caps: MediaCodecInfo.CodecCapabilities = runCatching {
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_HEVC)
            }.getOrNull() ?: continue
            val video = caps.videoCapabilities ?: continue
            if (runCatching { video.isSizeSupported(3840, 2160) }.getOrDefault(false)) uhd = true
        }
        return HevcSupport(supported, uhd)
    }

    private data class HdrSupport(val hdr10: Boolean, val dolbyVision: Boolean)

    @Suppress("DEPRECATION") // Display.getHdrCapabilities is the only path below API 34.
    private fun hdrSupport(context: Context): HdrSupport {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? android.hardware.display.DisplayManager
        val display = dm?.getDisplay(Display.DEFAULT_DISPLAY) ?: return HdrSupport(false, false)
        val types = display.hdrCapabilities?.supportedHdrTypes ?: return HdrSupport(false, false)
        return HdrSupport(
            hdr10 = types.any { it == Display.HdrCapabilities.HDR_TYPE_HDR10 || it == Display.HdrCapabilities.HDR_TYPE_HLG },
            dolbyVision = types.any { it == Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION },
        )
    }

    private const val TAG = "German4kCaps"
}
