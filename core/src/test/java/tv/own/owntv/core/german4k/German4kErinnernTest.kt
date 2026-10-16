package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.database.entity.ErinnerungEntity

/** German4K 3.2 (N7): wann „⏰ Erinnern“ klingelt, wann es „Verpasst“ heißt, wann es still verschwindet. */
class German4kErinnernTest {
    private val min = 60_000L
    private val start = 1_000_000_000L

    private fun sendung(ende: Long? = start + 30 * min) = ErinnerungEntity(
        profileId = 1, art = ErinnerungEntity.ART_SENDUNG, schluessel = German4kErinnern.sendungSchluessel(3, "4711", start),
        titel = "Tagesschau", zusatz = "Das Erste", startMs = start, endeMs = ende, channelId = 9, sourceId = 3, remoteId = "4711",
        vorlaufMin = German4kErinnern.VORLAUF_SENDUNG_MIN, createdAt = 0,
    )

    private fun spiel() = ErinnerungEntity(
        profileId = 1, art = ErinnerungEntity.ART_SPIEL, schluessel = German4kErinnern.spielSchluessel(-900001),
        titel = "Bundesliga-Konferenz", startMs = start, spielId = -900001, vorlaufMin = German4kErinnern.VORLAUF_SPIEL_MIN, createdAt = 0,
    )

    @Test
    fun `sendung rings five minutes before, game ten`() {
        assertEquals(start - 5 * min, German4kErinnern.ausloeseZeit(sendung()))
        assertEquals(start - 10 * min, German4kErinnern.ausloeseZeit(spiel()))
        assertEquals(German4kErinnern.Zustand.Wartet, German4kErinnern.zustand(sendung(), start - 6 * min))
        assertEquals(German4kErinnern.Zustand.Gleich, German4kErinnern.zustand(sendung(), start - 5 * min))
        assertEquals(German4kErinnern.Zustand.Wartet, German4kErinnern.zustand(spiel(), start - 11 * min))
        assertEquals(German4kErinnern.Zustand.Gleich, German4kErinnern.zustand(spiel(), start - 10 * min))
    }

    @Test
    fun `after the start it is missed, with minutes, until the end`() {
        assertEquals(German4kErinnern.Zustand.Gleich, German4kErinnern.zustand(sendung(), start + 2 * min))
        assertEquals(German4kErinnern.Zustand.Verpasst(12), German4kErinnern.zustand(sendung(), start + 12 * min + 30_000))
        assertEquals(German4kErinnern.Zustand.Vorbei, German4kErinnern.zustand(sendung(), start + 30 * min))
        // Ohne EPG-Ende: eine Stunde; Spiele zwei Stunden.
        assertEquals(German4kErinnern.Zustand.Verpasst(59), German4kErinnern.zustand(sendung(ende = null), start + 59 * min))
        assertEquals(German4kErinnern.Zustand.Vorbei, German4kErinnern.zustand(sendung(ende = null), start + 60 * min))
        assertEquals(German4kErinnern.Zustand.Verpasst(119), German4kErinnern.zustand(spiel(), start + 119 * min))
        assertEquals(German4kErinnern.Zustand.Vorbei, German4kErinnern.zustand(spiel(), start + 120 * min))
    }

    @Test
    fun `done is always over`() {
        assertEquals(German4kErinnern.Zustand.Vorbei, German4kErinnern.zustand(sendung().copy(erledigt = true), start))
    }

    @Test
    fun `next hint is the earliest due one, missed counts, waiting does not`() {
        val frueh = sendung()
        val spaeter = spiel().copy(id = 2, startMs = start + 3 * min)
        val wartet = spiel().copy(id = 3, startMs = start + 60 * min)
        assertEquals(frueh, German4kErinnern.naechsterHinweis(listOf(wartet, spaeter, frueh), start))
        assertEquals(frueh, German4kErinnern.naechsterHinweis(listOf(frueh), start + 10 * min)) // verpasst
        assertNull(German4kErinnern.naechsterHinweis(listOf(wartet), start))
        assertNull(German4kErinnern.naechsterHinweis(listOf(frueh.copy(erledigt = true)), start))
    }

    @Test
    fun `over ones are found for silent cleanup`() {
        val v = German4kErinnern.vorbei(listOf(sendung(), spiel()), start + 31 * min)
        assertEquals(listOf(sendung()), v)
    }

    @Test
    fun `only future programmes can be remembered`() {
        assertTrue(German4kErinnern.erinnerbar(start, start - 1))
        assertFalse(German4kErinnern.erinnerbar(start, start))
    }

    @Test
    fun `a moved game moves its reminder, a programme never`() {
        assertEquals(start + 30 * min, German4kErinnern.verlegt(spiel(), start + 30 * min))
        assertNull(German4kErinnern.verlegt(spiel(), start))
        assertNull(German4kErinnern.verlegt(spiel(), null))
        assertNull(German4kErinnern.verlegt(sendung(), start + 30 * min))
    }

    @Test
    fun `keys are stable and distinct`() {
        assertEquals("sendung:3:4711:$start", German4kErinnern.sendungSchluessel(3, "4711", start))
        assertEquals("spiel:-900001", German4kErinnern.spielSchluessel(-900001))
    }

    @Test
    fun `guide markers find the remembered starts of exactly this channel`() {
        val ch = tv.own.owntv.core.database.entity.ChannelEntity(id = 9, sourceId = 3, name = "ARD", streamUrl = "u", remoteId = "4711")
        val anderer = ch.copy(id = 10, remoteId = "47110")
        val keys = setOf(
            German4kErinnerungen.sendungSchluessel(ch, start),
            German4kErinnerungen.sendungSchluessel(ch, start + 60 * min),
            German4kErinnerungen.sendungSchluessel(anderer, start + 5 * min),
            German4kErinnern.spielSchluessel(-900001),
        )
        assertEquals(setOf(start, start + 60 * min), German4kErinnerungen.gemerkteBeginne(ch, keys))
        assertEquals(setOf(start + 5 * min), German4kErinnerungen.gemerkteBeginne(anderer, keys))
        // Ohne Anbieter-Kennung zählt die lokale id.
        val ohne = ch.copy(remoteId = null)
        assertEquals("sendung:3:id9:$start", German4kErinnerungen.sendungSchluessel(ohne, start))
    }

    @Test
    fun `at trigger time sport takes switched-on channels first, never switched-off ones`() {
        fun s(name: String, an: Boolean?) = German4kSportSender(name, null, listOf(1L), an, null, null, false, null)
        val spiel = German4kSpiel(
            id = -900001, titel = "Konferenz", heim = "", gast = "", wettbewerb = null, start = java.time.Instant.ofEpochMilli(start),
            startDE = "", zustand = "geplant", relevanz = 0.0, art = "konferenz",
            sender = listOf(s("aus", false), s("unbekannt", null), s("DAZN 1", true), s("Sky", true)),
            senderUnbekannt = false, bereich = null, rechte = null,
        )
        assertEquals(listOf("DAZN 1", "Sky", "unbekannt"), German4kErinnerungen.sportKandidaten(spiel).map { it.name })
    }
}
