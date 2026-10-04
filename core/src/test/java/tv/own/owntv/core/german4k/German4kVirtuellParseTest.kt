package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** German4K: Vertrag von /api/app/virtuell (Vertrag 1). */
class German4kVirtuellParseTest {
    @Test
    fun `parses the contract example`() {
        val a = German4kVirtuellAntwort.parse(VirtuellTestDaten.VERTRAG)!!
        assertTrue(a.ok)
        assertEquals(1, a.vertrag)
        assertEquals(VirtuellTestDaten.SERVER, a.serverTime)
        assertEquals("team", a.stufe)
        val s = a.sender.single()
        assertEquals(13750001L, s.streamId)
        assertEquals("g4k.v.himym", s.epgId)
        assertEquals("HIMYM", s.kurzname)
        assertEquals(28001, s.nummer)
        assertNull(s.logo)
        assertEquals(710, s.positionSek)
        assertEquals(3, s.jetzt!!.staffel)
        assertEquals(4, s.jetzt!!.folge)
        assertEquals(880001L, s.jetzt!!.zielId)
        assertEquals(3, s.plan.size)
        for (i in 0 until s.plan.size - 1) assertEquals(s.plan[i].ende, s.plan[i + 1].start)
    }

    @Test
    fun `movie entries carry no season data`() {
        val film = VirtuellTestDaten.sender().plan.last()
        assertEquals("movie", film.art)
        assertNull(film.serie); assertNull(film.staffel); assertNull(film.folge); assertNull(film.folgentitel)
        assertEquals(Instant.parse("2026-10-04T20:30:00.000Z"), film.ende)
    }

    @Test
    fun `error answer keeps the reason and no sender`() {
        val a = German4kVirtuellAntwort.parse(
            """{"ok":false,"grund":"Zu viele Anfragen.","vertrag":1,"server_time":"2026-10-04T18:00:00Z","stufe":null,"sender":[]}""",
        )!!
        assertFalse(a.ok)
        assertEquals("Zu viele Anfragen.", a.grund)
        assertNull(a.stufe)
        assertTrue(a.sender.isEmpty())
    }

    @Test
    fun `broken json is null`() {
        assertNull(German4kVirtuellAntwort.parse("<html>"))
    }

    @Test
    fun `entries without url or times are dropped`() {
        val a = German4kVirtuellAntwort.parse(
            """{"ok":true,"vertrag":1,"server_time":"2026-10-04T18:00:00Z","stufe":"alle","sender":[{"stream_id":1,"epg_id":"g4k.v.x",
               "jetzt":null,"plan":[{"start":"kaputt","ende":"2026-10-04T18:10:00Z","url":"x"},
               {"start":"2026-10-04T18:00:00Z","ende":"2026-10-04T18:10:00Z","url":""}]}]}""",
        )!!
        val s = a.sender.single()
        assertNull(s.jetzt)
        assertTrue(s.plan.isEmpty())
    }

    @Test
    fun `round trip through json`() {
        val a = German4kVirtuellAntwort.parse(VirtuellTestDaten.VERTRAG)!!
        assertEquals(a, German4kVirtuellAntwort.parse(a.toJson()))
    }
}
