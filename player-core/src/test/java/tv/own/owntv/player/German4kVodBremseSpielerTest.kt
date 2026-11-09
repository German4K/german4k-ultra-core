package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.german4k.German4kHostFailover

/** German4K 3.3.1 (B4): Fehlergrund beim Neuversuch (Host-Frage) und Bremse neben dem Überlast-Neuversuch. */
class German4kVodBremseSpielerTest {

    private fun grund(raw: String? = null, mpv: String? = null, http: String? = null, f: PlaybackFailure? = null) =
        OwnTVPlayer.g4kRetryGrund(raw, mpv, http, f)

    @Test fun `Feldfall Amin - Decoder und mkv-Seek sind kein Host-Fehler`() {
        val g = grund(raw = "hardware decoder produced no usable video on ExoPlayer", mpv = "mkv: Failed to seek when reading header element")
        assertFalse(German4kHostFailover.looksLikeHostFailure(g))
    }

    @Test fun `eigener Code des Fehlers geht vor - 404 bleibt 404, auch neben einem Timeout-Text`() {
        val g = grund(mpv = "tcp: Connection timed out", f = PlaybackFailure.Raw("Not Found", httpCode = 404))
        assertFalse(German4kHostFailover.looksLikeHostFailure(g))
    }

    @Test fun `Host-Fehler werden erkannt - Timeout, 503, Exo-Timeout-Code`() {
        assertTrue(German4kHostFailover.looksLikeHostFailure(grund(mpv = "tcp: Connection timed out")))
        assertTrue(German4kHostFailover.looksLikeHostFailure(grund(f = PlaybackFailure.Raw("Service Unavailable", httpCode = 503))))
        assertTrue(German4kHostFailover.looksLikeHostFailure(grund(f = PlaybackFailure.ExoPlay("ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT"))))
    }

    @Test fun `nichts bekannt - kein Grund, also keine Abstufung`() {
        assertNull(grund())
        assertNull(grund(raw = " ", mpv = ""))
        assertFalse(German4kHostFailover.looksLikeHostFailure(grund()))
    }

    @Test fun `Bremse loest keinen Ueberlast-Neuversuch aus, auch mit 503 in der Rohzeile`() {
        assertFalse(German4kUeberlast.istUeberlast(PlaybackFailure.VodBremse, "http: HTTP error 503 Service Unavailable", live = false))
        // der normale Überlastfall bleibt
        assertTrue(German4kUeberlast.istUeberlast(PlaybackFailure.BothEnginesExoFirst, "http: HTTP error 503 Service Unavailable", live = false))
    }

    @Test fun `Bremse ist kein Leitung-belegt-Fehlerbild`() {
        assertEquals(German4kFehlerText.Art.UNBEKANNT, German4kFehlerText.artFuer(PlaybackFailure.VodBremse, "HTTP error 460", leitungCodes = setOf(460)))
    }
}
