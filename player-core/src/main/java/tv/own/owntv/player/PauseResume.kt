package tv.own.owntv.player

/**
 * German4K 3.1.1: Film hängt nach längerer Pause. Viele Panels schließen eine ruhende VOD-Verbindung nach
 * ein bis zwei Minuten; beim Fortsetzen wartet der Player dann endlos auf Bytes, die nie kommen.
 *
 * Zwei Wege, je Fortsetzen höchstens EIN automatisches Neuladen:
 *  - Pause länger als [LONG_PAUSE_MS] → sofort an der aktuellen Stelle neu öffnen.
 *  - Sonst ein Wächter: steht der Puffer nach dem Fortsetzen länger als [STALL_MS] und bewegt sich die
 *    Position nicht, einmal neu laden.
 *
 * Nie für Live (dort holt der Live-Wächter die Kante) und nie für Catch-up-Archive: die können nicht an
 * einer Stelle geöffnet werden (siehe `rescueResumePosition`), ein Neuladen finge von vorn an.
 */
internal object PauseResume {
    const val LONG_PAUSE_MS = 60_000L
    const val STALL_MS = 10_000L
    /** So lange nach dem Fortsetzen schaut der Wächter hin; danach ist ein Hänger kein Pausenfolgeschaden mehr. */
    const val WATCH_WINDOW_MS = 45_000L

    /** Kommt für dieses Item überhaupt ein Neuladen in Frage? */
    fun eligible(isLive: Boolean, isArchive: Boolean, positionMs: Long): Boolean =
        !isLive && !isArchive && positionMs > 0L

    /** Sofort neu laden beim Fortsetzen? [pausedForMs] ≤ 0 heißt: keine bekannte Pause. */
    fun reloadOnResume(pausedForMs: Long, isLive: Boolean, isArchive: Boolean, positionMs: Long): Boolean =
        eligible(isLive, isArchive, positionMs) && pausedForMs > LONG_PAUSE_MS

    /** Wächter-Urteil: seit [stallSinceMs] puffert der Player, und die Position steht auf [stallPosMs]. */
    fun stalled(nowMs: Long, stallSinceMs: Long, stallPosMs: Long, positionMs: Long): Boolean =
        stallSinceMs > 0L && positionMs == stallPosMs && nowMs - stallSinceMs >= STALL_MS
}
