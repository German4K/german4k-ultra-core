package tv.own.owntv.core.recording

import tv.own.owntv.core.database.entity.RecordingEntity
import tv.own.owntv.core.german4k.German4kFeatures
import tv.own.owntv.core.german4k.German4kSchalter

/**
 * German4K 3.2.1 (q-3, G): ob der [RecordingWorker] überhaupt starten muss.
 *
 * Er läuft als Vordergrunddienst mit Benachrichtigung. Angestoßen wurde er bei jedem Ende des Zuschauens
 * (also bei jedem Sender- und Engine-Wechsel) — der Dienst startete, fand nichts und endete: auf Handys
 * eine aufblitzende Benachrichtigung je Umschalten, dazu Wecken und Akku. Nötig ist er nur, wenn etwas
 * aufnimmt oder eine Aufnahme mit eigener Leitung fällig ist. „Aufnehmen, was ich schaue" (leere URL)
 * schreibt der Player selbst — dafür braucht es den Dienst nicht.
 */
object RecordingStart {
    fun aktiv(): Boolean = German4kFeatures.an(German4kSchalter.AUFNAHME_NUR_FAELLIG)

    fun noetig(laeuft: Boolean, faellig: List<RecordingEntity>): Boolean =
        laeuft || faellig.any { it.streamUrl.isNotBlank() }
}
