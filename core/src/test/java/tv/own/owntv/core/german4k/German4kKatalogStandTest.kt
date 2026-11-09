package tv.own.owntv.core.german4k

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.database.entity.SourceEntity
import tv.own.owntv.core.model.SourceType

/** German4K 3.2.1 (U2): Angesagt rechnet neu, sobald der Erstsync fertig ist — nicht erst nach Neustart. */
class German4kKatalogStandTest {

    private fun quelle(id: Long, sync: Long?) =
        SourceEntity(id = id, name = "G4K", type = SourceType.XTREAM, url = "http://h", lastSyncAt = sync)

    @Test
    fun erstsyncFertigLoestNeuberechnungAus() = runBlocking {
        val staende = German4kKatalogStand.aus(
            flowOf(
                emptyList(), // Erststart: noch keine Quelle
                listOf(quelle(1, null)), // Quelle angelegt, Import läuft
                listOf(quelle(1, null)), // Import läuft weiter (gleicher Stand → keine Ausgabe)
                listOf(quelle(1, 1_000L)), // Erstsync fertig → neu abgleichen
                listOf(quelle(1, 1_000L)),
            ),
        ).toList()
        assertEquals(3, staende.size)
        assertEquals(listOf(1L to 1_000L), staende.last())
    }

    @Test
    fun schalterAusLiefertFestenStand() = runBlocking {
        German4kFeatures.set(mapOf(German4kSchalter.ANGESAGT_NACH_SYNC to "aus"))
        try {
            val staende = German4kKatalogStand.aus(flowOf(listOf(quelle(1, null)), listOf(quelle(1, 5L)))).toList()
            assertEquals(listOf(emptyList<Pair<Long, Long?>>()), staende)
        } finally {
            German4kFeatures.set(emptyMap())
        }
    }
}
