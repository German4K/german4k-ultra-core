package tv.own.owntv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * German4K 3.1: „Nur Ton" erst, wenn der Ton wirklich über den Startpunkt hinaus läuft. Beim Einstieg
 * mittendrin (virtueller Sender, Fortsetzen, Engine-Wechsel) steht die Position sofort auf dem Startpunkt,
 * bevor mpv den Videodecoder geöffnet hat — das ist kein Beweis für eine Datei ohne Bild.
 */
class AudioOnlyDetectTest {
    private fun d(pos: Long, start: Long = 0, codec: String? = null, elapsed: Long = 6_000, loaded: Boolean = true, audioMode: Boolean = false) =
        AudioOnlyDetect.isAudioOnly(loaded, elapsed, pos, start, audioMode, codec)

    @Test fun `resume point alone is no proof`() = assertFalse(d(pos = 169_000, start = 169_000))
    @Test fun `audio progressing past the resume point without video codec`() = assertTrue(d(pos = 171_500, start = 169_000))
    @Test fun `from zero, progress after 5 s without codec`() = assertTrue(d(pos = 3_000))
    @Test fun `video codec known - not audio only`() = assertFalse(d(pos = 171_500, start = 169_000, codec = "h264"))
    @Test fun `too early`() = assertFalse(d(pos = 3_000, elapsed = 4_000))
    @Test fun `not loaded yet`() = assertFalse(d(pos = 3_000, loaded = false))
    @Test fun `audio mode is the user's own doing`() = assertTrue(d(pos = 3_000, codec = "h264", audioMode = true))
}
