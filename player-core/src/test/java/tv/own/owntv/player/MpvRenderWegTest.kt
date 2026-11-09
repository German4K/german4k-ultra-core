package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.player.MpvRenderWeg.Weg

/** German4K 3.2.1 (p-9): Ausgabeweg von mpv, besonders während der Direktweg-Sperre. */
class MpvRenderWegTest {
    private val direkt = Weg("mediacodec", "mediacodec_embed")
    private val kopie = Weg("mediacodec-copy", "gpu")
    private val software = Weg("no", "gpu")

    private fun weg(
        hwEin: Boolean = true, sw: Boolean = false, cc: Boolean = false,
        gesperrt: Boolean = false, gl: Boolean = true, kopieRettung: Boolean = false,
    ): Weg = MpvRenderWeg.weg(MpvRenderWeg.hwAktiv(hwEin, sw, cc, gesperrt, gl), kopieRettung, gesperrt)

    @Test fun `ohne sperre unveraendert`() {
        assertEquals(direkt, weg())
        assertEquals(direkt, weg(gl = false))
        assertEquals(kopie, weg(kopieRettung = true))
        assertEquals(software, weg(sw = true))
        assertEquals(software, weg(cc = true))
        assertEquals(software, weg(hwEin = false))
        assertTrue(weg().direkt)
    }

    @Test fun `sperre mit gl = hardware ueber die kopie, nie hwdec=no`() {
        assertEquals(kopie, weg(gesperrt = true, gl = true))
        assertEquals(kopie, weg(gesperrt = true, gl = true, kopieRettung = true))
        assertFalse(weg(gesperrt = true, gl = true).direkt)
        assertTrue(MpvRenderWeg.hwAktiv(true, false, false, gesperrt = true, glOk = true))
    }

    @Test fun `sperre ohne gl = software`() {
        assertEquals(software, weg(gesperrt = true, gl = false))
        assertFalse(MpvRenderWeg.hwAktiv(true, false, false, gesperrt = true, glOk = false))
    }

    @Test fun `einzel-abschaltungen gelten auch unter sperre`() {
        assertEquals(software, weg(gesperrt = true, sw = true))
        assertEquals(software, weg(gesperrt = true, cc = true))
        assertEquals(software, weg(gesperrt = true, hwEin = false))
    }

    @Test fun `exo vorziehen nur bei sperre ohne gl, film ueber 1080p, hardware ein`() {
        for (gesperrt in listOf(false, true)) for (gl in listOf(false, true)) for (hw in listOf(false, true))
            for (live in listOf(false, true)) for (h in listOf(0, 1080, 1081, 2160)) {
                val soll = gesperrt && !gl && hw && !live && h > 1080
                assertEquals("g=$gesperrt gl=$gl hw=$hw live=$live h=$h", soll, MpvRenderWeg.exoVorziehen(gesperrt, gl, hw, live, h))
            }
    }
}
