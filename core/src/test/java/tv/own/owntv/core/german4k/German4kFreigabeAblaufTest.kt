package tv.own.owntv.core.german4k

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class German4kFreigabeAblaufTest {
    private var gewartet = 0L
    private val warte: suspend (Long) -> Unit = { gewartet += it }

    @Test fun ohneKennungVierSekunden() = runBlocking {
        assertTrue(German4kFreigabeAblauf.abwarten(null, { error("keine Abfrage ohne Kennung") }, warte))
        assertEquals(4_000L, gewartet)
    }

    @Test fun fertigNachZweiAbfragen() = runBlocking {
        val antworten = ArrayDeque(listOf("wartet", "fertig"))
        assertTrue(German4kFreigabeAblauf.abwarten("v1", { antworten.removeFirst() }, warte))
        assertEquals(3_000L, gewartet)
    }

    @Test fun fehlerStartetNichtNeu() = runBlocking {
        assertFalse(German4kFreigabeAblauf.abwarten("v1", { "fehler" }, warte))
    }

    @Test fun alterServerOhneStandWartetAufVierSekunden() = runBlocking {
        assertTrue(German4kFreigabeAblauf.abwarten("v1", { null }, warte))
        assertEquals(4_000L, gewartet)
    }

    @Test fun haengtDerVorgangGehtEsNachDerFristTrotzdemWeiter() = runBlocking {
        assertTrue(German4kFreigabeAblauf.abwarten("v1", { "wartet" }, warte))
        assertTrue(gewartet in 20_000L..21_500L)
    }
}
