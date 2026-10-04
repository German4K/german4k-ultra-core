package tv.own.owntv.player

/**
 * Hat ein VOD-Item wirklich kein Bild? (Radio unter „Filme", reine Musik-MP4.) German4K 3.1: Fortschritt
 * zählt erst ab mehr als [MIN_PROGRESS_MS] hinter dem Startpunkt — beim Einstieg mittendrin steht die
 * Position sofort dort, bevor mpv den Videodecoder geöffnet hat (virtueller Sender nach Exo→mpv-Wechsel:
 * fälschlich „Nur Ton", danach schwarz). Reiner Entscheider, per JVM-Test prüfbar.
 */
internal object AudioOnlyDetect {
    const val MIN_ELAPSED_MS = 5_000L
    const val MIN_PROGRESS_MS = 1_000L

    fun isAudioOnly(
        fileLoaded: Boolean,
        elapsedMs: Long,
        positionMs: Long,
        startPositionMs: Long,
        audioModeOn: Boolean,
        videoCodec: String?,
    ): Boolean {
        val schwelle = if (startPositionMs <= 0) 0L else startPositionMs + MIN_PROGRESS_MS
        return fileLoaded && elapsedMs > MIN_ELAPSED_MS && positionMs > schwelle && (audioModeOn || videoCodec == null)
    }
}
