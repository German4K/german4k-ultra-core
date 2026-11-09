package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.own.owntv.player.LeitungFrei.Nach

/** German4K 3.2.1 (t-14): Senderwechsel und Neuverbindung auf 1-Verbindungs-Zugängen (zap_erst_schliessen). */
class LeitungFreiTest {
    private val belegt: (Int) -> Boolean = { it == 458 || it == 460 }

    @Before fun leeren() = LeitungFrei.vergessen()

    private fun nach(
        an: Boolean = true, status: Int? = 458, schon: Int = 0, gewaehlt: Boolean = true, vorne: Boolean = true,
    ) = LeitungFrei.nachBelegt(an, status, belegt, schon, gewaehlt, vorne)

    // --- Schließ-Entscheidung -------------------------------------------------------------------------

    @Test fun `1-Verbindungs-Zugang - erst schliessen, dann anfragen`() {
        assertTrue(LeitungFrei.erstSchliessen(schalterAn = true, einzelLeitung = true, alterStromOffen = true))
    }

    @Test fun `Schalter aus - altes paralleles Umschalten`() {
        assertFalse(LeitungFrei.erstSchliessen(schalterAn = false, einzelLeitung = true, alterStromOffen = true))
        assertFalse(LeitungFrei.einzelLeitung(schalterAn = false, schonBelegt = true, belegtGesehen = true))
    }

    @Test fun `mehrere Verbindungen oder nichts offen - nichts zu schliessen`() {
        assertFalse(LeitungFrei.erstSchliessen(schalterAn = true, einzelLeitung = false, alterStromOffen = true))
        assertFalse(LeitungFrei.erstSchliessen(schalterAn = true, einzelLeitung = true, alterStromOffen = false))
    }

    @Test fun `1-Verbindung - max_connections 1 oder schon 458-460 auf dem Host`() {
        assertTrue(LeitungFrei.einzelLeitung(schalterAn = true, schonBelegt = true, belegtGesehen = false))
        assertTrue(LeitungFrei.einzelLeitung(schalterAn = true, schonBelegt = false, belegtGesehen = true))
        // max_connections 0 (German4K oder unbekannt) und nie 458: unverändert (t-15: kein pauschales German4K mehr)
        assertFalse(LeitungFrei.einzelLeitung(schalterAn = true, schonBelegt = false, belegtGesehen = false))
    }

    // German4K 3.2.1 (t-15): German4K-Host mit „0“ zählt erst nach einem 458/460 außerhalb einer Kachel.
    @Test fun `German4K-Host ohne 458 - kein Warten, Kachel-458 zaehlt nicht`() {
        val u = "http://g4k.example:8080/live/u/p/1.ts"
        assertFalse(LeitungFrei.einzelLeitungFuer(true, u))
        LeitungFrei.belegtGesehen(u, kachel = true) // Kachel über dem Konto-Limit
        assertFalse(LeitungFrei.einzelLeitungFuer(true, u))
        LeitungFrei.belegtGesehen(u, kachel = false)
        assertTrue(LeitungFrei.einzelLeitungFuer(true, "http://g4k.example:8080/live/u/p/7.ts"))
        assertFalse(LeitungFrei.einzelLeitungFuer(false, u)) // Schalter aus / noch nicht geladen
        assertFalse(LeitungFrei.einzelLeitungFuer(true, "http://other.example:8080/live/u/p/1.ts"))
        assertFalse(LeitungFrei.einzelLeitungFuer(true, null))
    }

    // --- Backoff nach „Leitung belegt“ ----------------------------------------------------------------

    @Test fun `Backoff 1 - 2 - 3 - 4 s, Summe 10 s, dann aufgeben`() {
        // German4K 3.2.1 (t-16/5): 1/2/3/4 s, kumuliert 1/3/6/10 s
        assertEquals(1_000L, LeitungFrei.warteMs(0))
        assertEquals(2_000L, LeitungFrei.warteMs(1))
        assertEquals(3_000L, LeitungFrei.warteMs(2))
        assertEquals(4_000L, LeitungFrei.warteMs(3))
        assertNull(LeitungFrei.warteMs(4))
        assertEquals(4, LeitungFrei.BUDGET)
        assertTrue(LeitungFrei.WARTE_MS.sum() <= 10_000L)
    }

    @Test fun `458 und 460 - wiederholen bis das Budget verbraucht ist`() {
        assertEquals(Nach.WIEDERHOLEN, nach(schon = 0))
        assertEquals(Nach.WIEDERHOLEN, nach(status = 460, schon = 3)) // German4K 3.2.1 (t-16/5): vier Stufen
        assertEquals(Nach.AUFGEBEN, nach(schon = 4))
    }

