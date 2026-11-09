package tv.own.owntv.core.german4k

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** German4K 3.2.1 (u-8): Sport-Frische (U38), Kontrast (U39), Schalternamen U34–U39. */
class German4kU8Test {

    @After fun zuruecksetzen() { German4kFeatures.set(emptyMap()) }

    private val min = 60_000L

    // ---- U38 Sport-Frische ------------------------------------------------------------------

    @Test fun `noch nie geholt ist faellig`() {
        assertTrue(German4kSportFrische.faellig(null, 1_000L))
    }

    @Test fun `juenger als zehn Minuten ist nicht faellig, ab zehn Minuten schon`() {
        val t0 = 1_000_000_000L
        assertFalse(German4kSportFrische.faellig(t0, t0))
        assertFalse(German4kSportFrische.faellig(t0, t0 + 9 * min + 59_999))
        assertTrue(German4kSportFrische.faellig(t0, t0 + 10 * min))
        assertTrue(German4kSportFrische.faellig(t0, t0 + 7 * 60 * min)) // 7 h alt (Fire TV 03:00, Stand 19:47)
    }

    @Test fun `Uhr zurueckgestellt ist faellig`() {
        assertTrue(German4kSportFrische.faellig(2_000_000L, 1_000_000L))
    }

    @Test fun `hoechstens ein Abruf je zehn Minuten bei Pruefung jede Minute`() {
        // Startseite prüft jede Minute eine Stunde lang: genau 6 Abrufe (0, 10, 20, 30, 40, 50 min).
        var letzter: Long? = null
        var abrufe = 0
        for (m in 0 until 60) {
            val jetzt = 5_000_000L + m * min
            if (German4kSportFrische.faellig(letzter, jetzt)) { abrufe++; letzter = jetzt }
        }
        assertEquals(6, abrufe)
    }

    private fun antwort(stand: String?, serverTime: String) =
        German4kSportAntwort(ok = true, grund = null, stand = stand, serverTime = serverTime, spiele = emptyList())

    @Test fun `abrufZeit liest server_time, kaputt oder leer ist null`() {
        assertEquals(1_761_102_000_000L, German4kSportFrische.abrufZeitMs(antwort(null, "2025-10-22T03:00:00Z")))
        assertEquals(1_761_102_000_123L, German4kSportFrische.abrufZeitMs(antwort(null, "2025-10-22T03:00:00.123Z")))
        assertNull(German4kSportFrische.abrufZeitMs(antwort(null, "")))
        assertNull(German4kSportFrische.abrufZeitMs(antwort(null, "gestern")))
        assertNull(German4kSportFrische.abrufZeitMs(null))
    }

    @Test fun `Stand zeigt mit Schalter die Abrufzeit, ohne Schalter den Spielplan-Stand`() {
        val a = antwort("2025-10-21T17:47:00Z", "2025-10-22T01:00:00Z")
        assertEquals("2025-10-22T01:00:00Z", German4kSportFrische.standIso(a, schalterAn = true))
        assertEquals("2025-10-21T17:47:00Z", German4kSportFrische.standIso(a, schalterAn = false))
        // Server ohne lesbare Zeit → Spielplan-Stand wie bisher.
        assertEquals("2025-10-21T17:47:00Z", German4kSportFrische.standIso(antwort("2025-10-21T17:47:00Z", ""), true))
        assertNull(German4kSportFrische.standIso(null, true))
    }

    @Test fun `Zwischenspeicher behaelt server_time fuer die Drossel`() {
        val a = antwort("2025-10-21T17:47:00Z", "2025-10-22T01:00:00Z")
        val zurueck = German4kSportAntwort.parse(a.toJson())
        assertEquals(German4kSportFrische.abrufZeitMs(a), German4kSportFrische.abrufZeitMs(zurueck))
    }

    // ---- U39 Kontrast -----------------------------------------------------------------------

    @Test fun `Kontrast Weiss auf Schwarz ist 21`() {
        assertEquals(21.0, German4kKontrast.verhaeltnis(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 0.01)
        assertEquals(1.0, German4kKontrast.verhaeltnis(0xFF7F5AF0.toInt(), 0xFF7F5AF0.toInt()), 0.001)
    }

    @Test fun `dunkle Schrift auf mittlerem Lila wird ersetzt und erreicht 4_5`() {
        val lila = 0xFF7C4DFF.toInt() // mittleres Lila wie der Fokus-Chip
        val dunkel = 0xFF381E72.toInt()
        assertTrue(German4kKontrast.verhaeltnis(lila, dunkel) < 4.5)
        val s = German4kKontrast.schrift(lila, dunkel)
        assertTrue(German4kKontrast.verhaeltnis(lila, s) >= 4.5)
    }

    @Test fun `ausreichender Wunsch bleibt`() {
        val dunkelLila = 0xFF4F378B.toInt()
        val hell = 0xFFEADDFF.toInt()
        assertEquals(hell, German4kKontrast.schrift(dunkelLila, hell))
    }

    @Test fun `helles Lila bekommt schwarze Schrift`() {
        val hellLila = 0xFFD0BCFF.toInt()
        assertEquals(0xFF000000.toInt(), German4kKontrast.schrift(hellLila, 0xFFFFFFFF.toInt()))
    }

    @Test fun `Fokusflaeche traegt weisse Schrift mit 4_5 und bleibt dunkler Akzent unveraendert`() {
        val weiss = 0xFFFFFFFF.toInt()
        for (akzent in listOf(0xFFD0BCFF.toInt(), 0xFF7C4DFF.toInt(), 0xFFB388FF.toInt(), 0xFFFFEB3B.toInt(), 0xFFFFFFFF.toInt())) {
            val f = German4kKontrast.flaecheFuerWeiss(akzent)
            assertTrue("%08X".format(akzent), German4kKontrast.verhaeltnis(f, weiss) >= 4.5)
        }
        val dunkel = 0xFF4F378B.toInt()
        assertEquals(dunkel, German4kKontrast.flaecheFuerWeiss(dunkel))
    }

    // ---- Schalternamen ----------------------------------------------------------------------

    @Test fun `u-8 Schalter - Namen fest, fehlend an, aus schaltet ab`() {
        val namen = listOf(
            German4kSchalter.MENUE_DECKEND to "menue_deckend",
            German4kSchalter.MENUE_SYMBOLE to "menue_symbole",
            German4kSchalter.LIVE_VORSCHAU_AUS_TEXT to "live_vorschau_aus_text",
            German4kSchalter.LIVE_SUCHE_LEEREN to "live_suche_leeren",
            German4kSchalter.LIVE_TITEL_UMBRUCH to "live_titel_umbruch",
            German4kSchalter.SPORT_FRISCH to "sport_frisch",
            German4kSchalter.KOPF_KONTRAST to "kopf_kontrast",
        )
        namen.forEach { (k, v) -> assertEquals(v, k) }
        German4kFeatures.set(emptyMap())
        namen.forEach { (k, _) -> assertTrue(German4kFeatures.an(k)) }
        German4kFeatures.set(namen.associate { it.second to "aus" })
        namen.forEach { (k, _) -> assertFalse(German4kFeatures.an(k)) }
    }
}
