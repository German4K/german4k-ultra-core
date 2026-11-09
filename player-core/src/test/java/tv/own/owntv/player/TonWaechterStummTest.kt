package tv.own.owntv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** German4K 3.2.1 (p-20): bewusst stumme Vorschau löst keinen Stereo-Rückfall aus. */
class TonWaechterStummTest {
    @Test fun `stumme Vorschau zaehlt nicht`() = assertFalse(TonWaechterStumm.zaehlt(stumm = true, spurAbgewaehlt = true, schalter = true))
    @Test fun `nur leise gestellt zaehlt auch nicht`() = assertFalse(TonWaechterStumm.zaehlt(stumm = true, spurAbgewaehlt = false, schalter = true))
    @Test fun `hoerbare Wiedergabe zaehlt`() = assertTrue(TonWaechterStumm.zaehlt(stumm = false, spurAbgewaehlt = false, schalter = true))
    @Test fun `Schalter aus wie bisher`() = assertTrue(TonWaechterStumm.zaehlt(stumm = true, spurAbgewaehlt = true, schalter = false))
}
