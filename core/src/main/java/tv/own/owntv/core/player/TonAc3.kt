package tv.own.owntv.core.player

/**
 * German4K 3.2.1 (T7): wann AAC-Mehrkanal in der App zu AC3 (Dolby Digital) umkodiert und als Bitstream
 * ausgegeben wird.
 *
 * Anlass: Fire-TV-Sticks geben kein Mehrkanal-PCM über HDMI aus — ein AAC-5.1-Film kommt am Receiver
 * als Stereo an, AC3-Sender dagegen in 5.1 (Kunde 20.10.). Also: AAC 5.1 → AC3 → Receiver.
 *
 * Nur ExoPlayer (FFmpeg-Encoder in libffmpegJNI.so); mpv hat keinen Encoder und bleibt unverändert.
 * Reine Funktion, damit die Auswahl ohne Gerät testbar ist.
 */
object TonAc3 {
    /** MIME von AAC in Media3 (`MimeTypes.AUDIO_AAC`). */
    const val AAC_MIME = "audio/mp4a-latm"

    /** Ziel-Bitrate: Höchstwert von Dolby Digital, wie Blu-ray/DVB-Spitze. */
    const val BITRATE = 640_000

    /** Ausgaberate, fest (HDMI-Passthrough mit 48 kHz geht überall; 44,1 kHz machte Fire TV stumm). */
    const val RATE = 48_000

    /**
     * @param schalterAn Schalter `ton_ac3` (fehlt = an)
     * @param weg Tonweg, mit dem der Player gebaut ist ([TonWahl.weg])
     * @param ausgang erkannter Ausgang ([TonAusgang.aktuell])
     * @param mime / [kanaele] Format der Tonspur
     */
    fun aktiv(schalterAn: Boolean, weg: TonWeg?, ausgang: TonAusgang?, mime: String?, kanaele: Int): Boolean {
        if (!schalterAn) return false
        // Nur Automatisch (mit erkanntem Bitstream) und Receiver; nie Stereo, Mehrkanal-PCM oder 3.2-„Surround“.
        if (weg == null || !weg.durchreichen) return false
        if (ausgang == null || !ausgang.hdmi || TonAusgang.Kodierung.AC3 !in ausgang.kodierungen) return false
        if (mime != AAC_MIME || kanaele <= 2) return false
        // Fire TV: Mehrkanal-PCM kommt nicht an, egal was gemeldet wird. Sonst nur, wenn das Gerät kein
        // Mehrkanal-PCM meldet — dann ist PCM 5.1 der bessere (verlustfreie) Weg.
        return ausgang.fireTv || !ausgang.mehrkanalPcm
    }

    /** Ausgangs-Kanalzahl des AC3 (Dolby Digital kann höchstens 5.1; 7.1 wird auf 5.1 gemischt). */
    fun ac3Kanaele(quelle: Int): Int = quelle.coerceIn(1, 6)

    /** Kurzname eines Kanal-Layouts für Diagnose: 6 → "5.1", 8 → "7.1". Nie Oberfläche. */
    fun layoutName(kanaele: Int): String = when (kanaele) {
        1 -> "1.0"
        2 -> "2.0"
        3 -> "3.0"
        4 -> "4.0"
        5 -> "5.0"
        6 -> "5.1"
        7 -> "6.1"
        8 -> "7.1"
        else -> "${kanaele}ch"
    }

    /** Diagnose-Kurzform, z. B. "aac 5.1 → ac3 5.1". */
    fun umwandlung(quelleKanaele: Int, ac3Kanaele: Int): String =
        "aac " + layoutName(quelleKanaele) + " → ac3 " + layoutName(ac3Kanaele)
}