    @Test fun `Budget je Ereignis - neues Ereignis beginnt wieder bei 0`() {
        // Ereignis 1 (erster Hänger) verbraucht zwei Wiederholungen und spielt wieder …
        assertEquals(Nach.WIEDERHOLEN, nach(schon = 1))
        // … der zweite Hänger ist ein neues Ereignis (Engine setzt den Zähler bei jeder Neuverbindung auf 0) — anders als
        // die alte „eine Wiederholung je Abstimmung“ (k11: zweiter Hänger → sofort Fehlerbild).
        assertEquals(Nach.WIEDERHOLEN, nach(schon = 0))
    }

    @Test fun `weggezappt oder Hintergrund - nichts mehr anfragen`() {
        assertEquals(Nach.LASSEN, nach(gewaehlt = false))
        assertEquals(Nach.LASSEN, nach(vorne = false))
        assertEquals(Nach.LASSEN, nach(schon = 3, vorne = false))
    }

    @Test fun `anderer Fehler oder Schalter aus - alter Weg`() {
        assertEquals(Nach.ALT, nach(status = 500))
        assertEquals(Nach.ALT, nach(status = null))
        assertEquals(Nach.ALT, nach(an = false))
        assertEquals(Nach.ALT, nach(an = false, schon = 3))
    }

    @Test fun `Waechter wartet die geplante Wiederholung ab`() {
        // 3-s-Wiederholung bei 20 s geplant, Wächter fragt bei 21 s → Rest bis 20 + 3 + 8 = 31 s.
        assertEquals(10_000L, LeitungFrei.waechterNachfristMs(true, 20_000L, 3_000L, 21_000L))
        assertEquals(0L, LeitungFrei.waechterNachfristMs(true, 0L, 3_000L, 21_000L))
        assertEquals(0L, LeitungFrei.waechterNachfristMs(false, 20_000L, 3_000L, 21_000L))
        assertEquals(0L, LeitungFrei.waechterNachfristMs(true, 1_000L, 5_000L, 60_000L))
        // nie mehr als Wartezeit + Öffnen, auch bei verstellter Uhr
        assertEquals(5_000L + LeitungFrei.OEFFNEN_MS, LeitungFrei.waechterNachfristMs(true, 50_000L, 5_000L, 1_000L))
    }

    @Test fun `echte Belegung - Dialog nach rund 10 s`() {
        // Erste Anfrage + drei Wiederholungen, je ~50 ms Antwortzeit: Fehler nach ~9,7 s.
        var t = 50L
        var schon = 0
        while (nach(schon = schon) == Nach.WIEDERHOLEN) { t += LeitungFrei.warteMs(schon)!! + 50L; schon++ }
        assertEquals(Nach.AUFGEBEN, nach(schon = schon))
        assertTrue("Dialog nach $t ms", t in 9_000L..10_500L)
    }

    // German4K 3.2.1 (t-16): die je Host gelernte Freigabe-Pause ist entfernt — kein Lernen, keine Pause mehr.

    // German4K 3.2.1 (t-15): eine Kachel wartet nur auf die Vollbild-Leitung, nicht auf Nachbar-Kacheln.
    @Test fun `offene Stroeme ohne Kacheln`() {
        val u = "http://a.example:8080/live/u/p/1.ts"
        LeitungFrei.auf(u, kachel = true); LeitungFrei.auf(u, kachel = true)
        assertEquals(2, LeitungFrei.offen(u))
        assertEquals(0, LeitungFrei.offen(u, ohneKacheln = true))
        LeitungFrei.auf(u) // Vollbild
        assertEquals(1, LeitungFrei.offen(u, ohneKacheln = true))
        LeitungFrei.zu(u, kachel = true)
        assertEquals(1, LeitungFrei.offen(u, ohneKacheln = true))
        LeitungFrei.zu(u)
        assertEquals(0, LeitungFrei.offen(u, ohneKacheln = true))
        assertEquals(1, LeitungFrei.offen(u))
    }

    // German4K 3.2.1 (t-15): A gewählt, gleich B — ein Start von A, der nach dem Warten aufwacht, gilt nicht mehr.
    @Test fun `Wahl - nur die letzte Wahl gilt`() {
        val w = LeitungFrei.Wahl()
        val a = w.neu()
        assertTrue(w.gilt(a))
        val b = w.neu()
        assertFalse(w.gilt(a))
        assertTrue(w.gilt(b))
    }

    @Test fun `offene Stroeme je Host ueber alle Engines`() {
        val u = "http://a.example:8080/live/u/p/1.ts"
        assertEquals(0, LeitungFrei.offen(u))
        LeitungFrei.auf(u); LeitungFrei.auf("http://a.example:8080/live/u/p/2.ts")
        assertEquals(2, LeitungFrei.offen(u))
        LeitungFrei.zu(u); LeitungFrei.zu(u); LeitungFrei.zu(u) // nie negativ
        assertEquals(0, LeitungFrei.offen(u))
    }
}
