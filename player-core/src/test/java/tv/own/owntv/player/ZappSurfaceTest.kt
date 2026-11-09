package tv.own.owntv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ZappSurfaceTest {
    private val hd = 1920 to 1080
    private val hd720 = 1280 to 720

    @Test fun auflösungswechselBautNeu() = assertTrue(ZappSurface.neuBauen(hd, hd720, istKachel = false, schalterAn = true))
    @Test fun gleicheGroesseNicht() = assertFalse(ZappSurface.neuBauen(hd, hd, istKachel = false, schalterAn = true))
    @Test fun ersterTuneNicht() = assertFalse(ZappSurface.neuBauen(null, hd, istKachel = false, schalterAn = true))
    @Test fun kachelNie() = assertFalse(ZappSurface.neuBauen(hd, hd720, istKachel = true, schalterAn = true))
    @Test fun schalterAus() = assertFalse(ZappSurface.neuBauen(hd, hd720, istKachel = false, schalterAn = false))
}
