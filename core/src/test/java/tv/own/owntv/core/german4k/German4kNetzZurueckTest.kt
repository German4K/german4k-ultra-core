package tv.own.owntv.core.german4k

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.german4k.German4kNetzZurueck.Schritt

/** German4K 3.2.1 (q-6): wann nach einer Netzlücke Live von selbst neu startet — und wann nie. */
class German4kNetzZurueckTest {

    @After fun zuruecksetzen() { German4kFeatures.set(emptyMap()) }

    private val a = "http://h/live/u/p/1.ts"
    private val b = "http://h/live/u/p/2.ts"

    @Test fun `Schaltername fest, fehlend an, aus schaltet ab`() {
        assertEquals("netz_zurueck_neustart", German4kSchalter.NETZ_ZURUECK_NEUSTART)
        German4kFeatures.set(emptyMap())
        assertTrue(German4kFeatures.an(German4kSchalter.NETZ_ZURUECK_NEUSTART))
        German4kFeatures.set(mapOf("netz_zurueck_neustart" to "aus"))
        assertFalse(German4kFeatures.an(German4kSchalter.NETZ_ZURUECK_NEUSTART))
    }

    // ---- warten -----------------------------------------------------------------------------

    private fun warten(
        an: Boolean = true, sender: String? = a, online: Boolean = false, belegt: Boolean = false,
        multiview: Boolean = false, sichtbar: Boolean = true, versucht: String? = null,
    ) = German4kNetzZurueck.warten(an, sender, online, belegt, multiview, sichtbar, versucht)

    @Test fun `Live-Fehler ohne Netz wartet auf Netz`() = assertTrue(warten())

    @Test fun `mit validiertem Netz kein Warten - normale Fehleranzeige`() = assertFalse(warten(online = true))

    @Test fun `Schalter aus - nie`() = assertFalse(warten(an = false))

    @Test fun `Leitung belegt 458 460 - nie`() = assertFalse(warten(belegt = true))

    @Test fun `Multiview - nie`() = assertFalse(warten(multiview = true))

    @Test fun `Live nicht zu sehen - nie`() = assertFalse(warten(sichtbar = false))

    @Test fun `kein Sender - nie`() = assertFalse(warten(sender = null))

    @Test fun `schon einmal neu gestartet - keine Schleife`() {
        assertFalse(warten(versucht = a))
        assertTrue("anderer Sender zählt neu", warten(versucht = b))
    }

    // ---- beiNetz ----------------------------------------------------------------------------

    private fun beiNetz(
        an: Boolean = true, gewartet: String? = a, sender: String? = a, fehler: Boolean = true, belegt: Boolean = false,
        multiview: Boolean = false, sichtbar: Boolean = true, vorn: Boolean = true, seit: Long = 0,
    ) = German4kNetzZurueck.beiNetz(an, gewartet, sender, fehler, belegt, multiview, sichtbar, vorn, seit)

    @Test fun `Netz zurück, derselbe Sender im Fehlerbild - Neustart`() = assertEquals(Schritt.NEUSTART, beiNetz())

    @Test fun `Kunde inzwischen auf anderem Sender - kein Neustart`() =
        assertEquals(Schritt.ABBRECHEN, beiNetz(sender = b))

    @Test fun `Player verlassen - kein Neustart`() = assertEquals(Schritt.ABBRECHEN, beiNetz(sender = null))

    @Test fun `anderer Bereich - kein Neustart`() = assertEquals(Schritt.ABBRECHEN, beiNetz(sichtbar = false))

    @Test fun `App im Hintergrund - kein Neustart`() = assertEquals(Schritt.ABBRECHEN, beiNetz(vorn = false))

    @Test fun `Multiview - kein Neustart`() = assertEquals(Schritt.ABBRECHEN, beiNetz(multiview = true))

    @Test fun `inzwischen Leitung belegt - kein Neustart`() = assertEquals(Schritt.ABBRECHEN, beiNetz(belegt = true))

    @Test fun `Schalter inzwischen aus - kein Neustart`() = assertEquals(Schritt.ABBRECHEN, beiNetz(an = false))

    @Test fun `nichts gewartet - nichts tun`() = assertEquals(Schritt.ABBRECHEN, beiNetz(gewartet = null))

    @Test fun `Leiter lädt noch - im Nachlauf warten, danach aufgeben`() {
        assertEquals(Schritt.WARTEN, beiNetz(fehler = false, seit = 0))
        assertEquals(Schritt.WARTEN, beiNetz(fehler = false, seit = German4kNetzZurueck.NACHLAUF_MS - 1))
        assertEquals(Schritt.ABBRECHEN, beiNetz(fehler = false, seit = German4kNetzZurueck.NACHLAUF_MS))
    }

    @Test fun `Fehler im Nachlauf - Neustart`() =
        assertEquals(Schritt.NEUSTART, beiNetz(fehler = true, seit = German4kNetzZurueck.NACHLAUF_MS - 1))

    // ---- mpv „geöffnet“, dann Fehler --------------------------------------------------------

    @Test fun `geöffnet und gleich loading failed ist ein Fehlschlag`() {
        assertTrue(German4kNetzZurueck.oeffnenGescheitert(true, 300))
        assertTrue(German4kNetzZurueck.oeffnenGescheitert(true, German4kNetzZurueck.NACH_OEFFNEN_MS))
    }

    @Test fun `kein Fehler oder später Fehler - geöffnet bleibt geöffnet`() {
        assertFalse(German4kNetzZurueck.oeffnenGescheitert(true, null))
        assertFalse(German4kNetzZurueck.oeffnenGescheitert(true, German4kNetzZurueck.NACH_OEFFNEN_MS + 1))
    }

    @Test fun `Neustart-Verzug - wenige Sekunden, kürzer als der Nachlauf`() {
        assertTrue(German4kNetzZurueck.NEUSTART_VERZUG_MS in 1_000L..12_000L)
        assertTrue(German4kNetzZurueck.NEUSTART_VERZUG_MS < German4kNetzZurueck.NACHLAUF_MS)
    }

    @Test fun `Schalter aus - geöffnet bleibt geöffnet wie bisher`() =
        assertFalse(German4kNetzZurueck.oeffnenGescheitert(false, 300))

    // German4K 3.2.1 (t-10)
    @org.junit.Test fun geoeffnetErstNachFristGelungen() {
        org.junit.Assert.assertTrue(German4kNetzZurueck.sofortGelungen(schalterAn = false))
        org.junit.Assert.assertFalse(German4kNetzZurueck.sofortGelungen(schalterAn = true))
    }

    @org.junit.Test fun weckerNachSpaetemFehlerNurMitSchalterUndFrist() {
        org.junit.Assert.assertTrue(German4kNetzZurueck.weckerNeuSetzen(schalterAn = true, abgelaufen = false))
        org.junit.Assert.assertFalse(German4kNetzZurueck.weckerNeuSetzen(schalterAn = true, abgelaufen = true))
        org.junit.Assert.assertFalse(German4kNetzZurueck.weckerNeuSetzen(schalterAn = false, abgelaufen = false))
    }
}
