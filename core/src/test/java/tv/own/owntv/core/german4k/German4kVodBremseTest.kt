package tv.own.owntv.core.german4k

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** German4K 3.3.1 (B4): Wiederholbremse — 3 Fehlschläge desselben Titels in 2 min → bremsen. */
class German4kVodBremseTest {
    private val b = German4kVodBremse
    private val film = "movie:1434539"

    @Before fun setUp() = b.zuruecksetzen()
    @After fun tearDown() { b.zuruecksetzen(); German4kFeatures.set(emptyMap()) }

    @Test fun `drei Fehlschlaege in zwei Minuten bremsen, zwei nicht`() {
        assertFalse(b.fehlschlag(film, 1_000))
        assertFalse(b.fehlschlag(film, 20_000))
        assertTrue(b.fehlschlag(film, 40_000))
    }

    @Test fun `Fehlschlaege ausserhalb des Fensters verfallen`() {
        b.fehlschlag(film, 0)
        b.fehlschlag(film, 60_000)
        // der erste liegt jetzt genau 2 min zurück → zählt nicht mehr
        assertFalse(b.fehlschlag(film, b.FENSTER_MS))
        assertEquals(2, b.anzahl(film, b.FENSTER_MS))
        assertTrue(b.fehlschlag(film, b.FENSTER_MS + 1_000))
    }

    @Test fun `zaehlt je Titel - ein anderer Film bremst nicht mit`() {
        b.fehlschlag(film, 1_000)
        b.fehlschlag(film, 2_000)
        assertFalse(b.fehlschlag("movie:42", 3_000))
        assertTrue(b.fehlschlag(film, 4_000))
    }

    @Test fun `manueller Neuversuch setzt zurueck`() {
        b.fehlschlag(film, 1_000)
        b.fehlschlag(film, 2_000)
        b.zuruecksetzen()
        assertEquals(0, b.anzahl(film, 3_000))
        assertFalse(b.fehlschlag(film, 3_000))
        assertFalse(b.fehlschlag(film, 4_000))
        assertTrue(b.fehlschlag(film, 5_000))
    }

    @Test fun `Ueberlast-Neuversuch, Leitung belegt und Live zaehlen nicht`() {
        for (code in listOf(502, 503, 504)) assertFalse(b.zaehlt(live = false, httpStatus = code, belegt = false))
        assertFalse(b.zaehlt(live = false, httpStatus = 460, belegt = true))
        assertFalse(b.zaehlt(live = true, httpStatus = null, belegt = false))
        assertFalse(b.zaehlt(live = true, httpStatus = 404, belegt = false))
        assertTrue(b.zaehlt(live = false, httpStatus = null, belegt = false))
        assertTrue(b.zaehlt(live = false, httpStatus = 404, belegt = false))
        assertTrue(b.zaehlt(live = false, httpStatus = 500, belegt = false))
    }

    @Test fun `Schluessel - Inhaltskennung zuerst, sonst URL ohne Host`() {
        assertEquals("movie:7", b.schluessel("movie:7", "https://german4k.tv/movie/u/p/7.mkv"))
        // Multi-DNS: derselbe Film auf dem Ausweich-Host ist derselbe Titel
        assertEquals(
            b.schluessel(null, "https://german4k.tv/movie/u/p/7.mkv"),
            b.schluessel(" ", "http://tv.german4k.tv/movie/u/p/7.mkv"),
        )
    }

    @Test fun `Schalter vod_bremse - fehlt = an, aus = aus`() {
        assertEquals("vod_bremse", German4kSchalter.VOD_BREMSE)
        German4kFeatures.set(emptyMap())
        assertTrue(German4kFeatures.an(German4kSchalter.VOD_BREMSE))
        German4kFeatures.set(mapOf("vod_bremse" to "aus"))
        assertFalse(German4kFeatures.an(German4kSchalter.VOD_BREMSE))
    }
}
