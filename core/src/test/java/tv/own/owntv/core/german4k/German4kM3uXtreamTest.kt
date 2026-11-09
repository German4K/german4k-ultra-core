package tv.own.owntv.core.german4k

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** German4K 3.3 (m3u_als_xtream, Kunde): unser M3U-Link → Xtream-Zugang, alles andere bleibt M3U. */
class German4kM3uXtreamTest {

    @After fun zuruecksetzen() { German4kFeatures.set(emptyMap()) }

    private val keinPanel: (String) -> Boolean = { false }
    private fun z(url: String?) = German4kM3uXtream.umwandeln(url, keinPanel)

    @Test fun `muster12 - https german4k tv mit kind live`() {
        assertEquals(
            German4kM3uXtream.Zugang("https://german4k.tv", "muster12", "geheim"),
            z("https://german4k.tv/get.php?username=muster12&password=geheim&type=m3u_plus&output=ts&kind=live"),
        )
    }

    @Test fun `ohne kind und ohne type`() {
        assertEquals(German4kM3uXtream.Zugang("https://german4k.tv", "u", "p"), z("https://german4k.tv/get.php?username=u&password=p"))
        // Reihenfolge der Parameter egal
        assertEquals(German4kM3uXtream.Zugang("https://german4k.tv", "u", "p"), z("https://german4k.tv/get.php?password=p&type=m3u_plus&username=u"))
    }

    @Test fun `http und Port bleiben wie im Link`() {
        assertEquals(German4kM3uXtream.Zugang("http://tv.german4k.tv", "u", "p"), z("http://tv.german4k.tv/get.php?username=u&password=p&type=m3u_plus&output=ts"))
        assertEquals(German4kM3uXtream.Zugang("http://tv.german4k.tv:8080", "u", "p"), z("http://tv.german4k.tv:8080/get.php?username=u&password=p"))
        assertEquals(German4kM3uXtream.Zugang("https://german4k.tv:443", "u", "p"), z("https://german4k.tv:443/get.php?username=u&password=p"))
        assertEquals(German4kM3uXtream.Zugang("http://cf.german4k.xyz:80", "u", "p"), z("http://cf.german4k.xyz:80/get.php?username=u&password=p&type=m3u_plus"))
    }

    @Test fun `alle unsere Hosts`() {
        for (host in listOf(
            "german4k.tv", "www.german4k.tv", "tv.german4k.tv", "cdn2.german4k.tv", "apps.german4k.tv",
            "cf.german4k.xyz", "alt.german4k.xyz", "apps.german4k.xyz", "german4k.xyz", "german4k.com", "www.german4k.com",
        )) {
            assertEquals(host, "https://$host", z("https://$host/get.php?username=u&password=p")?.server)
        }
        // Gross geschrieben / mit Punkt am Ende → normalisiert
        assertEquals("https://german4k.tv", z("HTTPS://German4K.TV/get.php?username=u&password=p")?.server)
        assertTrue(German4kM3uXtream.unserHost("german4k.tv."))
    }

    @Test fun `Zugangsdaten werden dekodiert, Passwort nicht getrimmt`() {
        // encodeURIComponent (lib/tv/links.ts): %20 und %2B
        assertEquals("p+w x", z("https://german4k.tv/get.php?username=u&password=p%2Bw%20x")?.password)
        // URLSearchParams (lib/panel/m3u.ts): Leerzeichen als +
        assertEquals("a b", z("https://german4k.tv/get.php?username=u&password=a+b")?.password)
        assertEquals(" p ", z("https://german4k.tv/get.php?username=%20u%20&password=%20p%20")?.password)
        assertEquals("u", z("https://german4k.tv/get.php?username=%20u%20&password=%20p%20")?.username)
        assertEquals("Kunde1", z("https://german4k.tv/get.php?USERNAME=Kunde1&Password=x")?.username)
    }

