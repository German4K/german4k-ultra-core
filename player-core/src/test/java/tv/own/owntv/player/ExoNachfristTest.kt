package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.player.ExoNachfrist.Lebenszeichen

/** German4K 3.2.1 (t-9): Entscheidung über die einmalige Öffnungs-Nachfrist (exo_nachgeben). */
class ExoNachfristTest {
    private val belegt: (Int) -> Boolean = { it == 458 || it == 460 }

    private fun geben(
        an: Boolean = true,
        schon: Boolean = false,
        antwort: Boolean = false,
        bytes: Boolean = false,
        status: Int? = null,
    ) = ExoNachfrist.geben(an, schon, Lebenszeichen(antwort, bytes, status), belegt)

    @Test fun `Kopfzeilen da, noch keine Bytes - Nachfrist`() = assertTrue(geben(antwort = true, status = 200))

    @Test fun `Bytes fliessen - Nachfrist`() = assertTrue(geben(antwort = true, bytes = true, status = 200))

    @Test fun `nur Bytes ohne gemerkte Antwort - Nachfrist`() = assertTrue(geben(bytes = true))

    @Test fun `ohne jede Antwort - altes Verhalten`() {
        assertFalse(geben())
        assertFalse(ExoNachfrist.geben(true, false, Lebenszeichen.KEINE, belegt))
    }

    @Test fun `Schalter aus - altes Verhalten`() = assertFalse(geben(an = false, antwort = true, bytes = true, status = 200))

    @Test fun `nur einmal je Abstimmung`() = assertFalse(geben(schon = true, antwort = true, bytes = true, status = 200))

    @Test fun `458 und 460 nie verlaengern`() {
        assertFalse(geben(antwort = false, bytes = true, status = 458))
        assertFalse(geben(antwort = false, bytes = true, status = 460))
    }

    @Test fun `andere Fehlerantwort - keine Nachfrist`() {
        assertFalse(geben(bytes = true, status = 404))
        assertFalse(geben(bytes = true, status = 503))
    }

    @Test fun `Nachfrist ist 6 s und bleibt im Live-Budget`() {
        assertEquals(6_000L, ExoNachfrist.NACHFRIST_MS)
        assertTrue(LiveExoWatchdog.OPEN_TIMEOUT_MS + ExoNachfrist.NACHFRIST_MS < LiveLadder.DEFAULT_BUDGET_SECS * 1000L)
    }
}
