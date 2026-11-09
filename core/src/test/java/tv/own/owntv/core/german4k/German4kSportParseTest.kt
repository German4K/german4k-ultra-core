package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/** German4K: Vertrag von /api/app/sport, inklusive Cache-Rundreise. */
class German4kSportParseTest {
    private val vertrag = """
        {"ok":true,"stand":"2026-10-01T12:00:00Z","server_time":"2026-10-01T12:00:05Z",
         "spiele":[
          {"id":42,"titel":"A – B","heim":"A","gast":"B","wettbewerb":"Bundesliga","start":"2026-10-01T18:30:00Z",
           "startDE":"Do., 01.10., 20:30","zustand":"geplant","relevanz":7,"art":"spiel",
           "sender":[
             {"name":"Platz 1","kategorie":"Event","streamIds":[11,12],"an":true,"gruppe":"de","gruppeName":"Deutschland","platz":true,"platzZustand":"geplant"},
             {"name":"Kanal X","kategorie":null,"streamIds":[],"an":null,"gruppe":null,"gruppeName":null,"platz":false,"platzZustand":null}
           ],
           "senderUnbekannt":false,"bereich":{"name":"Sport DE","hinweis":null},
           "rechte":{"sender":"Marke","hinweis":"nur Pay","kategorie":"Marke Sport"}},
          {"id":43,"titel":"C – D","heim":"C","gast":"D","wettbewerb":null,"start":"2026-10-02T19:00:00.000Z",
           "startDE":"Fr., 02.10., 21:00","zustand":"laeuft","relevanz":3.5,"art":"spiel","sender":[],
           "senderUnbekannt":true,"bereich":null,"rechte":null}
         ],
         "events":[{"egal":1}]}
    """.trimIndent()

    @Test
    fun `parses the contract`() {
        val a = German4kSportAntwort.parse(vertrag)!!
        assertTrue(a.ok)
        assertNull(a.grund)
        assertEquals("2026-10-01T12:00:00Z", a.stand)
        assertEquals(2, a.spiele.size)
        val s = a.spiele[0]
        assertEquals(42L, s.id)
        assertEquals(Instant.parse("2026-10-01T18:30:00Z"), s.start)
        assertEquals(7.0, s.relevanz, 0.0)
        assertEquals(listOf(11L, 12L), s.sender[0].streamIds)
        assertEquals(true, s.sender[0].an)
        assertTrue(s.sender[0].platz)
        assertNull(s.sender[1].an)
        assertNull(s.sender[1].kategorie)
        assertEquals("Sport DE", s.bereich!!.name)
        assertEquals("Marke Sport", s.rechte!!.kategorie)
        val t = a.spiele[1]
        assertNull(t.wettbewerb)
        assertTrue(t.laeuft)
        assertTrue(t.senderUnbekannt)
        assertNull(t.bereich)
        assertNull(t.rechte)
    }

    @Test
    fun `round-trips through toJson`() {
        val a = German4kSportAntwort.parse(vertrag)!!
        assertEquals(a, German4kSportAntwort.parse(a.toJson()))
    }

    @Test
    fun `ok false with grund`() {
        val a = German4kSportAntwort.parse("""{"ok":false,"grund":"nicht gekoppelt","stand":null,"server_time":"x","spiele":[],"events":[]}""")!!
        assertFalse(a.ok)
        assertEquals("nicht gekoppelt", a.grund)
        assertNull(a.stand)
        assertTrue(a.spiele.isEmpty())
        assertEquals(a, German4kSportAntwort.parse(a.toJson()))
    }

    @Test
    fun `broken json is null`() {
        assertNull(German4kSportAntwort.parse("<html>"))
    }
}
