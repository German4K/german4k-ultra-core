package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** German4K 3.2.1 (U51): gewählte Chips mit lesbarer Schrift. */
class German4kKontrastTest {
    @Test fun `Schwarz auf Weiss ist 21 zu 1`() {
        assertEquals(21.0, German4kKontrast.verhaeltnis(0xFF000000.toInt(), 0xFFFFFFFF.toInt()), 0.01)
    }

    @Test fun `dunkle Akzentschrift auf dunklem Grund faellt durch, helle Ersatzfarbe gewinnt`() {
        val grund = 0xFF2A2340.toInt() // dunkler Kartenton mit Violett-Schimmer
        val onPrimary = 0xFF21005D.toInt() // dunkles Violett (Schrift auf gefülltem Akzent)
        val hell = 0xFFE6E1F5.toInt()
        assertTrue(German4kKontrast.verhaeltnis(onPrimary, grund) < German4kKontrast.MINDEST)
        val gewaehlt = German4kKontrast.lesbar(grund, onPrimary, hell)
        assertEquals(hell, gewaehlt)
        assertTrue(German4kKontrast.verhaeltnis(gewaehlt, grund) >= German4kKontrast.MINDEST)
    }

    @Test fun `erste ausreichende Farbe bleibt`() {
        val grund = 0xFF101010.toInt()
        val akzent = 0xFFB69DF8.toInt()
        assertEquals(akzent, German4kKontrast.lesbar(grund, akzent, 0xFFFFFFFF.toInt()))
    }
}
