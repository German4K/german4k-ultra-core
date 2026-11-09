package tv.own.owntv.core.german4k

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** German4K 3.2.1 (U4): Kopplung über die Website wird ohne „Erneut prüfen" erkannt — mit Obergrenze. */
class German4kKopplungsWacheTest {

    @Test
    fun kopplungNachZwanzigSekundenWirdErkannt() = runBlocking {
        var uhr = 0L
        var fragen = 0
        val ok = German4kKopplungsWache.warte(
            pruefe = { fragen++; uhr >= 20_000L },
            schlafe = { uhr += it },
        )
        assertTrue(ok)
        assertEquals(20_000L, uhr)
        assertEquals(4, fragen)
    }

    @Test
    fun hoertNachZehnMinutenAuf() = runBlocking {
        var uhr = 0L
        var fragen = 0
        val ok = German4kKopplungsWache.warte(pruefe = { fragen++; false }, schlafe = { uhr += it })
        assertFalse(ok)
        assertEquals(German4kKopplungsWache.MAX_MS, uhr)
        // 3 min alle 5 s (36) + 7 min alle 15 s (28) = 64 Anfragen statt 120.
        assertEquals(64, fragen)
    }
}
