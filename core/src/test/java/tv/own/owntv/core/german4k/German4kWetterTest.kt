package tv.own.owntv.core.german4k

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** German4K 3.2.1 (Q4): Wetter-Ort vom Server statt ipapi.co — Parser und Auswahl. */
class German4kWetterTest {
    @After fun zuruecksetzen() { German4kFeatures.set(emptyMap()); German4kKonfig.set(null) }

    @Test fun `parse liest wetter_ort aus konfig`() {
        val k = German4kKonfigWerte.parse(JSONObject("""{"wetter_ort":{"stadt":" München ","lat":48.14,"lon":11.58}}"""))
        assertEquals(German4kWetterOrt("München", 48.14, 11.58), k.wetterOrt)
    }

    @Test fun `parse ohne Feld oder mit kaputtem Feld ergibt null`() {
        assertNull(German4kKonfigWerte.parse(JSONObject("{}")).wetterOrt)
        assertNull(German4kKonfigWerte.parse(null).wetterOrt)
        assertNull(German4kWetterOrt.parse(JSONObject("""{"stadt":"X","lat":"48","lon":11}""")))
        assertNull(German4kWetterOrt.parse(JSONObject("""{"stadt":"X","lat":91,"lon":11}""")))
        assertNull(German4kWetterOrt.parse(JSONObject("""{"stadt":"X","lat":48}""")))
        assertEquals(German4kWetterOrt("", 52.52, 13.4), German4kWetterOrt.parse(JSONObject("""{"lat":52.52,"lon":13.4}""")))
    }

    @Test fun `toJson und parse ergeben denselben Ort (Cache der Panel-Antwort)`() {
        val k = German4kKonfigWerte(wetterOrt = German4kWetterOrt("Wien", 48.21, 16.37))
        assertEquals(k, German4kKonfigWerte.parse(k.toJson()))
        assertTrue(!German4kKonfigWerte().toJson().has("wetter_ort"))
    }

    @Test fun `eingetragener Ort hat Vorrang`() {
        val ort = German4kWetterOrt("Berlin", 52.52, 13.4)
        assertEquals(German4kWetterQuelle.Eingetragen("Hamburg"), German4kWetterQuelle.waehle(" Hamburg ", ort, true))
        assertEquals(German4kWetterQuelle.Eingetragen("Hamburg"), German4kWetterQuelle.waehle("Hamburg", ort, false))
    }

    @Test fun `ohne Eintrag Server-Ort, ohne Server-Ort keine Quelle - nie IP-Ortung`() {
        val ort = German4kWetterOrt("Berlin", 52.52, 13.4)
        assertEquals(German4kWetterQuelle.Server(ort), German4kWetterQuelle.waehle("", ort, true))
        assertEquals(German4kWetterQuelle.Keine, German4kWetterQuelle.waehle("  ", null, true))
    }

    @Test fun `Schalter aus = altes Verhalten (IP-Ortung)`() {
        assertEquals(German4kWetterQuelle.IpOrtung, German4kWetterQuelle.waehle("", German4kWetterOrt("B", 1.0, 2.0), false))
    }

    @Test fun `aktuell liest Schalter und Konfig - fehlender Schalter = an`() {
        assertEquals("wetter_server_ort", German4kSchalter.WETTER_SERVER_ORT)
        assertEquals(German4kWetterQuelle.Keine, German4kWetterQuelle.aktuell(""))
        German4kKonfig.set(German4kKonfigWerte(wetterOrt = German4kWetterOrt("Graz", 47.07, 15.44)))
        assertEquals(German4kWetterQuelle.Server(German4kWetterOrt("Graz", 47.07, 15.44)), German4kWetterQuelle.aktuell(""))
        German4kFeatures.set(mapOf(German4kSchalter.WETTER_SERVER_ORT to "aus"))
        assertEquals(German4kWetterQuelle.IpOrtung, German4kWetterQuelle.aktuell(""))
    }
}
