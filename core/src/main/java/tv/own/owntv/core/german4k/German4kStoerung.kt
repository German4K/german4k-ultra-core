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
        val klasse = einordnen(grund, German4kFeatures.an(German4kSchalter.STOERUNG_EHRLICH))
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

    /**
     * German4K 3.2.1 (U8): [melde] kommt nur, wenn der Sender ENDGÜLTIG nicht läuft (Player gibt auf). Ein
     * Verbindungsfehler im Grund („unexpected end of stream", „timed out") hieß bisher [German4kHealth.Klasse.HOST_AUSFALL]
     * — und der Dialog sagte „Auf die zweite Verbindung gewechselt … Das Bild ist wieder da", während schwarz blieb
     * (abn2-a9/24-engine-v11.png, M3U-Quelle ohne zweite Verbindung, Log „giving up: no picture within 30s").
     * Ein gelungener Wechsel endet nie hier; also gilt der Fall als unbekannt, und der Kunde sieht die ehrliche
     * technische Meldung des Players statt einer erfundenen Entwarnung. [ehrlich] = false: altes 3.2-Verhalten.
     */
    fun einordnen(grund: String, ehrlich: Boolean = true): German4kHealth.Klasse {
        val klasse = German4kHealth.klassifiziere(grund)
        return if (ehrlich && klasse == German4kHealth.Klasse.HOST_AUSFALL) German4kHealth.Klasse.UNBEKANNT else klasse
    }

    /**
     * German4K 3.2.1 (B4): nach erfolgreicher Freigabe zählt das hoch — die Shell startet dann den Sender
     * (Live) bzw. den Film (VOD) neu. Ein Zähler statt eines Ereignisses, damit kein Signal verloren geht.
     */
    private val _neustart = MutableStateFlow(0)
    val neustart: StateFlow<Int> = _neustart.asStateFlow()
    fun neuStarten() { _lage.value = null; _neustart.value++ }

    /**
     * German4K 3.2.1 (B1): solange Multiview offen ist, kommt ein 458 von einer weiteren Kachel — die eigene
     * zweite Leitung. „Leitung freigeben“ würde dann die erste Kachel abschießen, also kein Knopf.
     */
    @Volatile var multiviewOffen: Boolean = false

    /** German4K 3.1: nur zurücknehmen, wenn die stehende Störung eine dieser Klassen ist (z. B. nach einem
     *  gelungenen Tune oder einer erkannten Verlängerung). */
    fun schliessenWenn(vararg klassen: German4kHealth.Klasse) {
        if (_lage.value?.klasse in klassen) _lage.value = null
    }

    private const val TAG = "German4kStoerung"
}
