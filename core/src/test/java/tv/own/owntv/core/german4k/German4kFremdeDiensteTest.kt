package tv.own.owntv.core.german4k

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** German4K 3.2.1 (Q1): fremde Dienste zu, Bremse gegen Wiederholungen bei 4xx. */
class German4kFremdeDiensteTest {

    @After fun zuruecksetzen() { German4kFeatures.set(emptyMap()) }

    @Test fun `Schaltername fest`() {
        assertEquals("fremde_dienste_aus", German4kSchalter.FREMDE_DIENSTE_AUS)
        assertEquals("tv_startseite_nur_tv", German4kSchalter.TV_STARTSEITE_NUR_TV)
    }

    @Test fun `fehlender Schalter - fremde Wege zu`() {
        assertTrue(German4kFremdeDienste.zu())
        assertFalse(German4kFremdeDienste.tmdbStandardWegErlaubt())
        assertFalse(German4kFremdeDienste.githubUpdateErlaubt())
        assertFalse(German4kFremdeDienste.openSubtitlesErlaubt("", ""))
    }

    @Test fun `OpenSubtitles mit eigenem Schluessel oder Server bleibt erlaubt`() {
        assertTrue(German4kFremdeDienste.openSubtitlesErlaubt("", "meinschluessel"))
        assertTrue(German4kFremdeDienste.openSubtitlesErlaubt("https://eigen.example", ""))
    }

    @Test fun `aus - altes Verhalten`() {
        German4kFeatures.set(mapOf(German4kSchalter.FREMDE_DIENSTE_AUS to "aus"))
        assertFalse(German4kFremdeDienste.zu())
        assertTrue(German4kFremdeDienste.tmdbStandardWegErlaubt())
        assertTrue(German4kFremdeDienste.githubUpdateErlaubt())
        assertTrue(German4kFremdeDienste.openSubtitlesErlaubt("", ""))
    }

    // ---- Bremse --------------------------------------------------------------------------------

    private var jetzt = 1_000_000L
    private val bremse = German4kHostBremse(uhr = { jetzt })
    private val a = "https://api.themoviedb.org/3/search/movie?query=a"
    private val b = "https://api.themoviedb.org/3/movie/1"
    private val anders = "https://german4k.tv/player_api.php"

    @Test fun `403 sperrt den Host 30 Minuten, andere Hosts frei`() {
        bremse.fehler(a, 403)
        assertFalse(bremse.darf(a))
        assertFalse(bremse.darf(b))
        assertTrue(bremse.darf(anders))
        jetzt += 30 * 60_000L - 1
        assertFalse(bremse.darf(b))
        jetzt += 2
        assertTrue(bremse.darf(b))
    }

    @Test fun `401 sperrt wie 403`() {
        bremse.fehler(a, 401)
        assertFalse(bremse.darf(b))
    }

    @Test fun `404 sperrt nur die Adresse`() {
        bremse.fehler(a, 404)
        assertFalse(bremse.darf(a))
        assertTrue(bremse.darf(b))
        jetzt += 10 * 60_000L + 1
        assertTrue(bremse.darf(a))
    }

    @Test fun `429 und 5xx - wachsende Pause, Erfolg setzt zurueck`() {
        bremse.fehler(a, 429)
        assertFalse(bremse.darf(b))
        jetzt += 60_001
        assertTrue(bremse.darf(b))
        bremse.fehler(a, 503)
        jetzt += 60_001
        assertFalse(bremse.darf(b)) // zweiter Fehler: 2 min
        jetzt += 60_000
        assertTrue(bremse.darf(b))
        bremse.erfolg(b)
        bremse.fehler(a, 500)
        jetzt += 60_001
        assertTrue(bremse.darf(b)) // nach Erfolg wieder 1 min
    }

    @Test fun `Pause gedeckelt bei 30 Minuten`() {
        repeat(20) { bremse.fehler(a, 500) }
        jetzt += 30 * 60_000L + 1
        assertTrue(bremse.darf(a))
    }

    @Test fun `andere Codes bremsen nicht`() {
        bremse.fehler(a, 400)
        bremse.fehler(a, 302)
        assertTrue(bremse.darf(a))
    }

    @Test fun `Host aus Adresse`() {
        assertEquals("api.themoviedb.org", German4kHostBremse.host(a))
        assertEquals("host:8080", German4kHostBremse.host("http://HOST:8080"))
    }
}
