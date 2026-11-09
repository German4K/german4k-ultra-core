package tv.own.owntv.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.R
import tv.own.owntv.core.german4k.German4kFeatures
import tv.own.owntv.core.german4k.German4kSchalter
import tv.own.owntv.player.German4kFehlerText.Art

/** German4K 3.2.1 (t-7/1, t-7/2): Kundentext statt Rohtext; „Leitung belegt" ohne Media3-Wiederholungen. */
class German4kFehlerTextTest {
    @After fun zuruecksetzen() { German4kFeatures.set(emptyMap()); German4kFehlerText.technik = false }

    private val codes = setOf(458, 460)

    @Test
    fun `belegte Leitung aus Code, Exo-Zeile, mpv-Zeile und Anbietertext`() {
        assertEquals(Art.LEITUNG_BELEGT, German4kFehlerText.art(460, "max connections", codes))
        assertEquals(Art.LEITUNG_BELEGT, German4kFehlerText.art(null, "ERROR_CODE_IO_BAD_HTTP_STATUS: Response code: 460", codes))
        assertEquals(Art.LEITUNG_BELEGT, German4kFehlerText.art(null, "ffmpeg: http: HTTP error 458 <none>", codes))
        assertEquals(Art.LEITUNG_BELEGT, German4kFehlerText.art(null, "max connections", codes))
        assertEquals(Art.LEITUNG_BELEGT, German4kFehlerText.art(null, "'K Live Belegt': HTTP 460 — Leitung belegt, kein Rückfall", codes))
        // Server-Konfig mit eigenem Code.
        assertEquals(Art.LEITUNG_BELEGT, German4kFehlerText.art(461, null, setOf(461)))
    }

    @Test
    fun `bekannte Codes haben je einen Kundensatz`() {
        assertEquals(Art.ABGELEHNT, German4kFehlerText.art(403, "Forbidden", codes))
        assertEquals(Art.ABGELEHNT, German4kFehlerText.art(401, null, codes))
        assertEquals(Art.NICHT_GEFUNDEN, German4kFehlerText.art(null, "Response code: 404", codes))
        assertEquals(Art.LAND, German4kFehlerText.art(456, null, codes))
        assertEquals(Art.NETZ_ANMELDUNG, German4kFehlerText.art(null, "HTTP error 511", codes))
        assertEquals(Art.AUSGELASTET, German4kFehlerText.art(509, null, codes))
        assertEquals(Art.AUSGELASTET, German4kFehlerText.art(null, "HTTP error 502 Bad Gateway", codes))
        assertEquals(Art.ZEITUEBERSCHREITUNG, German4kFehlerText.art(null, "no picture within 30s of tuning", codes))
        assertEquals(Art.ZEITUEBERSCHREITUNG, German4kFehlerText.art(null, "java.net.SocketTimeoutException: timeout", codes))
        assertEquals(Art.FORMAT, German4kFehlerText.art(null, "ffmpeg: Invalid data found when processing input", codes))
        assertEquals(Art.UNBEKANNT, German4kFehlerText.art(null, "something odd", codes))
        assertEquals(Art.UNBEKANNT, German4kFehlerText.art(null, null, codes))
    }

    @Test
    fun `eine Engine mit eigener Diagnose behaelt ihren Satz`() {
        assertEquals(Art.UNBEKANNT, German4kFehlerText.artFuer(PlaybackFailure.DecoderExhausted, "HTTP 460", codes))
        assertEquals(Art.UNBEKANNT, German4kFehlerText.artFuer(PlaybackFailure.Surround, "timeout", codes))
        assertEquals(Art.LEITUNG_BELEGT, German4kFehlerText.artFuer(PlaybackFailure.Channel, "Live: HTTP 460 — Leitung belegt", codes))
        assertEquals(Art.LEITUNG_BELEGT, German4kFehlerText.artFuer(PlaybackFailure.Raw("max connections", 460), null, codes))
        // Raw ohne Code, die Technikzeile kennt ihn.
        assertEquals(Art.LEITUNG_BELEGT, German4kFehlerText.artFuer(PlaybackFailure.Raw("Sorry, try later"), "Response code: 460", codes))
    }

