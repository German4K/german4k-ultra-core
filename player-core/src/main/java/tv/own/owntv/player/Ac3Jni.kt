package tv.own.owntv.player

import java.nio.ByteBuffer

/**
 * German4K 3.2.1 (T7): JNI-Hülle in libffmpegJNI.so (Quelle player-core/ffmpeg/ac3_transcode_jni.cc,
 * gebaut mit player-core/ffmpeg/bau.sh): AAC dekodieren → 48 kHz / AC3-Layout → AC3 640 kbit/s.
 * Namen sind JNI-Exporte (Java_tv_own_owntv_player_Ac3Jni_*) — Klasse und Methoden nie umbenennen
 * (consumer-rules.pro).
 */
internal object Ac3Jni {
    /** Paket war kaputt (übersprungen), kein Abbruch. */
    const val SKIP = -10

    /** Öffnet Decoder + Encoder; 0 = ging nicht. */
    @JvmStatic external fun nInit(extra: ByteArray?, channels: Int, bitrate: Int, trimStart: Int): Long

    @JvmStatic external fun nOutChannels(handle: Long): Int

    /**
     * Ein AAC-Access-Unit ([inSize] Bytes ab Position 0 von [input], direkt); null/0 = Ende.
     * Rückgabe: Anzahl AC3-Frames in [out] (hintereinander, Größen in [sizes], pts in 1/48000 s in [pts]),
     * [SKIP] = Paket kaputt, sonst negativ = fatal. stats[0] = dekodierte Samples seit Reset, stats[1] = Quellrate.
     */
    @JvmStatic external fun nTranscode(
        handle: Long, input: ByteBuffer?, inSize: Int, out: ByteBuffer, sizes: IntArray, pts: LongArray, stats: LongArray,
    ): Int

    /** Nach Seek/Sprung: alles leeren, Encoder neu. 0 = ok. */
    @JvmStatic external fun nReset(handle: Long): Int

    @JvmStatic external fun nRelease(handle: Long)
}
