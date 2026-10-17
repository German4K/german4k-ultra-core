package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** German4K 3.2: Sport-Reiter vom Server — rückwärtskompatibel (fehlt = feste Chips). */
class German4kSportReiterTest {
    private fun antwort(extra: String) = German4kSportAntwort.parse("""{"ok":true,"spiele":[]$extra}""")!!
    private fun spiel(id: Long, wettbewerb: String?) =
        German4kSpiel(id, "t$id", "h", "g", wettbewerb, Instant.parse("2026-10-17T13:30:00Z"), "", "geplant", 0.0, "spiel", emptyList(), false, null, null)

    @Test fun `alter Server ohne reiter - feste Chips`() {
        val a = antwort("")
        assertNull(a.reiter)
        assertEquals(SportChip.entries.toList(), sportTabs(a.reiter).map { it.chip })
        assertEquals(SportTab.ALLE, sportTabs(a.reiter).first())
    }

    @Test fun `reiter werden gelesen, Alle steht vorn`() {
        val a = antwort(""","reiter":[{"id":"bundesliga","titel":"Bundesliga","wettbewerbe":["Bundesliga"]},
            {"id":"international","titel":"International","wettbewerbe":["Premier League","LaLiga"]}]""")
        assertEquals(listOf("bundesliga", "international"), a.reiter!!.map { it.id })
        val tabs = sportTabs(a.reiter)
        assertEquals(listOf("alle", "bundesliga", "international"), tabs.map { it.id })
        assertEquals("International", tabs[2].titel)
    }

    @Test fun `kaputte Eintraege fallen weg, alle und Doppelte auch`() {
        val a = antwort(""","reiter":[{"id":"alle","titel":"Alle","wettbewerbe":["x"]},{"id":"","titel":"X","wettbewerbe":["x"]},
            {"id":"a","titel":"","wettbewerbe":["x"]},{"id":"b","titel":"B"},{"id":"c","titel":"C","wettbewerbe":[]},7,
            {"id":"d","titel":"D","wettbewerbe":["Pokal"," ",3]},{"id":"d","titel":"D2","wettbewerbe":["y"]}]""")
        assertEquals(listOf(German4kSportReiter("d", "D", listOf("Pokal"))), a.reiter)
    }

    @Test fun `leeres Array - nur Alle, kein Array - alt`() {
        assertEquals(listOf(SportTab.ALLE), sportTabs(antwort(""","reiter":[]""").reiter))
        assertNull(antwort(""","reiter":"kaputt"""").reiter)
    }

    @Test fun `Filter nach Wettbewerb exakt`() {
        val spiele = listOf(spiel(1, "Bundesliga"), spiel(2, "2. Bundesliga"), spiel(3, "premier league "), spiel(4, null))
        val tab = SportTab("x", null, "X", listOf("Bundesliga", "Premier League"))
        assertEquals(listOf(1L, 3L), tabFilter(spiele, tab).map { it.id })
        assertEquals(4, tabFilter(spiele, SportTab.ALLE).size)
        // Fester Chip wie bisher (Teilwort).
        assertEquals(listOf(1L, 2L), tabFilter(spiele, sportTabs(null).first { it.chip == SportChip.BUNDESLIGA }).map { it.id })
    }

    @Test fun `reiter ueberleben den Cache`() {
        val a = antwort(""","reiter":[{"id":"cl","titel":"Champions League","wettbewerbe":["Champions League"]}]""")
        assertEquals(a.reiter, German4kSportAntwort.parse(a.toJson())!!.reiter)
        assertNull(German4kSportAntwort.parse(antwort("").toJson())!!.reiter)
        assertTrue(sportTabs(emptyList()).size == 1)
    }
}
