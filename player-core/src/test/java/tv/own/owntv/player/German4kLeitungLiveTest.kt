package tv.own.owntv.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.german4k.German4kFeatures
import tv.own.owntv.core.german4k.German4kHealth
import tv.own.owntv.core.german4k.German4kSchalter

/** German4K 3.2.1 (p-10, D): Live erkennt „Leitung belegt" — Exo-Zeile, mpv-Zeile, Schalter `leitung_live`. */
class German4kLeitungLiveTest {
    @After fun zuruecksetzen() = German4kFeatures.set(emptyMap())

    // Wörtlich aus der Fire-TV-Abnahme (22.10., 07:02:46) bzw. dem mpv/FFmpeg-Log.
    private val exoZeile = "ExoPlayer error before first frame: ERROR_CODE_IO_BAD_HTTP_STATUS: Response code: 460"
    private val mpvZeile = "ffmpeg: http: HTTP error 460 <none>"

    @Test
    fun `live erkennt 460 und 458 aus Exo und mpv`() {
        assertTrue(German4kLeitung.liveText(exoZeile))
        assertTrue(German4kLeitung.liveText(mpvZeile))
        assertTrue(German4kLeitung.liveText("mpv: ${German4kLeitung.grund(458)} (Leitung belegt)"))
        assertEquals(460, German4kLeitung.status(exoZeile))
        assertEquals(460, German4kLeitung.status(mpvZeile))
        assertNull(German4kLeitung.status("no video frame rendered (audio plays, no picture)"))
        assertFalse(German4kLeitung.liveText("ExoPlayer error before first frame: ERROR_CODE_IO_BAD_HTTP_STATUS: Response code: 404"))
        assertFalse(German4kLeitung.liveText("no decodable audio track"))
    }

    @Test
    fun `der gemeldete Grund wird als Leitung belegt eingeordnet`() {
        val detail = "${German4kLeitung.grund(German4kLeitung.status(exoZeile))} — Leitung belegt, kein Rückfall"
        assertEquals(German4kHealth.Klasse.LEITUNG_BELEGT, German4kHealth.klassifiziere(detail))
    }

    @Test
    fun `460 ist eine Abweisung der Anfrage - kein Formatfehler, keine andere Qualitaetsstufe`() {
        assertTrue(LiveStreamQuirks.isSessionLimit(460))
        assertTrue(LiveStreamQuirks.isRequestRefusal(460))
        assertEquals(OwnTVPlayer.HttpRefusal.BUSY, OwnTVPlayer.httpRefusalKind(mpvZeile))
    }

    @Test
    fun `Schalter aus - altes Verhalten`() {
        German4kFeatures.set(mapOf(German4kSchalter.LEITUNG_LIVE to "aus"))
        assertFalse(German4kLeitung.liveText(exoZeile))
        assertFalse(LiveStreamQuirks.isSessionLimit(460))
        assertTrue(LiveStreamQuirks.isSessionLimit(458)) // 458 war schon immer Sitzungsgrenze
        assertEquals(OwnTVPlayer.HttpRefusal.HARD, OwnTVPlayer.httpRefusalKind(mpvZeile))
        // VOD bleibt unberührt.
        assertTrue(German4kLeitung.belegtText(mpvZeile))
    }
}
