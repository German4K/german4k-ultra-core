package tv.own.owntv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** German4K 3.2.1 (P12): Exo-Uhr steht nach dem ersten Bild → mpv. */
class ExoUhrTest {
    @Test fun stand_nur_nach_erstem_bild_ohne_fortschritt_und_nach_frist() {
        assertTrue(ExoUhr.stand(erstesBild = true, fortschritte = 0, seitStartMs = 10_000, fristMs = 8_000))
        assertFalse(ExoUhr.stand(erstesBild = true, fortschritte = 1, seitStartMs = 10_000, fristMs = 8_000))
        assertFalse(ExoUhr.stand(erstesBild = false, fortschritte = 0, seitStartMs = 10_000, fristMs = 8_000))
        assertFalse(ExoUhr.stand(erstesBild = true, fortschritte = 0, seitStartMs = 3_000, fristMs = 8_000))
    }

    @Test fun sitzung_erst_ab_zwei_treffern_am_selben_ausgang() {
        assertFalse(ExoUhr.sitzungMpv(1, "lautsprecher 2ch pcm", "lautsprecher 2ch pcm"))
        assertTrue(ExoUhr.sitzungMpv(2, "lautsprecher 2ch pcm", "lautsprecher 2ch pcm"))
        assertFalse(ExoUhr.sitzungMpv(2, "lautsprecher 2ch pcm", "hdmi 8ch ac3,eac3"))
        assertTrue(ExoUhr.sitzungMpv(3, null, null))
    }
}

/** German4K 3.2.1 (P13): Live-Rückpuffer. */
class LiveSpeicherTest {
    @Test fun live_klein_film_budget() {
        org.junit.Assert.assertEquals("8MiB", LiveSpeicher.rueckpuffer(live = true, budget = "64MiB", getrimmt = false, an = true))
        org.junit.Assert.assertEquals("64MiB", LiveSpeicher.rueckpuffer(live = false, budget = "64MiB", getrimmt = false, an = true))
        org.junit.Assert.assertEquals("8MiB", LiveSpeicher.rueckpuffer(live = false, budget = "64MiB", getrimmt = true, an = true))
        org.junit.Assert.assertEquals(null, LiveSpeicher.rueckpuffer(live = true, budget = "64MiB", getrimmt = false, an = false))
    }
}
