package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Test

class German4kProzessEndeTest {
    private val tag = 24L * 60 * 60 * 1000
    private val jetzt = 100 * tag
    private fun e(t: Long) = German4kProzessEnde.Ende(t, "anr", "x")

    @Test fun nurNachDemMerker() {
        val r = German4kProzessEnde.neue(listOf(e(jetzt - 5), e(jetzt - 50), e(jetzt - 500)), seitMs = jetzt - 60, jetztMs = jetzt)
        assertEquals(listOf(jetzt - 50, jetzt - 5), r.map { it.zeitMs })
    }

    @Test fun ersterLaufNurDreiTage() {
        val r = German4kProzessEnde.neue(listOf(e(jetzt - tag), e(jetzt - 10 * tag)), seitMs = null, jetztMs = jetzt)
        assertEquals(listOf(jetzt - tag), r.map { it.zeitMs })
    }

    @Test fun hoechstensDreiDieNeuesten() {
        val r = German4kProzessEnde.neue((1L..6L).map { e(jetzt - it) }, seitMs = 0, jetztMs = jetzt)
        assertEquals(listOf(jetzt - 3, jetzt - 2, jetzt - 1), r.map { it.zeitMs })
    }
}
