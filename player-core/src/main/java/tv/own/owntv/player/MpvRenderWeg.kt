package tv.own.owntv.player

/**
 * German4K 3.2.1 (A1, p-9): Ausgabeweg von mpv (`hwdec` + `vo`), rein und unit-getestet.
 *
 * Normal: Direktweg (`mediacodec` + `mediacodec_embed`); Kopie-Rettung `mediacodec-copy` + `gpu`; Software `no` + `gpu`.
 *
 * Direktweg gesperrt (ein hängender Rest-Kern lebt, [MpvRestKerne]): die Verklemmung sitzt im Direktweg, nicht in der
 * Hardware-Dekodierung. Mit GL also weiter Hardware, nur über die Kopie (`mediacodec-copy` + `gpu`). Vorher schaltete
 * die Sperre `hwdec=no` — bis Prozessende alles Software: 4K-Live endete im Decode-Guard, 1080p50 ruckelte. Ohne GL
 * (Kopie braucht `vo=gpu` wie Software) bleibt ≤ 1080p Software; darüber zieht ein Film ExoPlayer vor
 * ([exoVorziehen]), Live landet im Decode-Guard → Fehler → Live-Leiter (ExoPlayer).
 *
 * `glOk` heißt hier „die Kopie trägt“: GL vorhanden UND seit der Sperre hing noch kein Kern auf `mediacodec-copy`
 * (am TV-Emulator hängt der FFmpeg-MediaCodec-Decoder auch dort; dann wie vor p-9 ohne Hardware).
 */
internal object MpvRenderWeg {
    data class Weg(val hwdec: String, val vo: String) {
        val direkt: Boolean get() = vo == "mediacodec_embed"
    }

    /** Hardware-Dekodierung wirklich im Einsatz (Einstellung minus Einzel-Abschaltungen und Sperre ohne GL). */
    fun hwAktiv(hwEin: Boolean, softwareErzwungen: Boolean, ccSoftware: Boolean, gesperrt: Boolean, glOk: Boolean): Boolean =
        hwEin && !softwareErzwungen && !ccSoftware && !(gesperrt && !glOk)

    fun weg(hwAktiv: Boolean, kopieRettung: Boolean, gesperrt: Boolean): Weg = when {
        !hwAktiv -> Weg("no", "gpu")
        kopieRettung || gesperrt -> Weg("mediacodec-copy", "gpu")
        else -> Weg("mediacodec", "mediacodec_embed")
    }

    /** Sperre ohne GL, Film über 1080p: Software würde der Decode-Guard abbrechen — gleich ExoPlayer. */
    fun exoVorziehen(gesperrt: Boolean, glOk: Boolean, hwEin: Boolean, live: Boolean, quellHoehe: Int): Boolean =
        gesperrt && !glOk && hwEin && !live && quellHoehe > 1080
}
