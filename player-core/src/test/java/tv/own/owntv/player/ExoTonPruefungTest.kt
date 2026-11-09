package tv.own.owntv.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** German4K 3.1: ExoPlayer zuerst darf DTS/TrueHD-Filme nicht stumm abspielen — dann übernimmt mpv. */
class ExoTonPruefungTest {
    @Test fun `only unsupported audio tracks - hand over`() = assertTrue(ExoTonPruefung.unspielbar(listOf(false, false)))
    @Test fun `one supported track - fine`() = assertFalse(ExoTonPruefung.unspielbar(listOf(false, true)))
    @Test fun `no audio at all - not our case`() = assertFalse(ExoTonPruefung.unspielbar(emptyList()))
}
