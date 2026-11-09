package tv.own.owntv.core.german4k

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import tv.own.owntv.core.database.entity.SourceEntity

/**
 * German4K 3.2.1 (U2): Angesagt/Hero blieben nach dem Erstsync leer bis zum Neustart. Die Regale werden
 * gegen den lokalen Katalog abgeglichen — beim ersten Start ist der noch leer, und neu gerechnet wurde nur
 * bei neuer Datei, Profilwechsel oder beim Öffnen eines Bildschirms. Dieser Fluss ändert sich, sobald eine
 * Quelle einen Abgleich abschließt (`lastSyncAt` neu) oder eine Quelle dazukommt/wegfällt.
 */
object German4kKatalogStand {
    /** Ein Wert je Katalogstand: (id, lastSyncAt) aller Quellen. Gleicher Stand → keine neue Ausgabe. */
    fun aus(quellen: Flow<List<SourceEntity>>): Flow<List<Pair<Long, Long?>>> =
        if (!German4kFeatures.an(German4kSchalter.ANGESAGT_NACH_SYNC)) {
            flowOf(emptyList())
        } else {
            quellen.map { liste -> liste.map { it.id to it.lastSyncAt }.sortedBy { it.first } }.distinctUntilChanged()
        }
}
