package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class German4kAbgelaufenTest {
    private val a = German4kAbgelaufen
    private val min = 60_000L

    @Test fun einzelnerFehlerIstNichtDauerhaft() {
        val s = a.nachFehler(null, 0L)
        assertEquals(1, s.anzahl)
        assertFalse(a.dauerhaft(s))
    }

    @Test fun dreiFehlerSchnellHintereinanderReichenNicht() {
        var s = a.nachFehler(null, 0L)
        s = a.nachFehler(s, min)
        s = a.nachFehler(s, 2 * min)
        assertFalse(a.dauerhaft(s))
    }

    @Test fun dreiFehlerUeberDreissigMinutenSindDauerhaft() {
        var s = a.nachFehler(null, 0L)
        s = a.nachFehler(s, 10 * min)
        s = a.nachFehler(s, 30 * min)
        assertTrue(a.dauerhaft(s))
    }

    @Test fun hauptzugangUndSchalterAus() {
        val s = German4kAbgelaufen.Stand(0L, 40 * min, 5)
        assertTrue(a.gesperrt(s, 7L, verwaltet = setOf(1L), schalterAn = true))
        assertFalse(a.gesperrt(s, 1L, verwaltet = setOf(1L), schalterAn = true))
        assertFalse(a.gesperrt(s, 7L, verwaltet = emptySet(), schalterAn = false))
        assertFalse(a.gesperrt(null, 7L, verwaltet = emptySet(), schalterAn = true))
    }

    @Test fun anmeldeFehlerErkennen() {
        assertTrue(a.istAnmeldeFehler("HTTP 403 Forbidden"))
        assertTrue(a.istAnmeldeFehler("login failed: HTTP 401"))
        assertFalse(a.istAnmeldeFehler("HTTP 503"))
        assertFalse(a.istAnmeldeFehler(null))
    }

    @Test fun kodierenUndLesen() {
        val s = German4kAbgelaufen.Stand(1L, 2L, 3)
        assertEquals(s, a.lesen(a.kodieren(s)))
        assertNull(a.lesen("kaputt"))
        assertNull(a.lesen(null))
    }
}
