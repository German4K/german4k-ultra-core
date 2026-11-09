package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** German4K 3.2.1 (P1): keine Zugangsdaten aus mpv-Logzeilen ins Fehlerbild/Protokoll/Diagnose. */
class MpvLogMaskeTest {
    private val user = "a1b2c3d4e5"
    private val pass = "geheim1234"

    private fun frei(text: String) {
        assertFalse(text, text.contains(user))
        assertFalse(text, text.contains(pass))
    }

    @Test fun `opening done mit https-url`() {
        val m = MpvLogMaske.maskiere("cplayer: Opening done: https://german4k.tv/live/$user/$pass/364.m3u8")
        frei(m)
        assertEquals("cplayer: Opening done: https://german4k.tv/live/•••/•••/364.m3u8", m)
    }

    @Test fun `stream failed to open bleibt lesbar`() {
        val m = MpvLogMaske.maskiere("stream: Failed to open https://german4k.tv/live/$user/$pass/364.ts.")
        assertEquals("stream: Failed to open https://german4k.tv/live/•••/•••/364.ts.", m)
    }

    @Test fun `hostloser pfad und hls-segment`() {
        frei(MpvLogMaske.maskiere("ffmpeg/demuxer: hls: Opening '/live/$user/$pass/364_17.ts' for reading"))
        frei(MpvLogMaske.maskiere("Opening /movie/$user/$pass/123.mkv"))
        frei(MpvLogMaske.maskiere("seek /series/$user/$pass/9.mp4"))
        frei(MpvLogMaske.maskiere("archive /timeshift/$user/$pass/120/2026-10-21:20-00/364.ts"))
    }

    @Test fun `query-parameter und userinfo`() {
        frei(MpvLogMaske.maskiere("http://h:8080/get.php?username=$user&password=$pass&type=m3u"))
        frei(MpvLogMaske.maskiere("http://h/timeshift.php?username=$user&password=$pass&stream=1"))
        frei(MpvLogMaske.maskiere("proxy http://$user:$pass@proxy.example:3128"))
    }

    @Test fun `idempotent und harmlose zeilen unveraendert`() {
        val einmal = MpvLogMaske.maskiere("Opening done: https://german4k.tv/live/$user/$pass/364.ts")
        assertEquals(einmal, MpvLogMaske.maskiere(einmal))
        assertEquals("ffmpeg: https: HTTP error 403 Forbidden", MpvLogMaske.maskiere("ffmpeg: https: HTTP error 403 Forbidden"))
        assertTrue(MpvLogMaske.maskiere("vd: Using hardware decoding (mediacodec).").contains("mediacodec"))
    }
}
