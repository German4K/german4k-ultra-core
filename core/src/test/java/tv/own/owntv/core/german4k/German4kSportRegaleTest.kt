package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** German4K: Regale und Chips des Sport-Hubs. */
class German4kSportRegaleTest {
    private fun spiel(id: Long, start: String, zustand: String = "geplant", relevanz: Double = 0.0, wettbewerb: String? = null) =
        German4kSpiel(id, "t$id", "h", "g", wettbewerb, Instant.parse(start), "", zustand, relevanz, "spiel", emptyList(), false, null, null)

    private val heute = LocalDate.of(2026, 10, 1)

    @Test
    fun `partitions and sorts`() {
        val r = regale(
            listOf(
                spiel(1, "2026-10-01T18:45:00Z", relevanz = 1.0),
                spiel(2, "2026-10-01T16:30:00Z"),
                spiel(3, "2026-10-01T18:45:00Z", relevanz = 5.0),
                spiel(4, "2026-10-01T17:00:00Z", zustand = "laeuft", relevanz = 2.0),
                spiel(5, "2026-10-01T16:00:00Z", zustand = "laeuft", relevanz = 9.0),
                spiel(6, "2026-10-03T13:30:00Z"),
                spiel(7, "2026-10-02T18:00:00Z"),
                spiel(8, "2026-09-30T18:00:00Z", zustand = "beendet"),
                spiel(9, "2026-10-01T10:00:00Z", zustand = "beendet"),
            ),
            heute,
        )
        assertEquals(listOf(5L, 4L), r.live.map { it.id })
        assertEquals(listOf(2L, 3L, 1L), r.heute.map { it.id })
        assertEquals(listOf(7L, 6L), r.spaeter.map { it.id })
        assertEquals(listOf(9L, 8L), r.beendet.map { it.id })
    }

    @Test
    fun `berlin date border`() {
        // 22:30 UTC = 00:30 Berlin am Folgetag → „Morgen & später".
        val r = regale(listOf(spiel(1, "2026-10-01T22:30:00Z"), spiel(2, "2026-09-30T22:30:00Z")), heute)
        assertEquals(listOf(2L), r.heute.map { it.id })
        assertEquals(listOf(1L), r.spaeter.map { it.id })
    }

    @Test
    fun `chips`() {
        val liste = listOf(
            spiel(1, "2026-10-01T18:00:00Z", wettbewerb = "Bundesliga"),
            spiel(2, "2026-10-01T18:00:00Z", wettbewerb = "UEFA Champions League"),
            spiel(3, "2026-10-01T18:00:00Z", wettbewerb = "DFB-Pokal"),
            spiel(4, "2026-10-01T18:00:00Z", wettbewerb = "Premier League"),
            spiel(5, "2026-10-01T18:00:00Z", wettbewerb = null),
            spiel(6, "2026-10-01T18:00:00Z", wettbewerb = "2. Bundesliga"),
        )
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), chipFilter(liste, SportChip.ALLE).map { it.id })
        assertEquals(listOf(1L, 6L), chipFilter(liste, SportChip.BUNDESLIGA).map { it.id })
        assertEquals(listOf(2L), chipFilter(liste, SportChip.CL).map { it.id })
        assertEquals(listOf(3L), chipFilter(liste, SportChip.POKAL).map { it.id })
        assertEquals(listOf(4L), chipFilter(liste, SportChip.INTERNATIONAL).map { it.id })
    }
}