    @Test
    fun `roher Anbietertext wird nie angezeigt`() {
        val resolve: (Int, List<Any>) -> String = { id, _ -> "res:$id" }
        assertEquals("res:${R.string.t7_321_leitung_belegt}", PlaybackFailure.Raw("max connections", 460).describe(resolve))
        assertEquals("res:${R.string.player_error_channel}", PlaybackFailure.Raw("Channel limit reached, call 0800").describe(resolve))
        // Schalter aus = 3.2-Verhalten (Rohtext).
        German4kFeatures.set(mapOf(German4kSchalter.FEHLER_KUNDENTEXT to "aus"))
        assertEquals("max connections", PlaybackFailure.Raw("max connections", 460).describe(resolve))
    }

    @Test
    fun `Technikzeile nur bei Detail-Protokoll oder Schalter aus`() {
        assertFalse(German4kFehlerText.technikZeigen(an = true))
        assertTrue(German4kFehlerText.technikZeigen(an = false))
        German4kFehlerText.technik = true
        assertTrue(German4kFehlerText.technikZeigen(an = true))
    }

    @Test
    fun `Ursachenzeile nur wo verstaendlich`() {
        assertFalse(German4kFehlerText.grundZeigen(PlayerFailureReason.HTTP_403, Art.UNBEKANNT))
        assertFalse(German4kFehlerText.grundZeigen(PlayerFailureReason.LIVE_NO_FALLBACK, Art.UNBEKANNT))
        assertFalse(German4kFehlerText.grundZeigen(PlayerFailureReason.DECODER_MEMORY, Art.LEITUNG_BELEGT))
        assertTrue(German4kFehlerText.grundZeigen(PlayerFailureReason.DECODER_MEMORY, Art.UNBEKANNT))
    }

    @Test
    fun `visibleFailure gibt den Status mit`() {
        LiveStreamQuirks.rememberProviderMessage("http://h.example/live/a/b/1.ts", 460, "max connections reached")
        val f = PlayerErrors.visibleFailure("Response code: 460", "http://h.example/live/a/b/1.ts", PlaybackFailure.Channel)
        assertEquals(PlaybackFailure.Raw("max connections reached", 460), f)
    }

    @Test
    fun `Leitung belegt ist fuer Media3 sofort endgueltig`() {
        assertTrue(LivePreviewEngine.leitungSofortFatal(460, schalterAn = true))
        assertTrue(LivePreviewEngine.leitungSofortFatal(458, schalterAn = true))
        assertFalse(LivePreviewEngine.leitungSofortFatal(460, schalterAn = false))
        assertFalse(LivePreviewEngine.leitungSofortFatal(503, schalterAn = true))
        assertFalse(LivePreviewEngine.leitungSofortFatal(null, schalterAn = true))
        // `leitung_live` aus → Live kennt „belegt" nicht → alte Media3-Wiederholungen.
        German4kFeatures.set(mapOf(German4kSchalter.LEITUNG_LIVE to "aus"))
        assertFalse(LivePreviewEngine.leitungSofortFatal(460, schalterAn = true))
    }

    // German4K 3.2.1 (p-20): VOD-460 → Rohfehler mit Code; nach BACK aus dem Störungsdialog „anderes Gerät", nicht „offline".
    @Test
    fun `belegte VOD-Leitung wird nie zu Quelle offline`() {
        val mitCode = PlaybackFailure.Raw(German4kLeitung.grund(460), 460)
        assertEquals(R.string.t7_321_leitung_belegt, German4kFehlerText.rohText(mitCode, codes))
        assertEquals(Art.LEITUNG_BELEGT, German4kFehlerText.artFuer(mitCode, null, codes))
        val ohneCode = PlaybackFailure.Raw(German4kLeitung.grund(null), null)
        assertEquals(R.string.t7_321_leitung_belegt, German4kFehlerText.rohText(ohneCode, codes))
    }
}
