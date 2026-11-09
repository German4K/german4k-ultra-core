package tv.own.owntv.player

/**
 * German4K 3.3 (Schalter `ueberlast_neuversuch`): Film/Folge scheitert am Ende der Engine-Kette mit 502/503/504 — der
 * Server ist überlastet, nicht die Datei kaputt. Statt „beide Engines fehlgeschlagen“ sagt der Player „Gerade sehr
 * viel los …“ und versucht es nach [WARTEN_MS] selbst noch einmal, höchstens [MAX_VERSUCHE]-mal, abbrechbar.
 *
 * Bewusst außerhalb von OwnTVPlayer: die Kette selbst bleibt unverändert, entschieden wird am fertigen Fehler
 * ([PlaybackFailure] + Rohzeile aus [ErrorInfo.raw]). Live ist ausgenommen (dort gibt es Störungsdialog und Leiter).
 */
object German4kUeberlast {
    const val WARTEN_MS = 30_000L
    const val MAX_VERSUCHE = 3
    private val CODES = setOf(502, 503, 504)

    /** HTTP-Status hinter dem Fehler: Anbieter-Code am Fehler selbst, an Exos Fehler im Doppel-Fehler, sonst aus der Rohzeile. */
    fun status(failure: PlaybackFailure?, raw: String?): Int? =
        (failure as? PlaybackFailure.Raw)?.httpCode
            ?: ((failure as? PlaybackFailure.BothEnginesMpvFirst)?.exoError as? PlaybackFailure.Raw)?.httpCode
            ?: raw?.let { PlayerErrors.httpStatusIn(it) }

    /** Überlastung am Ende der Kette bei Film/Serie? */
    fun istUeberlast(failure: PlaybackFailure?, raw: String?, live: Boolean): Boolean =
        // German4K 3.3.1 (B4): die Wiederholbremse hat schon entschieden — kein stiller Neuversuch darüber.
        failure != null && failure !is PlaybackFailure.VodBremse && !live && status(failure, raw) in CODES

    /** Wartezeit vor dem nächsten stillen Versuch, null = keine Versuche mehr ([bisher] = schon gemachte). */
    fun naechsterVersuchIn(bisher: Int): Long? = if (bisher < MAX_VERSUCHE) WARTEN_MS else null
}
