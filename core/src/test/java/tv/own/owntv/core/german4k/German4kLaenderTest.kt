package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** German4K 3.2 (N8/C3): Vertrag der Länder-Ansicht von `/api/app/ultra/bereiche`. */
class German4kLaenderTest {
    private val beispiel = """
        {"ok":true,"typ":"live","bereiche":[{"id":10,"name":"🇬🇧 UHD","sender":12,"an":true,"standard":true}],
         "version":2,
         "laender":[
           {"land":"gb","name":"Großbritannien","flagge":"🇬🇧",
            "typen":{"live":{"an":true,"sender":312},"movie":{"an":false,"sender":900}},
            "bereiche":[{"id":10,"name":"🇬🇧 UHD","sender":12,"an":true,"standard":true,"typ":"live"},
                        {"id":11,"name":"🇬🇧 Filme","sender":900,"an":false,"standard":false,"typ":"movie"}]},
           {"land":"weitere","name":"Weitere","flagge":"★","typen":{},
            "bereiche":[{"id":99,"name":"Radio","sender":40,"an":false,"standard":false,"typ":"live"}]},
           {"land":"","name":"kaputt"}
         ],
         "meldung":"Fertig – 🇬🇧 Live: 312 Sender kommen dazu","bereiche_stand":"2026-10-16T20:00:00.000Z"}
    """.trimIndent()

    @Test
    fun `parses the real contract`() {
        val a = German4kLaenderAntwort.parse(beispiel)!!
        assertTrue(a.ok)
        assertEquals(2, a.version)
        assertEquals(2, a.laender.size) // Land ohne Code fällt weg
        val gb = a.laender[0]
        assertEquals("🇬🇧", gb.flagge)
        assertEquals(setOf("live", "movie"), gb.typen.keys) // nur vorhandene Typen
        assertEquals(312, gb.typen.getValue("live").sender)
        assertTrue(gb.typen.getValue("live").an)
        assertFalse(gb.typen.getValue("movie").an)
        assertTrue(gb.hatLandSchalter)
        assertEquals(listOf(11), gb.bereicheVon("movie").map { it.id })
        assertEquals("Fertig – 🇬🇧 Live: 312 Sender kommen dazu", a.meldung)
        assertEquals("2026-10-16T20:00:00.000Z", a.bereicheStand)
        assertNull(a.warnung)
    }

    @Test
    fun `weitere has no country switch, only single areas`() {
        val w = German4kLaenderAntwort.parse(beispiel)!!.laender[1]
        assertTrue(w.typen.isEmpty())
        assertFalse(w.hatLandSchalter)
        assertEquals(1, w.bereiche.size)
    }

    @Test
    fun `ok false keeps the old state and the reason`() {
        val a = German4kLaenderAntwort.parse(
            """{"ok":false,"grund":"Das hat sich nicht geändert.","laender":[{"land":"de","name":"Deutschland","typen":{"live":{"an":true,"sender":800}}}]}""",
        )!!
        assertFalse(a.ok)
        assertEquals("Das hat sich nicht geändert.", a.grund)
        assertEquals(1, a.laender.size)
        assertNull(a.meldung)
    }

    @Test
    fun `missing typ on an area means live`() {
        val a = German4kLaenderAntwort.parse("""{"ok":true,"laender":[{"land":"de","bereiche":[{"id":1,"name":"x","sender":1,"an":true}]}]}""")!!
        assertEquals("live", a.laender[0].bereiche[0].typ)
        assertEquals("DE", a.laender[0].name)
    }

    @Test
    fun `broken json is null`() {
        assertNull(German4kLaenderAntwort.parse("<html>"))
    }

    @Test
    fun `request bodies`() {
        val lesen = laenderAnfrage("dev", null)
        assertEquals("laender", lesen.getString("ansicht"))
        assertEquals("live", lesen.getString("typ"))
        assertFalse(lesen.has("an"))
        val land = laenderAnfrage("dev", German4kLaenderSchalter.Land("gb", "movie", true))
        assertEquals("gb", land.getString("land"))
        assertEquals("movie", land.getString("typ"))
        assertTrue(land.getBoolean("an"))
        assertFalse(land.has("id"))
        val bereich = laenderAnfrage("dev", German4kLaenderSchalter.Bereich(10, "live", false))
        assertEquals(10, bereich.getInt("id"))
        assertFalse(bereich.getBoolean("an"))
        assertFalse(bereich.has("land"))
        assertEquals("dev", bereich.getString("app_device_id"))
    }
}
