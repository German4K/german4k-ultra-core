package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ChannelEntity
import tv.own.owntv.core.database.entity.EpgProgrammeEntity

/** Startseiten-Reihe „Jetzt im TV" (Kundenwunsch): virtuelle zuerst, dann echte Sender mit laufender Sendung. */
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

    // German4K 3.2.1 (p-15): „Jetzt im TV“ ohne die Weiterschauen-Sender — nie leer.
    private fun alle(vararg ids: Long) = ids.associate { it to prog(it, "S$it", jetzt - 1, jetzt + 1) }

    @Test
    fun `ohne Weiterschauen - Doppelte raus, aus Zusatz aufgefuellt`() {
        val r = JetztImTv.ohneWeiterschauen(
            virtuell = emptyList(), echt = listOf(ch(1), ch(2), ch(3), ch(4)), zusatz = listOf(ch(1), ch(5)),
            echtJetzt = alle(1, 2, 3, 4, 5), jetztMs = jetzt, weiterIds = setOf(1L, 2L),
        )
        assertEquals(listOf(3L, 4L, 5L), r.map { it.channel.id })
    }

    @Test
    fun `ohne Weiterschauen - zu wenig uebrig, alte Liste bleibt`() {
        // Abnahme k8: keine Favoriten, nur die zwei gerade gesehenen Sender.
        val r = JetztImTv.ohneWeiterschauen(
            virtuell = emptyList(), echt = listOf(ch(1), ch(2)), zusatz = listOf(ch(1), ch(2), ch(3)),
            echtJetzt = alle(1, 2, 3), jetztMs = jetzt, weiterIds = setOf(1L, 2L),
        )
        assertEquals(listOf(1L, 2L), r.map { it.channel.id })
    }

    @Test
    fun `ohne Weiterschauen - virtuelle Sender aus Weiterschauen fallen auch raus`() {
        val stand = VirtuellPlan.stand(VirtuellTestDaten.sender(), VirtuellUhr(0), jetzt)!!
        val r = JetztImTv.ohneWeiterschauen(
            virtuell = listOf(ch(9) to stand), echt = listOf(ch(1), ch(2), ch(3)), zusatz = emptyList(),
            echtJetzt = alle(1, 2, 3), jetztMs = jetzt, weiterIds = setOf(9L, 1L),
        )
        assertEquals(listOf(2L, 3L), r.map { it.channel.id })
    }

    @Test
    fun `ohne Weiterschauen - nichts unter Weiterschauen, unveraendert`() {
        val echt = listOf(ch(1), ch(2))
        assertEquals(
            JetztImTv.zusammenstellen(emptyList(), echt, alle(1, 2), jetzt),
            JetztImTv.ohneWeiterschauen(emptyList(), echt, listOf(ch(3)), alle(1, 2, 3), jetzt, emptySet()),
        )
    }

    // German4K 3.2.1 (t-8)
    @Test
    fun `einzeln ausgeblendete Nachbarn fallen weg`() {
        val a = ChannelEntity(id = 1, sourceId = 1, name = "A", streamUrl = "u1", remoteId = "11")
        val b = ChannelEntity(id = 2, sourceId = 1, name = "B", streamUrl = "u2", remoteId = "12")
        val key = tv.own.owntv.core.customize.CustomizeKeys.channel(b)
        assertEquals(listOf(a), JetztImTv.ohneAusgeblendete(listOf(a, b), setOf(key)))
        assertEquals(listOf(a, b), JetztImTv.ohneAusgeblendete(listOf(a, b), emptySet()))
    }

    // German4K 3.2.1 (t-8)
    @Test
    fun `nur der neueste Lauf schreibt`() {
        val l = JetztImTv.Laeufe()
        val alt = l.start()
        val neu = l.start()
        org.junit.Assert.assertFalse(l.aktuell(alt))
        assertTrue(l.aktuell(neu))
    }
}
