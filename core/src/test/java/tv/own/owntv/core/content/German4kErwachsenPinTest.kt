package tv.own.owntv.core.content

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import tv.own.owntv.core.util.Pin

/** German4K 3.3: Erwachsenen-PIN — Erkennung, PIN-Prüfung, Sitzung (4 h), Schalter, Tor. */
class German4kErwachsenPinTest {
    private var jetzt = 1_000L

    @Before fun setUp() {
        German4kErwachsenPin.vergessenFuerTests()
        German4kErwachsenPin.uhr = { jetzt }
        German4kErwachsenPin.schalterAn = { true }
        AdultCategoryClassifier.setzeErwachsenRubriken(emptyList())
    }

    @After fun tearDown() {
        German4kErwachsenPin.vergessenFuerTests()
        German4kErwachsenSitzung.sperren()
    }

    @Test fun `erkennt Erwachsenen-Rubriken am Namen und ueber die Panel-Liste`() {
        assertTrue(German4kErwachsenPin.istErwachsen("Adult Movies"))
        assertTrue(German4kErwachsenPin.istErwachsen("DE | XXX 18+"))
        assertTrue(German4kErwachsenPin.istErwachsen("Für Erwachsene"))
        assertFalse(German4kErwachsenPin.istErwachsen("Adult Swim"))
        assertFalse(German4kErwachsenPin.istErwachsen("Filme 2024"))
        assertFalse(German4kErwachsenPin.istErwachsen(null))
        assertFalse(German4kErwachsenPin.istErwachsen("Nachtprogramm"))
        AdultCategoryClassifier.setzeErwachsenRubriken(listOf("Nachtprogramm"))
        assertTrue(German4kErwachsenPin.istErwachsen("Nachtprogramm"))
    }

    @Test fun `ohne gespeicherten PIN gilt 0000, sonst der Hash`() {
        assertTrue(German4kErwachsenPin.pinStimmt("0000", null))
        assertFalse(German4kErwachsenPin.pinStimmt("1234", null))
        val hash = Pin.hash("4711")
        assertTrue(German4kErwachsenPin.pinStimmt("4711", hash))
        assertFalse(German4kErwachsenPin.pinStimmt("0000", hash))
    }

    @Test fun `PIN muss genau 4 Ziffern haben`() {
        assertTrue(German4kErwachsenPin.pinGueltig("1234"))
        assertTrue(German4kErwachsenPin.pinGueltig("0000"))
        assertFalse(German4kErwachsenPin.pinGueltig("123"))
        assertFalse(German4kErwachsenPin.pinGueltig("12345"))
        assertFalse(German4kErwachsenPin.pinGueltig("12a4"))
    }

    @Test fun `Freigabe haelt 4 Stunden, Sperren und Ablauf schliessen wieder`() {
        assertFalse(German4kErwachsenPin.istFrei())
        assertTrue(German4kErwachsenPin.mussFragen("Adult Movies"))
        German4kErwachsenPin.freigeben()
        assertTrue(German4kErwachsenPin.istFrei())
        assertFalse(German4kErwachsenPin.mussFragen("Adult Movies"))
        assertTrue("PIN gilt auch für die Suche (3.2 F1)", German4kErwachsenSitzung.frei)
        jetzt += German4kErwachsenPin.FREI_MS - 1
        assertTrue(German4kErwachsenPin.istFrei())
        jetzt += 1
        assertFalse(German4kErwachsenPin.istFrei())
        German4kErwachsenPin.freigeben()
        German4kErwachsenPin.sperren()
        assertFalse(German4kErwachsenPin.istFrei())
    }

    @Test fun `Schalter aus fragt nie, normale Rubriken fragen nie`() {
        assertFalse(German4kErwachsenPin.mussFragen("Filme"))
        German4kErwachsenPin.schalterAn = { false }
        assertFalse(German4kErwachsenPin.mussFragen("Adult Movies"))
        assertFalse(German4kErwachsenPin.gesperrt())
    }

