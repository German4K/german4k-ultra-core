package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class VirtuellPlanEintragTest {
    private val plan = VirtuellTestDaten.sender().plan
    private fun ms(s: String) = Instant.parse(s).toEpochMilli()

    @Test fun `start is inclusive`() = assertEquals(880001L, VirtuellPlan.eintragBei(plan, ms("2026-10-04T17:48:10Z"))!!.zielId)
    @Test fun `end is exclusive`() = assertEquals(880002L, VirtuellPlan.eintragBei(plan, ms("2026-10-04T18:10:12Z"))!!.zielId)
    @Test fun `before the first entry is null`() = assertNull(VirtuellPlan.eintragBei(plan, ms("2026-10-04T17:00:00Z")))
    @Test fun `after the last entry is null`() = assertNull(VirtuellPlan.eintragBei(plan, ms("2026-10-04T21:00:00Z")))
    @Test fun `empty plan is null`() = assertNull(VirtuellPlan.eintragBei(emptyList(), 0L))

    @Test
    fun `stand is null when the clock is outside the plan`() {
        val uhr = VirtuellUhr(0)
        assertNull(VirtuellPlan.stand(VirtuellTestDaten.sender(), uhr, ms("2026-10-04T22:00:00Z")))
    }
}
