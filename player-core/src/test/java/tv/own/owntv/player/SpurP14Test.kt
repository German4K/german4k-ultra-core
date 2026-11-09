package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** German4K 3.2.1 (p-14, Fund 10): nur eine wirklich gefundene mitgenommene Tonspur schaltet die Titelwahl „Deutsch" ab. */
class SpurP14Test {
    private fun t(id: Int, lang: String?, label: String, selected: Boolean = false) =
        TrackOption(label = label, mpvId = id, selected = selected, lang = lang, typeIndex = id, labelKind = TrackLabelKind.AUDIO)

    @Test fun uebergeben_aber_nicht_gefunden_laesst_titelwahl_zu() {
        val u = LiveSpurUebergabe()
        u.setzen(null, SpurWunsch("fra", false, 7, "Français"))
        val ton = listOf(t(0, null, "Original", selected = true), t(1, null, "Deutsch"))
        assertNull(u.anwenden(emptyList(), ton).second)
        assertFalse(u.tonGetroffen)
    }

    @Test fun gefunden_und_gewaehlt_sperrt_titelwahl() {
        val u = LiveSpurUebergabe()
        u.setzen(null, SpurWunsch("eng", false, 1, "English"))
        val ton = listOf(t(0, "de", "Deutsch", selected = true), t(1, "en", "English"))
        assertEquals(1, u.anwenden(emptyList(), ton).second)
        assertTrue(u.tonGetroffen)
    }

    @Test fun gefunden_und_schon_aktiv_sperrt_titelwahl_ohne_override() {
        // p-13/2: gleiche Spur schon gewählt → kein Override, die Kundenwahl muss trotzdem stehen bleiben.
        val u = LiveSpurUebergabe()
        u.setzen(null, SpurWunsch("eng", false, 0, "English"))
        val ton = listOf(t(0, "en", "English", selected = true), t(1, "de", "Deutsch"))
        assertNull(u.anwenden(emptyList(), ton).second)
        assertTrue(u.tonGetroffen)
    }

    @Test fun naechster_sender_vergisst_den_treffer() {
        val u = LiveSpurUebergabe()
        u.setzen(null, SpurWunsch("eng", false, 1, "English"))
        u.anwenden(emptyList(), listOf(t(0, "de", "Deutsch", selected = true), t(1, "en", "English")))
        u.leeren()
        assertFalse(u.tonGetroffen)
    }
}
