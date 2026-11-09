package tv.own.owntv.player

/**
 * German4K: VOD-Start-Wächter für mpv (Fall JJ, Philips Android TV, 20.09.2026).
 *
 * MKV-Filme liefen dort in mpv nie an: FILE_LOADED kam, die Container-Höhe auch (damit gibt der
 * bestehende videoCheckJob auf), aber time-pos bewegte sich nie — Endlos-Spinner ohne Fehler und
 * ohne Exo-Fallback. Dieser reine Entscheider sagt pro Takt, ob gewartet, aufgegeben oder an
 * ExoPlayer übergeben wird. Keine Android-Abhängigkeit, damit er per JVM-Test prüfbar ist.
 */
internal object VodOpenWatchdog {
    /** Ab FILE_LOADED (bzw. nach Pause/Sprung neu) so lange ohne Fortschritt → Fallback. */
    const val VOD_OPEN_TIMEOUT_MS = 20_000L

    /** Größter Positionssprung pro 1-s-Takt, der noch als normales Abspielen zählt. Größer = Seek. */
    const val MAX_PLAY_STEP_MS = 3_000L

    enum class Verdict { WAIT, RESTART_WINDOW, STAND_DOWN, FIRE }

    /**
     * @param prevPosMs Position im letzten Takt, -1 = noch keine.
     * @param windowMs Wartezeit seit Fensterbeginn (erster Takt mit FILE_LOADED, oder letzter Neustart).
     */
    fun decide(
        fileLoaded: Boolean,
        paused: Boolean,
        errorShown: Boolean,
        exoActive: Boolean,
        prevPosMs: Long,
        posMs: Long,
        windowMs: Long,
    ): Verdict {
        // Fehler steht schon / Exo spielt → nicht unsere Baustelle.
        if (errorShown || exoActive) return Verdict.STAND_DOWN
        // Ohne FILE_LOADED arbeitet die bestehende T_OPEN-Leiter (Reset → Exo-Fallback) — nicht doppeln.
        if (!fileLoaded) return Verdict.RESTART_WINDOW
        if (prevPosMs >= 0) {
            val step = posMs - prevPosMs
            // Kleiner Vorwärtsschritt = Wiedergabe läuft → Wächter fertig.
            if (step in 1..MAX_PLAY_STEP_MS) return Verdict.STAND_DOWN
            // Großer Sprung = (Resume-)Seek → Fenster neu, Seek-Puffern ist kein Hänger.
            if (step != 0L) return Verdict.RESTART_WINDOW
        }
        // Vom Nutzer pausiert (oder pausiert gestartet) → kein Hänger, Fenster läuft erst ab Play.
        if (paused) return Verdict.RESTART_WINDOW
        return if (windowMs >= VOD_OPEN_TIMEOUT_MS) Verdict.FIRE else Verdict.WAIT
    }
}
