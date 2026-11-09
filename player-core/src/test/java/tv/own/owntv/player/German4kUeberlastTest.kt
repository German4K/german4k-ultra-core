package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class German4kUeberlastTest {
    private val u = German4kUeberlast

    @Test fun beideEnginesMit503AusDerRohzeile() {
        assertTrue(u.istUeberlast(PlaybackFailure.BothEnginesExoFirst, "http: HTTP error 503 Service Unavailable", live = false))
    }

    @Test fun exoFehlerMitCodeImDoppelFehler() {
        val f = PlaybackFailure.BothEnginesMpvFirst(PlaybackFailure.Raw("Bad Gateway", httpCode = 502))
        assertTrue(u.istUeberlast(f, null, live = false))
    }

    @Test fun rohFehlerMit504() {
        assertTrue(u.istUeberlast(PlaybackFailure.Raw("Gateway Timeout", httpCode = 504), null, live = false))
    }

    @Test fun andereCodesUndLiveNicht() {
        assertFalse(u.istUeberlast(PlaybackFailure.BothEnginesExoFirst, "HTTP error 404 Not Found", live = false))
        assertFalse(u.istUeberlast(PlaybackFailure.BothEnginesExoFirst, "HTTP error 500", live = false))
        assertFalse(u.istUeberlast(PlaybackFailure.BothEnginesExoFirst, "HTTP error 503", live = true))
        assertFalse(u.istUeberlast(PlaybackFailure.BothEnginesExoFirst, null, live = false))
        assertFalse(u.istUeberlast(null, "HTTP error 503", live = false))
    }

    @Test fun hoechstensDreiVersuche() {
        assertEquals(30_000L, u.naechsterVersuchIn(0))
        assertEquals(30_000L, u.naechsterVersuchIn(2))
        assertNull(u.naechsterVersuchIn(3))
    }
}
