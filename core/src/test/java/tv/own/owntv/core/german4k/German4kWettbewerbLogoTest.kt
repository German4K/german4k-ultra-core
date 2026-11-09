package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** German4K 3.2 (C6): Wettbewerbslogo je Spiel und je Konferenz — rückwärtsverträglich, Unsinn → null. */
class German4kWettbewerbLogoTest {
    private val bl = "https://cdn.german4k.tv/wettbewerbe/bundesliga.png"

    private val neu = """
        {"ok":true,"stand":null,"server_time":"x","spiele":[
          {"id":1,"art":"spiel","titel":"A – B","heim":"A","gast":"B","wettbewerb":"Bundesliga",
           "start":"2026-10-17T13:30:00Z","zustand":"geplant","sender":[],"wettbewerb_logo":"$bl"},
          {"id":2,"art":"spiel","titel":"C – D","heim":"C","gast":"D","start":"2026-10-17T13:30:00Z","zustand":"geplant",
           "sender":[],"wettbewerb_logo":null},
          {"id":3,"art":"spiel","titel":"E – F","heim":"E","gast":"F","start":"2026-10-17T13:30:00Z","zustand":"geplant",
           "sender":[],"wettbewerb_logo":"   "},
          {"id":4,"art":"spiel","titel":"G – H","heim":"G","gast":"H","start":"2026-10-17T13:30:00Z","zustand":"geplant",
           "sender":[],"wettbewerb_logo":"ftp://cdn.german4k.tv/x.png"},
          {"id":5,"art":"spiel","titel":"I – J","heim":"I","gast":"J","start":"2026-10-17T13:30:00Z","zustand":"geplant",
           "sender":[],"wettbewerb_logo":"kein-link"},
          {"id":6,"art":"spiel","titel":"K – L","heim":"K","gast":"L","start":"2026-10-17T13:30:00Z","zustand":"geplant",
           "sender":[],"wettbewerb_logo":42},
          {"id":-900001,"art":"konferenz","titel":"Bundesliga-Konferenz","heim":"Bundesliga-Konferenz","gast":"5 Spiele",
           "wettbewerb":"Bundesliga","start":"2026-10-17T13:30:00Z","zustand":"geplant","sender":[],
           "paarungen":[{"heim":"Mainz","gast":"Köln"}],"wettbewerb_logo":"$bl"}
        ]}
    """.trimIndent()

    /** Antwort eines Servers vor C6: kein `wettbewerb_logo`. */
    private val alt = """
        {"ok":true,"stand":null,"server_time":"x","spiele":[
          {"id":1,"art":"spiel","titel":"A – B","heim":"A","gast":"B","wettbewerb":"Bundesliga","start":"2026-10-17T13:30:00Z",
           "zustand":"geplant","sender":[]},
          {"id":-900001,"art":"konferenz","titel":"K","heim":"K","gast":"1 Spiel","start":"2026-10-17T13:30:00Z","zustand":"geplant",
           "sender":[],"paarungen":[{"heim":"Mainz","gast":"Köln"}]}
        ]}
    """.trimIndent()

    @Test
    fun `parses wettbewerb_logo on games and conference`() {
        val a = German4kSportAntwort.parse(neu)!!
        assertEquals(bl, a.spiele.first { it.id == 1L }.wettbewerbLogo)
        val k = a.spiele.first { it.istKonferenz }
        assertEquals(bl, k.wettbewerbLogo)
        assertEquals(1, k.paarungen.size)
    }

    @Test
    fun `missing, null, blank and invalid logos become null`() {
        val a = German4kSportAntwort.parse(neu)!!
        assertEquals(7, a.spiele.size)
        for (id in 2L..6L) assertNull("id $id", a.spiele.first { it.id == id }.wettbewerbLogo)
        val b = German4kSportAntwort.parse(alt)!!
        assertEquals(2, b.spiele.size)
        b.spiele.forEach { assertNull(it.wettbewerbLogo) }
        assertEquals("Bundesliga", b.spiele[0].wettbewerb)
    }

    @Test
    fun `wettbewerb logo survives the cache round trip`() {
        val a = German4kSportAntwort.parse(neu)!!
        assertEquals(a, German4kSportAntwort.parse(a.toJson()))
        val b = German4kSportAntwort.parse(alt)!!
        assertEquals(b, German4kSportAntwort.parse(b.toJson()))
    }
}
