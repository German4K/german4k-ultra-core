package tv.own.owntv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** German4K 3.2.1 (P10, P11): Knopfzustand und Live-Tempo. */
class LiveTempoPlayZustandTest {
    @Test fun knopf_zeigt_pause_beim_puffern_mit_spielwunsch() {
        assertTrue(PlayZustand.gewollt(spielt = false, puffert = true, willSpielen = true, an = true))
        assertTrue(PlayZustand.gewollt(spielt = true, puffert = false, willSpielen = true, an = true))
        assertFalse(PlayZustand.gewollt(spielt = false, puffert = true, willSpielen = false, an = true))
        assertFalse(PlayZustand.gewollt(spielt = false, puffert = false, willSpielen = true, an = true))
    }

    @Test fun knopf_ohne_schalter_wie_bisher() {
        assertFalse(PlayZustand.gewollt(spielt = false, puffert = true, willSpielen = true, an = false))
        assertTrue(PlayZustand.gewollt(spielt = true, puffert = false, willSpielen = false, an = false))
    }

    @Test fun live_tempo_wird_auf_eins_gesetzt() {
        assertTrue(LiveTempo.zuruecksetzen(live = true, tempo = 1.25, an = true))
        assertFalse(LiveTempo.zuruecksetzen(live = true, tempo = 1.0, an = true))
        assertFalse(LiveTempo.zuruecksetzen(live = false, tempo = 1.25, an = true))
        assertFalse(LiveTempo.zuruecksetzen(live = true, tempo = 1.25, an = false))
    }

    @Test fun live_tempo_wunsch_gesperrt() {
        assertTrue(LiveTempo.sperren(live = true, tempo = 1.5, an = true))
        assertFalse(LiveTempo.sperren(live = true, tempo = 1.0, an = true))
        assertFalse(LiveTempo.sperren(live = false, tempo = 1.5, an = true))
        assertFalse(LiveTempo.sperren(live = true, tempo = 1.5, an = false))
    }
}
