package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** German4K 3.2 (N4/C2): Konferenz-Einträge im Sport-Hub — Parser rückwärtsverträglich, eigene Karte. */
class German4kKonferenzTest {
    private val antwort = """
        {"ok":true,"stand":"2026-10-17T12:00:00Z","server_time":"2026-10-17T12:00:05Z","spiele":[
          {"id":-900001,"art":"konferenz","titel":"Bundesliga-Konferenz","heim":"Bundesliga-Konferenz","gast":"5 Spiele",
           "wettbewerb":"Bundesliga","start":"2026-10-17T13:30:00Z","startDE":"Sa., 17.10., 15:30","zustand":"geplant","relevanz":1,
           "sender":[{"name":"🇩🇪 DAZN 1","kategorie":"🇩🇪 DAZN","streamIds":[501,502],"an":true,"platz":false}],
           "senderUnbekannt":false,"bestaetigt":true,
           "paarungen":[{"heim":"Union Berlin","gast":"Dortmund","start":"2026-10-17T13:30:00Z"},{"heim":"Mainz","gast":"Köln"},{"heim":"","gast":""}]},
          {"id":42,"art":"spiel","titel":"Union – Dortmund","heim":"Union Berlin","gast":"Dortmund","wettbewerb":"Bundesliga",
           "start":"2026-10-17T13:30:00Z","startDE":"Sa., 17.10., 15:30","zustand":"geplant","relevanz":5,"sender":[]},
          {"id":-900002,"art":"konferenz","titel":"Champions-League-Konferenz","heim":"x","gast":"y","wettbewerb":"Champions League",
           "start":"2026-10-14T19:00:00Z","startDE":"","zustand":"beendet","relevanz":1,"sender":[]}
        ]}
    """.trimIndent()

    @Test
    fun `parses paarungen and bestaetigt, drops empty pairings`() {
        val a = German4kSportAntwort.parse(antwort)!!
        val k = a.spiele.first { it.id == -900001L }
        assertTrue(k.istKonferenz)
        assertEquals(true, k.bestaetigt)
        assertEquals(2, k.paarungen.size)
        assertEquals("Union Berlin", k.paarungen[0].heim)
        assertEquals(Instant.parse("2026-10-17T13:30:00Z"), k.paarungen[0].start)
        assertNull(k.paarungen[1].start)
        assertFalse(a.spiele.first { it.id == 42L }.istKonferenz)
        assertNull(a.spiele.first { it.id == 42L }.bestaetigt)
        assertTrue(a.spiele.first { it.id == 42L }.paarungen.isEmpty())
    }

    @Test
    fun `round-trips konferenz fields`() {
        val a = German4kSportAntwort.parse(antwort)!!
        assertEquals(a, German4kSportAntwort.parse(a.toJson()))
    }

    @Test
    fun `old server answer without the new fields still parses`() {
        val a = German4kSportAntwort.parse(
            """{"ok":true,"spiele":[{"id":1,"titel":"A – B","heim":"A","gast":"B","start":"2026-10-17T13:30:00Z","zustand":"geplant","sender":[]}]}""",
        )!!
        assertTrue(a.spiele.single().paarungen.isEmpty())
        assertNull(a.spiele.single().bestaetigt)
        assertFalse(a.spiele.single().istKonferenz)
    }

    @Test
    fun `konferenz gets its own shelf, never inside heute, finished ones vanish`() {
        val a = German4kSportAntwort.parse(antwort)!!
        val r = regale(a.spiele, LocalDate.of(2026, 10, 17))
        assertEquals(listOf(-900001L), r.konferenzen.map { it.id })
        assertEquals(listOf(42L), r.heute.map { it.id })
        assertTrue(r.beendet.none { it.istKonferenz })
        assertFalse(r.leer)
    }

    @Test
    fun `running konferenz comes first`() {
        val a = German4kSportAntwort.parse(antwort)!!
        val k = a.spiele.first { it.id == -900001L }
        val spaeter = k.copy(id = -900003L, start = k.start.plusSeconds(3600))
        val live = k.copy(id = -900004L, start = k.start.plusSeconds(7200), zustand = German4kSpiel.ZUSTAND_LAEUFT)
        val r = regale(listOf(spaeter, k, live), LocalDate.of(2026, 10, 17))
        assertEquals(listOf(-900004L, -900001L, -900003L), r.konferenzen.map { it.id })
    }
}
