package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class VirtuellPlanKetteTest {
    private val plan = VirtuellTestDaten.sender().plan
    private fun ms(s: String) = Instant.parse(s).toEpochMilli()

    @Test
    fun `title ended a moment early - next one starts`() {
        // Datei war 1 s kürzer als laut Plan: die Uhr steht noch im alten Eintrag.
        val n = VirtuellPlan.naechsterNach(plan, plan[0], ms("2026-10-04T18:10:11Z"))
        assertEquals(880002L, n!!.zielId)
    }

    @Test
    fun `clock already two entries further - take the running one`() {
        val n = VirtuellPlan.naechsterNach(plan, plan[0], ms("2026-10-04T18:40:00Z"))
        assertEquals(555L, n!!.zielId)
    }

    @Test
    fun `after the last entry there is nothing`() {
        assertNull(VirtuellPlan.naechsterNach(plan, plan[2], ms("2026-10-04T20:30:00Z")))
    }

    @Test
    fun `three failures switch to the normal channel and success resets`() {
        val z = VirtuellFehlerZaehler(grenze = 3)
        assertEquals(false, z.fehler())
        assertEquals(false, z.fehler())
        z.erfolg()
        assertEquals(false, z.fehler())
        assertEquals(false, z.fehler())
        assertEquals(true, z.fehler())
    }
}
