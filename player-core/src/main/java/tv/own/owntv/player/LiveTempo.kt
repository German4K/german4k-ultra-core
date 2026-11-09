package tv.own.owntv.player

import tv.own.owntv.core.german4k.German4kFeatures
import tv.own.owntv.core.german4k.German4kSchalter

/**
 * German4K 3.2.1 (P11, Schalter `live_tempo_fest`): Live läuft immer mit 1,0.
 *
 * Fire-TV-Abnahme: Tempo 1,25x auf einem Live-Sender → mpv spielt schneller, als der Sender liefert, der Puffer
 * läuft leer und mpv meldet alle 16 s „live stall … reconnect“. ExoPlayer ignorierte das Tempo bei Live ohnehin.
 * Deshalb: kein Tempo-Knopf bei Live, und ein Tempo aus Film/Serie (mpv behält `speed` über loadfile) wird beim
 * Live-Start auf 1,0 gesetzt. Aus = Verhalten 3.2.
 */
object LiveTempo {
    fun an(): Boolean = German4kFeatures.an(German4kSchalter.LIVE_TEMPO_FEST)

    /** Beim Laden: muss das Tempo zurück auf 1,0? */
    fun zuruecksetzen(live: Boolean, tempo: Double, an: Boolean = an()): Boolean =
        an && live && kotlin.math.abs(tempo - 1.0) > 0.001

    /** Wunsch nach anderem Tempo bei Live verwerfen (1,0 geht immer). */
    fun sperren(live: Boolean, tempo: Double, an: Boolean = an()): Boolean =
        an && live && kotlin.math.abs(tempo - 1.0) > 0.001
}
