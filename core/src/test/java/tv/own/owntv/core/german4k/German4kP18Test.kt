package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Locale

/** German4K 3.2.1 (p-18): Befunde der Fire-TV-Abnahme von Kandidat 12. */
class German4kP18Test {
    // --- P18-2 Multiview-Migration ---
    @Test fun `update von 3_2 mit gespeichertem aus - einmal an`() =
        assertEquals(MultiviewMigration.Schritt(schreibeAn = true, merke = true, merkeErstenStart = true), MultiviewMigration.schritt(false, false, true, true))

    @Test fun `nie beruehrt - nur Merker, Standard bleibt an`() =
        assertEquals(MultiviewMigration.Schritt(schreibeAn = false, merke = true, merkeErstenStart = true), MultiviewMigration.schritt(null, false, true, true))

    @Test fun `schon migriert - spaeteres Ausschalten bleibt`() =
        assertEquals(MultiviewMigration.Schritt(schreibeAn = false, merke = false), MultiviewMigration.schritt(false, true, true, true))

    @Test fun `Schalter aus - nichts anfassen, nur der Merker 3_2_1 gesehen`() {
        // German4K 3.2.1 (t-15): „gesehen“ wird beim ersten Start immer gemerkt (mit „nicht erlaubt“).
        assertEquals(MultiviewMigration.Schritt(false, false, false), MultiviewMigration.schritt(false, false, true, false))
        assertEquals(MultiviewMigration.Schritt(false, false, false), MultiviewMigration.schritt(false, false, false, true))
    }

    // German4K 3.2.1 (t-15, Review K13): Migration beim ersten Start aus, später an.
    @Test fun `spaeter an - wer in 3_2_1 bewusst ausgeschaltet hat, bleibt aus`() {
        assertEquals(
            MultiviewMigration.Schritt(false, false, null),
            MultiviewMigration.schritt(false, false, true, true, ersterStartErlaubt = false, geaendert = true),
        )
    }

    @Test fun `spaeter an - ohne eigene Aenderung seitdem wird umgestellt`() {
        assertEquals(
            MultiviewMigration.Schritt(true, true, null),
            MultiviewMigration.schritt(false, false, true, true, ersterStartErlaubt = false, geaendert = false),
        )
    }

    // German4K 3.2.1 (t-16): erster Start offline (Merker leer), Kunde schaltet selbst um → nur Merker, nicht einschalten.
    @Test fun `Merker leer und selbst umgeschaltet - nur Merker, kein Einschalten, auch spaeter nicht`() {
        val erst = MultiviewMigration.schritt(false, false, true, true, ersterStartErlaubt = null, geaendert = true)
        assertEquals(MultiviewMigration.Schritt(false, false, false), erst)
        assertEquals(
            MultiviewMigration.Schritt(false, false, null),
            MultiviewMigration.schritt(false, false, true, true, ersterStartErlaubt = erst.merkeErstenStart, geaendert = true),
        )
        // ohne eigene Änderung bleibt der erste Start wie bisher
        assertEquals(MultiviewMigration.Schritt(true, true, true), MultiviewMigration.schritt(false, false, true, true, null, false))
    }

    @Test fun `beim ersten Start erlaubt - Aenderung danach zaehlt nicht mehr (schon migriert)`() {
        assertEquals(MultiviewMigration.Schritt(false, false, null), MultiviewMigration.schritt(false, true, true, true, true, true))
        // erster Start erlaubt, Migration aber noch nicht gelaufen (z. B. Absturz): läuft, Merker steht schon
        assertEquals(MultiviewMigration.Schritt(true, true, null), MultiviewMigration.schritt(false, false, true, true, true, true))
    }

    // --- P18-6 Hinweis „Später" ---
    @Test fun `spaeter merkt Ablauf-Hinweis fuer heute`() {
        assertTrue(HinweisSpaeter.merken("verlaengern", true))
        assertTrue(HinweisSpaeter.merken("info", true))
        assertTrue(HinweisSpaeter.merken("test", true))
    }

    @Test fun `wartung und abgelaufen nie, Schalter aus nie`() {
        assertFalse(HinweisSpaeter.merken("wartung", true))
        assertFalse(HinweisSpaeter.merken("abgelaufen", true))
        assertFalse(HinweisSpaeter.merken("verlaengern", false))
    }

    // --- P18-5 Datum im Text ---
    @Test fun `iso im Satz wird Landesform`() =
        assertEquals(
            "Dein Zugang läuft am 27.10.2026 ab.",
            German4kDatum.imText("Dein Zugang läuft am 2026-10-27 ab.", Locale.GERMANY),
        )

    @Test fun `mehrere, ungueltige und keine Daten`() {
        assertEquals("vom 01.03.2027 bis 02.03.2027", German4kDatum.imText("vom 2027-03-01 bis 2027-03-02", Locale.GERMANY))
        assertEquals("Code 2026-13-40 bleibt", German4kDatum.imText("Code 2026-13-40 bleibt", Locale.GERMANY))
        assertEquals("Nummer 12026-10-27", German4kDatum.imText("Nummer 12026-10-27", Locale.GERMANY))
        assertEquals("ohne Datum", German4kDatum.imText("ohne Datum", Locale.GERMANY))
    }

    // --- P18-4 Fußball Rück-Fokus ---
    private fun spiel(id: Long, start: String = "2026-10-23T18:30:00Z") =
        German4kSpiel(id, "t$id", "h", "g", null, Instant.parse(start), "", "geplant", 0.0, "spiel", emptyList(), false, null, null)

    @Test fun `konferenz, live-reihe und heute-raster`() {
        val r = SportRegale(
            konferenzen = listOf(spiel(100)),
            live = listOf(spiel(1), spiel(2)),
            heute = (10L..15L).map { spiel(it) },
        )
        assertEquals(SportRueckFokus.Ziel(0, 0), SportRueckFokus.ziel(r, false, 100))
        assertEquals(SportRueckFokus.Ziel(1, 1), SportRueckFokus.ziel(r, false, 2))
        // 0 Konferenz, 1 Live, 2 Titel Heute, 3 Zeile 10..13, 4 Zeile 14..15
        assertEquals(SportRueckFokus.Ziel(3, 0), SportRueckFokus.ziel(r, false, 10))
        assertEquals(SportRueckFokus.Ziel(4, 1), SportRueckFokus.ziel(r, false, 15))
        assertNull(SportRueckFokus.ziel(r, false, 999))
    }

    @Test fun `spaeter nach Tagen, zugeklappt nicht in der Liste`() {
        val r = SportRegale(
            heute = listOf(spiel(1)),
            spaeter = listOf(spiel(20, "2026-10-24T13:30:00Z"), spiel(21, "2026-10-25T13:30:00Z")),
        )
        assertNull(SportRueckFokus.ziel(r, false, 21))
        assertTrue(SportRueckFokus.inSpaeter(r, 21))
        // 0 Titel Heute, 1 Zeile, 2 Kopf später, 3 Tag 24., 4 Zeile, 5 Tag 25., 6 Zeile
        assertEquals(SportRueckFokus.Ziel(6, 0), SportRueckFokus.ziel(r, true, 21))
        assertFalse(SportRueckFokus.inSpaeter(r, 1))
    }
}