    @Test fun `fehlendes Passwort oder fehlender Benutzer bleibt M3U`() {
        assertNull(z("https://german4k.tv/get.php?username=u&type=m3u_plus&output=ts"))
        assertNull(z("https://german4k.tv/get.php?username=u&password=&type=m3u_plus"))
        assertNull(z("https://german4k.tv/get.php?password=p"))
        assertNull(z("https://german4k.tv/get.php?username=%20%20&password=p"))
        assertNull(z("https://german4k.tv/a7f3k9.m3u")) // Token-Link: nichts herauszulesen
        assertNull(z("https://german4k.tv/get.php"))
    }

    @Test fun `fremder Host bleibt M3U`() {
        assertNull(z("http://example.com/get.php?username=u&password=p"))
        assertNull(z("http://german4k.net/get.php?username=u&password=p")) // Nachahmer
        assertNull(z("http://germany4k.com/get.php?username=u&password=p")) // Nachahmer
        assertNull(z("http://evilgerman4k.tv/get.php?username=u&password=p"))
        assertNull(z("http://german4k.tv.evil.example/get.php?username=u&password=p"))
        assertNull(z("http://shop.german4k.com/get.php?username=u&password=p"))
        assertFalse(German4kM3uXtream.unserHost("notgerman4k.tv"))
    }

    @Test fun `vom Panel gemeldeter Host zaehlt als unser`() {
        val panel: (String) -> Boolean = { it == "http://ausweich.example:8000" }
        assertEquals(
            German4kM3uXtream.Zugang("http://ausweich.example:8000", "u", "p"),
            German4kM3uXtream.umwandeln("http://ausweich.example:8000/get.php?username=u&password=p", panel),
        )
        assertNull(German4kM3uXtream.umwandeln("http://anders.example/get.php?username=u&password=p", panel))
    }

    @Test fun `kein http-Link bleibt M3U`() {
        assertNull(z(null))
        assertNull(z(""))
        assertNull(z("   "))
        assertNull(z("/storage/emulated/0/Download/liste.m3u"))
        assertNull(z("file:///sdcard/german4k.m3u?username=u&password=p"))
        assertNull(z("rtmp://german4k.tv/get.php?username=u&password=p"))
        assertNull(z("german4k.tv/get.php?username=u&password=p")) // ohne Schema: wie bisher
        assertNull(z("https://german4k.tv/get.php?username=u u&password=p")) // unlesbar → nicht raten
    }

    @Test fun `Schalter aus - altes Verhalten`() {
        val link = "https://german4k.tv/get.php?username=u&password=p&type=m3u_plus&output=ts&kind=live"
        German4kFeatures.set(emptyMap())
        assertEquals("u", German4kM3uXtream.alsXtream(link, keinPanel)?.username) // fehlt = an
        German4kFeatures.set(mapOf(German4kSchalter.M3U_ALS_XTREAM to "an"))
        assertEquals("u", German4kM3uXtream.alsXtream(link, keinPanel)?.username)
        German4kFeatures.set(mapOf(German4kSchalter.M3U_ALS_XTREAM to "aus"))
        assertNull(German4kM3uXtream.alsXtream(link, keinPanel))
        assertEquals("m3u_als_xtream", German4kSchalter.M3U_ALS_XTREAM) // Server ULTRA_FEATURES kennt genau diesen Namen
    }

    @Test fun `Passwort nie in toString`() {
        val zugang = z("https://german4k.tv/get.php?username=u&password=GEHEIM")!!
        assertFalse(zugang.toString().contains("GEHEIM"))
    }

    @Test fun `Standard-Pruefung des Panels ohne registrierte Hosts`() {
        German4kHostFailover.clear()
        assertNull(German4kM3uXtream.umwandeln("http://ausweich.example/get.php?username=u&password=p"))
        assertEquals("https://german4k.tv", German4kM3uXtream.umwandeln("https://german4k.tv/get.php?username=u&password=p")?.server)
    }
}
