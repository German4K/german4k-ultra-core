package tv.own.owntv.core.player

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * German4K 3.1: Filme & Serien starten auf ExoPlayer (Steiner646 Bravia 10-Min-Start, Holger714 Box,
 * Philips-MKV). Wer nie etwas gewählt hat oder auf dem alten Standard „mpv zuerst" steht, kommt auf
 * „ExoPlayer, dann mpv"; eine bewusste „nur"-Wahl bleibt.
 */
class VodEngineMigrationTest {
    @Test fun `never chosen becomes exo first`() = assertEquals(EnginePreference.EXO_FIRST, EnginePreference.vodNach31(null))
    @Test fun `old default mpv first becomes exo first`() = assertEquals(EnginePreference.EXO_FIRST, EnginePreference.vodNach31("MPV_FIRST"))
    @Test fun `mpv only stays`() = assertEquals(EnginePreference.MPV_ONLY, EnginePreference.vodNach31("MPV_ONLY"))
    @Test fun `exo only stays`() = assertEquals(EnginePreference.EXO_ONLY, EnginePreference.vodNach31("EXO_ONLY"))
    @Test fun `exo first stays`() = assertEquals(EnginePreference.EXO_FIRST, EnginePreference.vodNach31("EXO_FIRST"))
    @Test fun `garbage becomes exo first`() = assertEquals(EnginePreference.EXO_FIRST, EnginePreference.vodNach31("kaputt"))
}
