package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class VirtuellGuideTest {
    private val plan = VirtuellTestDaten.sender().plan
    private fun ms(s: String) = Instant.parse(s).toEpochMilli()

    @Test
    fun `plan becomes guide rows in device time clipped to the window`() {
        val zeilen = VirtuellPlan.alsProgramme(
            plan, epgId = "g4k.v.himym", sourceId = 7, uhr = VirtuellUhr(60_000),
            vonMs = ms("2026-10-04T18:00:00Z"), bisMs = ms("2026-10-04T18:30:00Z"),
        )
        // Gerät 1 min hinter dem Server: alle Zeiten 1 min früher. Der Film (18:32Z Server = 18:31Z Gerät) liegt
        // außerhalb des Fensters bis 18:30Z.
        assertEquals(2, zeilen.size)
        assertEquals(ms("2026-10-04T17:47:10Z"), zeilen[0].startMs)
        assertEquals("g4k.v.himym", zeilen[0].epgChannelId)
        assertEquals(7L, zeilen[0].sourceId)
        assertEquals("Der Rauswurf", zeilen[0].description)
        assertTrue(zeilen.all { it.id < 0 })
        assertEquals(zeilen.map { it.id }.distinct().size, zeilen.size)
    }

    @Test
    fun `empty plan gives no rows`() =
        assertTrue(VirtuellPlan.alsProgramme(emptyList(), "g4k.v.x", 1, VirtuellUhr(0), 0, Long.MAX_VALUE).isEmpty())
}
