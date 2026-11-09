package tv.own.owntv.player

/**
 * German4K 3.1: Start-Wächter für ExoPlayer als ersten Film-/Serien-Player (Standard seit 3.1).
 *
 * Gegenstück zu [VodOpenWatchdog]: ExoPlayer meldet einen Fehler meist selbst (→ mpv über onError), aber
 * auf manchen Boxen bleibt er ohne Fehler im Puffern stehen. Läuft 20 s nach dem Start nichts, übernimmt
 * mpv. Reiner Entscheider ohne Android-Abhängigkeit, damit er per JVM-Test prüfbar ist.
 */
internal object ExoVodStartWatchdog {
    const val TIMEOUT_MS = VodOpenWatchdog.VOD_OPEN_TIMEOUT_MS

    enum class Verdict { WAIT, RESTART_WINDOW, STAND_DOWN, FIRE }

    fun decide(
        exoPrimary: Boolean,
        exoActive: Boolean,
        paused: Boolean,
        errorShown: Boolean,
        prevPosMs: Long,
        posMs: Long,
        windowMs: Long,
    ): Verdict {
        if (errorShown || !exoPrimary) return Verdict.STAND_DOWN
        // Exo wartet noch auf die Fläche bzw. auf die Freigabe von mpv — Fenster läuft erst ab Start.
        if (!exoActive) return Verdict.RESTART_WINDOW
        if (prevPosMs >= 0) {
            val step = posMs - prevPosMs
            if (step in 1..VodOpenWatchdog.MAX_PLAY_STEP_MS) return Verdict.STAND_DOWN
            if (step != 0L) return Verdict.RESTART_WINDOW
        }
        if (paused) return Verdict.RESTART_WINDOW
        return if (windowMs >= TIMEOUT_MS) Verdict.FIRE else Verdict.WAIT
    }
}
