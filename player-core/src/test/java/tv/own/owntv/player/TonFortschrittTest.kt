package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** German4K 3.2.1 (P4): Ton-Wächter erkennt Bitstream-Ausgabe am Ausgabe-Zähler. */
class TonFortschrittTest {
    @Test fun `zwei anstiege in folge laufen`() {
        var a = TonFortschritt.anstiege(-1, 5, 0) // erster Messwert im Abspielen
        assertEquals(0, a)
        a = TonFortschritt.anstiege(5, 10, a)
        assertFalse(TonFortschritt.laeuft(a))
        a = TonFortschritt.anstiege(10, 42, a)
        assertTrue(TonFortschritt.laeuft(a))
    }

    @Test fun `stehender zaehler setzt zurueck`() {
        var a = TonFortschritt.anstiege(5, 10, 0)
        a = TonFortschritt.anstiege(10, 10, a)
        assertEquals(0, a)
        a = TonFortschritt.anstiege(10, 11, a)
        assertFalse(TonFortschritt.laeuft(a))
    }

    @Test fun `erster messwert zaehlt noch nicht`() {
        assertEquals(0, TonFortschritt.anstiege(-1, 5, 0))
    }

    @Test fun `transcode nur unter ton_ac3`() {
        assertTrue(TonFortschritt.aktiv(transcode = true, passthroughSenke = true, tonAc3 = true, tonAuto = false))
        assertFalse(TonFortschritt.aktiv(transcode = true, passthroughSenke = false, tonAc3 = false, tonAuto = true))
    }

    @Test fun `passthrough unter ton_auto`() {
        assertTrue(TonFortschritt.aktiv(transcode = false, passthroughSenke = true, tonAc3 = false, tonAuto = true))
        assertFalse(TonFortschritt.aktiv(transcode = false, passthroughSenke = true, tonAc3 = false, tonAuto = false))
    }

    @Test fun `pcm bleibt beim alten waechter`() {
        assertFalse(TonFortschritt.aktiv(transcode = false, passthroughSenke = false, tonAc3 = true, tonAuto = true))
    }
}
