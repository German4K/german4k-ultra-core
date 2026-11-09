package tv.own.owntv.player

/**
 * German4K 3.2.1 (t-9, Schalter `exo_nachgeben`): Live-Start bei langsamem Server.
 *
 * [LiveExoWatchdog.OPEN_TIMEOUT_MS] (12 s) gab einen Sender auch dann an mpv, wenn der Server schon geantwortet hatte
 * und nur die ersten Daten auf sich warten ließen (erste Bytes nach 10–14 s). mpv begann dann eine neue Anfrage beim
 * selben langsamen Server — der Kunde wartete etwa doppelt so lange, vor dem Standbild des alten Senders.
 *
 * Regel: Ist bei Ablauf der Frist schon ein Lebenszeichen DIESER Abstimmung da — eine erfolgreiche HTTP-Antwort
 * (Kopfzeilen) oder geflossene Bytes —, bekommt ExoPlayer einmalig [NACHFRIST_MS] dazu. Ohne Lebenszeichen, nach einer
 * „Leitung belegt“-Antwort (458/460) oder ein zweites Mal nie. Keine neue Verbindung: es wird nur länger auf die
 * laufende Anfrage gewartet.
 */
object ExoNachfrist {
    /** Einmalige Nachfrist auf die Öffnungsfrist, wenn der Server schon lebt. */
    const val NACHFRIST_MS = 6_000L

    /** Was das Engine seit Beginn der Abstimmung vom Server gesehen hat. */
    data class Lebenszeichen(
        /** Eine erfolgreiche HTTP-Antwort (2xx, Kopfzeilen) kam an. */
        val antwort: Boolean,
        /** Medien-Bytes sind geflossen. */
        val bytes: Boolean,
        /** Letzter HTTP-Status dieser Abstimmung (null = keiner). */
        val letzterStatus: Int?,
    ) {
        companion object {
            val KEINE = Lebenszeichen(antwort = false, bytes = false, letzterStatus = null)
        }
    }

    /**
     * Nachfrist geben? [schalterAn] = `exo_nachgeben`, [schonGegeben] = in dieser Abstimmung bereits verlängert,
     * [leitungBelegt] = Statusprüfung „Leitung belegt“ (458/460 bzw. konfig.leitung_codes).
     */
    fun geben(
        schalterAn: Boolean,
        schonGegeben: Boolean,
        zeichen: Lebenszeichen,
        leitungBelegt: (Int) -> Boolean,
    ): Boolean {
        if (!schalterAn || schonGegeben) return false
        val status = zeichen.letzterStatus
        if (status != null && (leitungBelegt(status) || status !in 200..299)) return false
        return zeichen.antwort || zeichen.bytes
    }
}
