package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity

/** Startseiten-Reihe „Jetzt im TV" (Wunsch Rabe330): virtuelle zuerst, dann echte Sender mit laufender Sendung. */
class JetztImTvTest {
    private fun ch(id: Long, name: String = "K$id") = ChannelEntity(id = id, sourceId = 1, name = name, streamUrl = "u$id")
    private fun prog(ch: Long, titel: String, start: Long, stop: Long) =
        EpgProgrammeEntity(sourceId = 1, epgChannelId = "e$ch", startMs = start, stopMs = stop, title = titel)

    private val jetzt = VirtuellTestDaten.SERVER.toEpochMilli()

    @Test
    fun `virtual first, real after, duplicates once, real without programme left out`() {
        val stand = VirtuellPlan.stand(VirtuellTestDaten.sender(), VirtuellUhr(0), jetzt)!!
        val r = JetztImTv.zusammenstellen(
            virtuell = listOf(ch(9) to stand),
            echt = listOf(ch(1), ch(2), ch(9), ch(1)),
            echtJetzt = mapOf(1L to prog(1, "Nachrichten", jetzt - 60_000, jetzt + 60_000), 9L to prog(9, "alt", 0, jetzt + 1)),
            jetztMs = jetzt,
        )
        assertEquals(listOf(9L, 1L), r.map { it.channel.id })
        assertTrue(r[0].virtuell)
        assertEquals("HIMYM · S3E4 · Der Rauswurf", r[0].titel)
        assertEquals("Nachrichten", r[1].titel)
    }

    @Test
    fun `finished programmes do not count`() {
        val r = JetztImTv.zusammenstellen(emptyList(), listOf(ch(1)), mapOf(1L to prog(1, "vorbei", 0, jetzt)), jetzt)
        assertTrue(r.isEmpty())
    }

    @Test
    fun `at most the maximum`() {
        val echt = (1L..30L).map { ch(it) }
        val r = JetztImTv.zusammenstellen(emptyList(), echt, echt.associate { it.id to prog(it.id, "x", jetzt - 1, jetzt + 1) }, jetzt, max = 12)
        assertEquals(12, r.size)
    }
}
