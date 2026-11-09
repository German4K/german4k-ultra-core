package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** German4K 3.3: „Meine Mannschaft“ — Erkennen, Sortieren, Startseiten-Regal, Auto-Erinnerung. */
class German4kMeineMannschaftTest {
    private fun spiel(
        id: Long, heim: String, gast: String, start: String = "2026-11-07T14:30:00Z", zustand: String = "geplant",
        art: String = "spiel", paarungen: List<German4kPaarung> = emptyList(),
    ) = German4kSpiel(id, "$heim – $gast", heim, gast, "Bundesliga", Instant.parse(start), "", zustand, 0.0, art, emptyList(), false, null, null, paarungen)

    private val meine = setOf(German4kMeineMannschaft.schluessel("FC Bayern München"))

    @Test
    fun `Schluessel ignoriert Gross-Klein und Leerraum`() {
        assertEquals("fc bayern münchen", German4kMeineMannschaft.schluessel("  FC  Bayern\tMünchen "))
    }

    @Test
    fun `erkennt Heim, Gast und Konferenz-Paarung`() {
        assertTrue(German4kMeineMannschaft.istMeine(spiel(1, "fc bayern münchen", "BVB"), meine))
        assertTrue(German4kMeineMannschaft.istMeine(spiel(2, "BVB", "FC Bayern München"), meine))
        val konf = spiel(3, "", "", art = "konferenz", paarungen = listOf(German4kPaarung("Mainz", "FC Bayern  München", null)))
        assertTrue(German4kMeineMannschaft.istMeine(konf, meine))
        assertFalse(German4kMeineMannschaft.istMeine(spiel(4, "BVB", "Schalke"), meine))
        assertFalse(German4kMeineMannschaft.istMeine(spiel(5, "FC Bayern München", "BVB"), emptySet()))
    }

    @Test
    fun `meine zuerst ist stabil`() {
        val l = listOf(spiel(1, "A", "B"), spiel(2, "BVB", "FC Bayern München"), spiel(3, "C", "D"), spiel(4, "FC Bayern München", "E"))
        assertEquals(listOf(2L, 4L, 1L, 3L), German4kMeineMannschaft.meineZuerst(l, meine).map { it.id })
        assertEquals(listOf(1L, 2L, 3L, 4L), German4kMeineMannschaft.meineZuerst(l, emptySet()).map { it.id })
    }

    @Test
    fun `Regale je Regal meine zuerst`() {
        val r = SportRegale(
            live = listOf(spiel(1, "A", "B", zustand = "laeuft"), spiel(2, "FC Bayern München", "B", zustand = "laeuft")),
            heute = listOf(spiel(3, "A", "B"), spiel(4, "C", "FC Bayern München")),
        )
        val s = German4kMeineMannschaft.regaleMeineZuerst(r, meine)
        assertEquals(listOf(2L, 1L), s.live.map { it.id })
        assertEquals(listOf(4L, 3L), s.heute.map { it.id })
    }

    @Test
    fun `Startregal nimmt kommende eigene Spiele dazu, eigene zuerst`() {
        val r = SportRegale(
            live = listOf(spiel(1, "A", "B", zustand = "laeuft")),
            heute = listOf(spiel(2, "C", "D"), spiel(3, "E", "F")),
            spaeter = listOf(spiel(4, "G", "H"), spiel(5, "FC Bayern München", "X"), spiel(6, "Y", "FC Bayern München"), spiel(7, "FC Bayern München", "Z")),
        )
        assertEquals(listOf(5L, 6L, 1L, 2L, 3L), German4kMeineMannschaft.startRegal(r, meine, 8).map { it.id })
        assertEquals(listOf(5L, 6L, 1L), German4kMeineMannschaft.startRegal(r, meine, 3).map { it.id })
        // Ohne eigene Mannschaft wie bisher: live + heute.
        assertEquals(listOf(1L, 2L, 3L), German4kMeineMannschaft.startRegal(r, emptySet(), 8).map { it.id })
    }

    @Test
    fun `Erinnerungs-Kandidaten nur kommende, nicht erledigte, in Reichweite`() {
        val jetzt = Instant.parse("2026-11-07T10:00:00Z").toEpochMilli()
        val spiele = listOf(
            spiel(1, "FC Bayern München", "A", start = "2026-11-07T14:30:00Z"),
            spiel(2, "FC Bayern München", "B", start = "2026-11-07T09:30:00Z", zustand = "laeuft"),
            spiel(3, "FC Bayern München", "C", start = "2026-11-06T14:30:00Z", zustand = "beendet"),
            spiel(4, "X", "Y", start = "2026-11-07T15:30:00Z"),
            spiel(5, "FC Bayern München", "D", start = "2026-11-30T14:30:00Z"), // zu weit weg
            spiel(6, "E", "FC Bayern München", start = "2026-11-10T19:00:00Z"),
        )
        assertEquals(listOf(1L, 6L), German4kMeineMannschaft.erinnerungsKandidaten(spiele, meine, jetzt, emptySet()).map { it.id })
        assertEquals(listOf(6L), German4kMeineMannschaft.erinnerungsKandidaten(spiele, meine, jetzt, setOf(1L)).map { it.id })
        assertTrue(German4kMeineMannschaft.erinnerungsKandidaten(spiele, emptySet(), jetzt, emptySet()).isEmpty())
    }

    @Test
    fun `Konferenz nur ohne eigenes Einzelspiel zur selben Zeit`() {
        val jetzt = Instant.parse("2026-11-07T10:00:00Z").toEpochMilli()
        val konf = spiel(10, "", "", art = "konferenz", paarungen = listOf(German4kPaarung("FC Bayern München", "Mainz", null)))
        val einzel = spiel(11, "FC Bayern München", "Mainz")
        assertEquals(listOf(11L), German4kMeineMannschaft.erinnerungsKandidaten(listOf(konf, einzel), meine, jetzt, emptySet()).map { it.id })
        assertEquals(listOf(10L), German4kMeineMannschaft.erinnerungsKandidaten(listOf(konf), meine, jetzt, emptySet()).map { it.id })
    }
}
