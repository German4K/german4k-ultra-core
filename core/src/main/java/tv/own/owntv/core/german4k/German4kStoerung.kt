package tv.own.owntv.core.german4k

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Was der Kunde sieht, wenn ein Sender endgültig nicht läuft.
 *
 * Bis Build 8 stand dort OwnTVs Fehlerbildschirm mit dem technischen Grund — „Source error:
 * response code: 458". Der Satz ist für uns nützlich und für den Kunden wertlos: Er sagt nicht,
 * dass sein Zugang gerade auf einem zweiten Gerät läuft, und schon gar nicht, dass ein Knopf das
 * löst. Genau diese Fälle sind der häufigste Chat des Tages.
 *
 * Deshalb wird der Grund einmal durch [German4kHealth.klassifiziere] geschickt. Kommt etwas
 * Bekanntes heraus, zeigt die Overlay-Schicht einen Satz und den passenden Knopf; bei
 * [German4kHealth.Klasse.UNBEKANNT] bleibt alles wie bei OwnTV — lieber die technische Meldung
 * als eine erfundene Erklärung.
 */
object German4kStoerung {

    data class Lage(val klasse: German4kHealth.Klasse, val sender: String, val grund: String)

    private val _lage = MutableStateFlow<Lage?>(null)
    val lage: StateFlow<Lage?> = _lage.asStateFlow()

    /** Aus dem Player: Dieser Sender läuft nicht mehr, das war der Grund. */
    fun melde(sender: String, grund: String) {
        val klasse = German4kHealth.klassifiziere(grund)
        if (klasse == German4kHealth.Klasse.UNBEKANNT) {
            // Nicht still verwerfen: Wer im Feld einen Fehlertext sucht, den wir noch nicht kennen,
            // findet ihn hier — und kann ihn in klassifiziere() ergänzen.
            Log.i(TAG, "keine Einordnung für '$sender': $grund")
            return
        }
        Log.i(TAG, "Störung bei '$sender': $klasse ($grund)")
        _lage.value = Lage(klasse, sender, grund)
    }

    fun schliessen() { _lage.value = null }

    private const val TAG = "German4kStoerung"
}
