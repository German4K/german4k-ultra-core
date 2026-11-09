package tv.own.owntv.player

/**
 * German4K 3.1: Hat eine Datei Tonspuren, von denen ExoPlayer keine spielen kann (DTS/TrueHD ohne
 * Durchleitung), liefe der Film stumm — ohne Fehler. Mit ExoPlayer als erstem Film-Player übernimmt dann mpv.
 */
internal object ExoTonPruefung {
    /** @param audioGruppenUnterstuetzt je Tonspur-Gruppe: kann ExoPlayer sie spielen? */
    fun unspielbar(audioGruppenUnterstuetzt: List<Boolean>): Boolean =
        audioGruppenUnterstuetzt.isNotEmpty() && audioGruppenUnterstuetzt.none { it }
}
