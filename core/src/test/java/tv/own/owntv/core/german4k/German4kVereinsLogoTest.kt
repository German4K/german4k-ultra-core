package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** German4K 3.2 (C5): Vereinswappen im Sport-Hub — Parser rückwärtsverträglich, Initialen als Ersatz. */
class German4kVereinsLogoTest {
    private val sge = "https://cdn.german4k.tv/vereine/sge.png"
    private val koeln = "https://cdn.german4k.tv/vereine/koeln.png"

    private val neu = """
        {"ok":true,"stand":null,"server_time":"x","spiele":[
          {"id":1,"art":"spiel","titel":"Eintracht Frankfurt – 1. FC Köln","heim":"Eintracht Frankfurt","gast":"1. FC Köln",
           "wettbewerb":"Bundesliga","start":"2026-10-17T13:30:00Z","startDE":"","zustand":"geplant","relevanz":5,"sender":[],
           "heim_logo":"$sge","gast_logo":"$koeln"},
          {"id":2,"art":"spiel","titel":"A – B","heim":"A","gast":"B","start":"2026-10-17T13:30:00Z","zustand":"geplant","sender":[],
           "heim_logo":null,"gast_logo":""},
          {"id":3,"art":"spiel","titel":"C – D","heim":"C","gast":"D","start":"2026-10-17T13:30:00Z","zustand":"geplant","sender":[],
           "heim_logo":"kein-link","gast_logo":"ftp://cdn.german4k.tv/x.png"},
          {"id":-900001,"art":"konferenz","titel":"Bundesliga-Konferenz","heim":"Bundesliga-Konferenz","gast":"2 Spiele",
           "start":"2026-10-17T13:30:00Z","zustand":"geplant","sender":[],
           "paarungen":[{"heim":"Eintracht Frankfurt","gast":"1. FC Köln","heim_logo":"$sge","gast_logo":"$koeln"},
                        {"heim":"Mainz","gast":"Union Berlin","heim_logo":"  ","gast_logo":null}]}
        ]}
    """.trimIndent()

    /** Antwort eines Servers vor C5: keine Logo-Felder, weder am Spiel noch an den Paarungen. */
    private val alt = """
        {"ok":true,"stand":null,"server_time":"x","spiele":[
          {"id":1,"art":"spiel","titel":"A – B","heim":"A","gast":"B","start":"2026-10-17T13:30:00Z","zustand":"geplant","sender":[]},
          {"id":-900001,"art":"konferenz","titel":"K","heim":"K","gast":"1 Spiel","start":"2026-10-17T13:30:00Z","zustand":"geplant",
           "sender":[],"paarungen":[{"heim":"Mainz","gast":"Köln"}]}
        ]}
    """.trimIndent()

    @Test
    fun `parses heim_logo and gast_logo on games and pairings`() {
        val a = German4kSportAntwort.parse(neu)!!
        val s = a.spiele.first { it.id == 1L }
        assertEquals(sge, s.heimLogo)
        assertEquals(koeln, s.gastLogo)
        val k = a.spiele.first { it.istKonferenz }
        assertEquals(sge, k.paarungen[0].heimLogo)
        assertEquals(koeln, k.paarungen[0].gastLogo)
        assertNull(k.paarungen[1].heimLogo)
        assertNull(k.paarungen[1].gastLogo)
        // Konferenz selbst hat kein Wappen.
        assertNull(k.heimLogo)
    }

    @Test
    fun `null, empty and invalid urls become null`() {
        val a = German4kSportAntwort.parse(neu)!!
        val b = a.spiele.first { it.id == 2L }
        assertNull(b.heimLogo)
        assertNull(b.gastLogo)
        val c = a.spiele.first { it.id == 3L }
        assertNull(c.heimLogo)
        assertNull(c.gastLogo)
    }

    @Test
    fun `old server answer without logo fields still parses`() {
        val a = German4kSportAntwort.parse(alt)!!
        assertEquals(2, a.spiele.size)
        assertNull(a.spiele[0].heimLogo)
        assertNull(a.spiele[0].gastLogo)
        val p = a.spiele[1].paarungen.single()
        assertEquals("Mainz", p.heim)
        assertNull(p.heimLogo)
        assertNull(p.gastLogo)
    }

    @Test
    fun `logos survive the cache round trip`() {
        val a = German4kSportAntwort.parse(neu)!!
        assertEquals(a, German4kSportAntwort.parse(a.toJson()))
        val b = German4kSportAntwort.parse(alt)!!
        assertEquals(b, German4kSportAntwort.parse(b.toJson()))
    }

    @Test
    fun `initials drop club prefixes, numbers and fillers`() {
        assertEquals("EF", vereinsInitialen("Eintracht Frankfurt"))
        assertEquals("K", vereinsInitialen("1. FC Köln"))
        assertEquals("UB", vereinsInitialen("1. FC Union Berlin"))
        assertEquals("S", vereinsInitialen("VfB Stuttgart"))
        assertEquals("H", vereinsInitialen("TSG 1899 Hoffenheim"))
        assertEquals("WB", vereinsInitialen("SV Werder Bremen"))
        assertEquals("BL", vereinsInitialen("Bayer 04 Leverkusen"))
        assertEquals("PSG", vereinsInitialen("Paris Saint-Germain"))
        assertEquals("AM", vereinsInitialen("Atlético de Madrid"))
        assertEquals("BM", vereinsInitialen("Borussia Mönchengladbach"))
        assertEquals("RL", vereinsInitialen("RB Leipzig"))
    }

    @Test
    fun `initials are at most three, never empty for a real name`() {
        assertEquals("ABG", vereinsInitialen("Alpha Beta Gamma Delta"))
        assertEquals("F", vereinsInitialen("FC"))
        assertEquals("", vereinsInitialen(""))
        assertEquals("", vereinsInitialen("  1.  "))
        assertEquals("Ö", vereinsInitialen("österreich"))
    }
}
