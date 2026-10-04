package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Test

/** Review Focus 1: die Position kommt aus der Serveruhr, nie aus der Gerätezeit allein. */
class VirtuellUhrTest {
    private val server = VirtuellTestDaten.SERVER.toEpochMilli()

    @Test
    fun `device five minutes behind still lands on the plan position`() {
        val geraetBeiAntwort = server - 5 * 60_000
        val uhr = VirtuellUhr.aus(VirtuellTestDaten.SERVER, geraetBeiAntwort)
        assertEquals(5 * 60_000L, uhr.versatzMs)
        val stand = VirtuellPlan.stand(VirtuellTestDaten.sender(), uhr, geraetBeiAntwort + 2_000)!!
        assertEquals(710_000L + 2_000L, stand.positionMs)
    }

    @Test
    fun `cached answer 45 s old yields the position 45 s later`() {
        val uhr = VirtuellUhr.aus(VirtuellTestDaten.SERVER, server)
        val stand = VirtuellPlan.stand(VirtuellTestDaten.sender(), uhr, server + 45_000)!!
        assertEquals(755_000L, stand.positionMs)
        assertEquals(880001L, stand.eintrag.zielId)
        assertEquals(880002L, stand.naechster!!.zielId)
    }

    @Test
    fun `missing server time means no offset`() {
        assertEquals(0L, VirtuellUhr.aus(null, 123L).versatzMs)
    }
}
