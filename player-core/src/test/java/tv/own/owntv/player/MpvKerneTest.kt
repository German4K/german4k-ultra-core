package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** German4K 3.2.1 (P2): HTTP-Grund im Fehlerbild, FFmpeg-Log-Bindung nach A1-Kerntausch. */
class MpvKerneTest {
    @Before fun setUp() = MpvKerne.zuruecksetzenFuerTest()

    @Test fun `erster kern bindet ffmpeg-log`() {
        assertFalse(MpvKerne.geboren())
        assertEquals(1, MpvKerne.anzahl())
    }

    @Test fun `a1 tausch - neuer kern neben altem, neu binden erst wenn alter tot`() {
        MpvKerne.geboren() // alter Kern
        val ohneLog = MpvKerne.geboren() // A1: neuer Kern, alter lebt noch
        assertTrue(ohneLog)
        assertFalse(MpvKerne.neuBindenFaellig(ohneLog, MpvKerne.anzahl())) // alter lebt → noch nicht
        MpvKerne.gestorben() // alter Kern zerstört
        assertTrue(MpvKerne.neuBindenFaellig(ohneLog, MpvKerne.anzahl()))
        MpvKerne.gestorben() // Neubau: erst zerstören …
        assertFalse(MpvKerne.geboren()) // … dann erzeugen → bindet wieder
    }

    @Test fun `gestorben faellt nie unter null`() {
        MpvKerne.gestorben()
        assertEquals(0, MpvKerne.anzahl())
        assertFalse(MpvKerne.neuBindenFaellig(false, 1))
    }

    @Test fun `generisches failed to open verdeckt den 403 nicht mehr`() {
        val http = "ffmpeg: https: HTTP error 403 Forbidden"
        val generisch = "stream: Failed to open https://german4k.tv/live/•••/•••/364.m3u8."
        assertEquals(http, MpvFehlerGrund.fuerAnzeige(generisch, http))
        assertEquals(PlayerFailureReason.HTTP_403, PlayerErrors.reasonFor(MpvFehlerGrund.fuerAnzeige(generisch, http)!!))
    }

    @Test fun `eigene http-zeile und konkreter anderer grund bleiben`() {
        assertEquals("ffmpeg: https: HTTP error 458 ", MpvFehlerGrund.fuerAnzeige("ffmpeg: https: HTTP error 458 ", "ffmpeg: https: HTTP error 403 Forbidden"))
        assertEquals("vd: Codec reported err 0x80001000", MpvFehlerGrund.fuerAnzeige("vd: Codec reported err 0x80001000", "ffmpeg: https: HTTP error 403 Forbidden"))
        assertEquals("ffmpeg: https: HTTP error 404 Not Found", MpvFehlerGrund.fuerAnzeige(null, "ffmpeg: https: HTTP error 404 Not Found"))
        assertNull(MpvFehlerGrund.fuerAnzeige(null, null))
        assertEquals("stream: Failed to open x", MpvFehlerGrund.fuerAnzeige("stream: Failed to open x", null))
    }

    @Test fun `statuscodes 403 404 458 460 511 werden erkannt`() {
        for (c in listOf(403, 404, 458, 460, 511)) {
            assertTrue("$c", MpvFehlerGrund.hatHttpStatus("ffmpeg: https: HTTP error $c x"))
            assertEquals(c, PlayerErrors.httpStatusIn("ffmpeg: https: HTTP error $c x"))
        }
        assertFalse(MpvFehlerGrund.hatHttpStatus("stream: Failed to open https://german4k.tv/live/•••/•••/364.ts"))
    }
}