    @Test fun `Schalter fehlt gilt als an, nur aus schaltet ab`() {
        assertTrue(German4kErwachsenPin.istGesperrt(null, frei = false))
        assertTrue(German4kErwachsenPin.istGesperrt("an", frei = false))
        assertFalse(German4kErwachsenPin.istGesperrt("aus", frei = false))
        assertFalse(German4kErwachsenPin.istGesperrt(null, frei = true))
    }

    @Test fun `Tor laesst Normales sofort durch und wartet bei Erwachsenem auf die Antwort`() = runBlocking {
        assertTrue(German4kErwachsenPin.durchlassen("Filme"))
        assertNull(German4kErwachsenPin.anfrage.value)

        val erst = async { German4kErwachsenPin.durchlassen("Adult Movies") }
        val zweit = async { German4kErwachsenPin.durchlassen("XXX") }
        yield(); yield()
        assertNotNull(German4kErwachsenPin.anfrage.value)
        German4kErwachsenPin.beantworten(false)
        assertFalse(erst.await())
        assertFalse("gleichzeitige Anfragen teilen sich einen Dialog", zweit.await())
        assertNull(German4kErwachsenPin.anfrage.value)

        val dritt = async { German4kErwachsenPin.durchlassen("Adult Movies") }
        yield()
        German4kErwachsenPin.freigeben()
        German4kErwachsenPin.beantworten(true)
        assertTrue(dritt.await())
        assertTrue(German4kErwachsenPin.durchlassen("Adult Movies"))
        assertEquals(null, German4kErwachsenPin.anfrage.value)
    }

    // --- German4K 3.3.1 (B3) ----------------------------------------------------------------------------------------

    @Test fun `Debug-Dauer gilt nur in Debug-Builds und nur von 1 s bis 4 h`() {
        val voll = German4kErwachsenPin.FREI_MS
        assertEquals(voll, German4kErwachsenPin.freiMs("60", debug = false))
        assertEquals(60_000L, German4kErwachsenPin.freiMs(" 60 ", debug = true))
        assertEquals(voll, German4kErwachsenPin.freiMs("14400", debug = true))
        for (falsch in listOf(null, "", "0", "-5", "abc", "14401")) {
            assertEquals("'$falsch'", voll, German4kErwachsenPin.freiMs(falsch, debug = true))
        }
    }

    @Test fun `verkuerzte Freigabe laeuft nach der Debug-Dauer ab und fragt wieder`() {
        German4kErwachsenPin.freiDauer = { 60_000L }
        German4kErwachsenPin.freigeben()
        jetzt += 59_999L
        assertTrue(German4kErwachsenPin.istFrei())
        jetzt += 1L
        assertFalse(German4kErwachsenPin.istFrei())
        assertTrue(German4kErwachsenPin.mussFragen("Adult Movies"))
    }

    @Test fun `Abbrechen vor dem Abspielen meldet sich, Ordner-Abfrage und richtiger PIN nicht`() = runBlocking {
        val meldungen = mutableListOf<Unit>()
        val wache = launch(start = CoroutineStart.UNDISPATCHED) {
            German4kErwachsenPin.abspielAbbruch.collect { meldungen += it }
        }

        val ordner = async { German4kErwachsenPin.durchlassen("Adult Movies") }
        yield()
        German4kErwachsenPin.beantworten(false)
        assertFalse(ordner.await())
        yield()
        assertEquals("Ordner-Abbruch schließt keinen Player", 0, meldungen.size)

        val spiel = async { German4kErwachsenPin.durchlassenZumAbspielen("Adult Movies") }
        yield()
        German4kErwachsenPin.beantworten(false)
        assertFalse(spiel.await())
        yield()
        assertEquals("Abbruch vor dem Abspielen meldet genau einmal", 1, meldungen.size)

        val richtig = async { German4kErwachsenPin.durchlassenZumAbspielen("Adult Movies") }
        yield()
        German4kErwachsenPin.freigeben()
        German4kErwachsenPin.beantworten(true)
        assertTrue(richtig.await())
        assertTrue(German4kErwachsenPin.durchlassenZumAbspielen("Filme"))
        yield()
        assertEquals(1, meldungen.size)
        wache.cancel()
    }
}
