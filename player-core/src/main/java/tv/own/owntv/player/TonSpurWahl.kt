package tv.own.owntv.player

/**
 * German4K 3.2.1 (p-19): Welche Live-Tonspur ExoPlayer spielen darf — und wann wirklich nur mpv bleibt.
 *
 * Anlass (Fire TV 4K Max, K14): Das Erste HD kommt als **MP2** (MPEG-1 Layer II, TS-Typ 0x03), nicht als AC3.
 * Das Gerät hat dafür keinen MediaCodec; ExoPlayer meldete „no decodable audio track“ und gab an mpv ab
 * (Bild erst nach ~6 s). Abhilfe: der mitgelieferte FFmpeg-Tondecoder (`ton_ffmpeg`) kann MPEG-Audio
 * (FFmpeg „mp3“ dekodiert Layer I/II/III), damit ist die Spur für Exo spielbar und mpv nur noch der Notweg.
 *
 * Reine Logik, ohne Android/Media3 testbar.
 */
object TonSpurWahl {

    /** Eine Tonspur, wie Media3 sie meldet: MIME und ob ein Renderer sie spielen kann. */
    data class Spur(val id: Int, val mime: String?, val spielbar: Boolean)

    /**
     * MIME-Typen, die die eingebaute `libffmpegJNI.so` dekodiert (bau.sh: ac3, eac3, dca, truehd, mlp, aac, mp3).
     * Gleiche Zuordnung wie Media3 `FfmpegLibrary.getCodecName`; nur zur Diagnose — ob Media3 die Spur
     * wirklich nimmt, sagt allein `isTrackSupported`.
     */
    private val FFMPEG_MIMES = setOf(
        "audio/ac3", "audio/eac3", "audio/eac3-joc", "audio/true-hd",
        "audio/vnd.dts", "audio/vnd.dts.hd", "audio/vnd.dts.hd;profile=lbr",
        "audio/mp4a-latm", "audio/mpeg", "audio/mpeg-L1", "audio/mpeg-L2",
    )

    /** Deckt der eingebaute FFmpeg-Decoder diesen Ton-MIME ab? */
    fun ffmpegDeckt(mime: String?): Boolean = mime != null && mime in FFMPEG_MIMES

    /** Ton vorhanden, aber keine einzige Spur spielbar → nur dann an mpv. Ohne Ton (Video ohne Spur) nie. */
    fun mpvNoetig(spuren: List<Spur>): Boolean = spuren.isNotEmpty() && spuren.none { it.spielbar }

    /**
     * Kandidaten für die Sprach-/Titelwahl: nur spielbare Spuren. Eine Wunschsprache auf einer Spur, die das
     * Gerät weder dekodieren noch durchleiten kann, wäre Stille — dann lieber die spielbare Spur behalten.
     * Ist gar keine als spielbar gemeldet (alte Meldung, Liste noch unvollständig), bleibt die Liste wie sie ist.
     */
    fun <T> nurSpielbare(optionen: List<T>, id: (T) -> Int, spuren: List<Spur>): List<T> {
        val ok = spuren.filter { it.spielbar }.map { it.id }.toSet()
        if (ok.isEmpty()) return optionen
        return optionen.filter { id(it) in ok }
    }

    /** Grund für das Support-Log, mit den MIME-Typen, z. B. „no decodable audio track (audio/mpeg-L2)“. */
    fun grund(spuren: List<Spur>): String {
        val mimes = spuren.mapNotNull { it.mime }.distinct()
        return if (mimes.isEmpty()) GRUND else "$GRUND (${mimes.joinToString(",")})"
    }

    /** Unverändert der 3.2-Text, damit Auswertungen, die danach suchen, weiter greifen. */
    const val GRUND = "no decodable audio track"
}
