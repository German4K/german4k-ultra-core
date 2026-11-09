package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.german4k.German4kAngesagtFilter.Wahl

/** German4K 3.2.1 (B3): Umschalter „Alle · Filme · Serien“. */
class German4kAngesagtFilterTest {
    private fun karte(tmdb: Int, serie: Boolean) = German4kAngesagtKarte(
        tmdb = tmdb, serie = serie, itemId = tmdb.toLong(), remoteId = "$tmdb", sourceId = 1, platz = tmdb, rang = tmdb,
        name = "t$tmdb", jahr = null, plakat = null, querbild = null, handlung = null, note = null, badge = null, fassungen = 1,
    )

    private val gemischt = listOf(karte(1, false), karte(2, true), karte(3, false), karte(4, true))

    @Test fun `nur eine Art - keine Chips`() {
        assertEquals(listOf(Wahl.ALLE), German4kAngesagtFilter.verfuegbar(listOf(karte(1, false), karte(2, false))))
        assertEquals(Wahl.entries.toList(), German4kAngesagtFilter.verfuegbar(gemischt))
    }

    @Test fun `lokale Wahl vor Server, Unbekanntes und Fehlendes auf Alle`() {
        val alle = German4kAngesagtFilter.verfuegbar(gemischt)
        assertEquals(Wahl.FILME, German4kAngesagtFilter.aktiv(null, "filme", alle))
        assertEquals(Wahl.SERIEN, German4kAngesagtFilter.aktiv("serien", "filme", alle))
        assertEquals(Wahl.ALLE, German4kAngesagtFilter.aktiv("quatsch", null, alle))
        assertEquals(Wahl.ALLE, German4kAngesagtFilter.aktiv("serien", "filme", listOf(Wahl.ALLE)))
    }

    @Test fun `filtern behaelt die Reihenfolge`() {
        assertEquals(listOf(1, 3), German4kAngesagtFilter.filtern(gemischt, Wahl.FILME).map { it.tmdb })
        assertEquals(listOf(2, 4), German4kAngesagtFilter.filtern(gemischt, Wahl.SERIEN).map { it.tmdb })
        assertEquals(gemischt, German4kAngesagtFilter.filtern(gemischt, Wahl.ALLE))
    }
}
