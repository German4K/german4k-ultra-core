package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** Review Focus 3: das Fenster läuft aus → neu holen; auch beim Tageswechsel. */
class VirtuellFensterTest {
    private val plan = VirtuellTestDaten.sender().plan // letzter Eintrag endet 20:30Z
    private fun ms(s: String) = Instant.parse(s).toEpochMilli()

    @Test fun `29 minutes left needs a reload`() =
        assertTrue(VirtuellPlan.brauchtNeu(plan, ms("2026-10-04T20:01:00Z"), ms("2026-10-04T18:00:00Z")))

    @Test fun `31 minutes left is fine`() =
        assertFalse(VirtuellPlan.brauchtNeu(plan, ms("2026-10-04T19:59:00Z"), ms("2026-10-04T18:00:00Z")))

    @Test fun `berlin midnight passed since fetch needs a reload`() {
        // 21:50Z = 23:50 Berlin geholt, 22:05Z = 00:05 Berlin jetzt; Fenster selbst noch lang genug.
        val langer = plan + plan.last().copy(start = plan.last().ende, ende = Instant.parse("2026-10-05T03:00:00Z"))
        assertTrue(VirtuellPlan.brauchtNeu(langer, ms("2026-10-04T22:05:00Z"), ms("2026-10-04T21:50:00Z")))
        assertFalse(VirtuellPlan.brauchtNeu(langer, ms("2026-10-04T21:55:00Z"), ms("2026-10-04T21:50:00Z")))
    }

    @Test fun `empty plan needs a reload`() = assertTrue(VirtuellPlan.brauchtNeu(emptyList(), 0L, 0L))

    @Test
    fun `subtitle shows season episode, minutes left and next title`() {
        val uhr = VirtuellUhr(0)
        val jetzt = ms("2026-10-04T17:58:12Z") // 12 min vor Ende 18:10:12
        val stand = VirtuellPlan.stand(VirtuellTestDaten.sender(), uhr, jetzt)!!
        val text = VirtuellPlan.untertitel(
            stand, jetzt,
            kopf = { e -> if (e.staffel != null && e.folge != null) "S${e.staffel} E${e.folge}" else e.titel },
            fmt = { kopf, rest, danach -> if (danach != null) "$kopf · noch $rest Min · danach: $danach" else "$kopf · noch $rest Min" },
        )
        assertEquals("S3 E4 · noch 12 Min · danach: HIMYM · S3E5 · Wie ich Marshall traf", text)
    }

    @Test
    fun `now next is in device time`() {
        val uhr = VirtuellUhr(60_000) // Server 1 min voraus
        val geraet = ms("2026-10-04T17:59:00Z")
        val stand = VirtuellPlan.stand(VirtuellTestDaten.sender(), uhr, geraet)!!
        val nn = VirtuellPlan.nowNext(stand)
        assertEquals("HIMYM · S3E4 · Der Rauswurf", nn.now!!.title)
        assertEquals(ms("2026-10-04T17:48:10Z") - 60_000, nn.now!!.startMs)
        assertEquals("HIMYM · S3E5 · Wie ich Marshall traf", nn.next!!.title)
        assertEquals(1, nn.upcoming.size)
        assertNull(nn.previous)
    }
}
