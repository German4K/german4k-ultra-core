package tv.own.owntv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** German4K 3.2.1 (B7): hochfps-Sender starten auf ExoPlayer — Tabelle aller Kombinationen. */
class LiveFpsWahlTest {
    /** Review B7: Kundenwahl mpv sperrt hochfps dauerhaft, automatischer Exo-Fehler nur 24 h. */
    @Test
    fun kundenwahl_mpv_dauerhaft_automatik_24h() {
        val kunde = LiveFpsWahl.fehlerZeit(kundenWahlMpv = true, jetztMs = jetzt)
        val auto = LiveFpsWahl.fehlerZeit(kundenWahlMpv = false, jetztMs = jetzt)
        assertEquals(Long.MAX_VALUE, kunde)
        assertEquals(jetzt, auto)
        val inEinemJahr = jetzt + 365L * 24 * 60 * 60 * 1000
        assertFalse(LiveFpsWahl.exoWegenHochFps(true, hoch.copy(exoFehlerMs = kunde), EnginePreference.EXO_FIRST, true, false, false, inEinemJahr))
        val nach25h = jetzt + LiveFpsWahl.FEHLER_SPERRE_MS + 3_600_000L
        assertFalse(LiveFpsWahl.exoWegenHochFps(true, hoch.copy(exoFehlerMs = auto), EnginePreference.EXO_FIRST, true, false, false, jetzt + 1000))
        assertTrue(LiveFpsWahl.exoWegenHochFps(true, hoch.copy(exoFehlerMs = auto), EnginePreference.EXO_FIRST, true, false, false, nach25h))
        // Speicherformat trägt den Merker verlustfrei
        val m = LinkedHashMap<String, LiveFpsEintrag>()
        LiveFpsTabelle.setzen(m, "k", hoch.copy(exoFehlerMs = kunde))
        assertEquals(kunde, LiveFpsTabelle.lesen(LiveFpsTabelle.schreiben(m))["k"]!!.exoFehlerMs)
    }

    private val jetzt = 10_000_000_000L
    private val hoch = LiveFpsEintrag(50f, jetzt - 1000)
    private val niedrig = LiveFpsEintrag(25f, jetzt - 1000)

    private fun wahl(
        schalter: Boolean = true,
        e: LiveFpsEintrag? = hoch,
        setting: EnginePreference = EnginePreference.EXO_FIRST,
        pin: Boolean? = true,
        panel: Boolean = false,
        drm: Boolean = false,
    ) = LiveFpsWahl.exoWegenHochFps(schalter, e, setting, pin, panel, drm, jetzt)

    /** Soll-Tabelle (Schalter an, hochfps, kein frischer Exo-Fehler, kein DRM): Einstellung, Pin (m=mpv, e=Exo, -=keiner), Panel lehnt ab → Exo wegen hochfps. */
    private val soll = """
        EXO_FIRST - n F
        EXO_FIRST - j F
        EXO_FIRST m n T
        EXO_FIRST m j T
        EXO_FIRST e n F
        EXO_FIRST e j F
        MPV_FIRST - n T
        MPV_FIRST - j F
        MPV_FIRST m n T
        MPV_FIRST m j T
        MPV_FIRST e n F
        MPV_FIRST e j F
        EXO_ONLY - n F
        EXO_ONLY - j F
        EXO_ONLY m n T
        EXO_ONLY m j T
        EXO_ONLY e n F
        EXO_ONLY e j F
        MPV_ONLY - n F
        MPV_ONLY - j F
        MPV_ONLY m n F
        MPV_ONLY m j F
        MPV_ONLY e n F
        MPV_ONLY e j F
    """.trimIndent().lines()

    @Test
    fun `volle Tabelle`() {
        var faelle = 0
        for (zeile in soll) {
            val (s, p, pa, erg) = zeile.split(' ')
            val setting = EnginePreference.valueOf(s)
            val pin = when (p) { "m" -> true; "e" -> false; else -> null }
            val panel = pa == "j"
            assertEquals(zeile, erg == "T", wahl(setting = setting, pin = pin, panel = panel))
            // Jede Sperre schlägt die Umleitung.
            assertFalse(wahl(schalter = false, setting = setting, pin = pin, panel = panel))
            assertFalse(wahl(drm = true, setting = setting, pin = pin, panel = panel))
            assertFalse(wahl(e = null, setting = setting, pin = pin, panel = panel))
            assertFalse(wahl(e = niedrig, setting = setting, pin = pin, panel = panel))
            assertFalse(wahl(e = hoch.copy(exoFehlerMs = jetzt - 60_000), setting = setting, pin = pin, panel = panel))
            faelle++
        }
        assertEquals(24, faelle)
    }

    @Test
    fun `Kernfaelle ausdruecklich`() {
        assertTrue("mpv-Pin, Exo zuerst", wahl(pin = true))
        assertTrue("mpv zuerst eingestellt", wahl(setting = EnginePreference.MPV_FIRST, pin = null))
        assertTrue("Nur Exo + mpv-Pin", wahl(setting = EnginePreference.EXO_ONLY, pin = true))
        assertFalse("Nur mpv bleibt mpv", wahl(setting = EnginePreference.MPV_ONLY, pin = null))
        assertFalse("Nur mpv auch mit Pin", wahl(setting = EnginePreference.MPV_ONLY, pin = true))
        assertFalse("startet ohnehin auf Exo", wahl(pin = null))
    }

    @Test
    fun `kein Kreislauf - Exo-Fehler der letzten 24 h sperrt, danach wieder frei`() {
        val tag = LiveFpsWahl.FEHLER_SPERRE_MS
        assertFalse(wahl(e = hoch.copy(exoFehlerMs = jetzt - 1)))
        assertFalse(wahl(e = hoch.copy(exoFehlerMs = jetzt - tag + 1)))
        assertTrue(wahl(e = hoch.copy(exoFehlerMs = jetzt - tag)))
        assertTrue(wahl(e = hoch.copy(exoFehlerMs = 0L)))
        // Uhr zurückgestellt: Fehler „in der Zukunft“ gilt als frisch.
        assertFalse(wahl(e = hoch.copy(exoFehlerMs = jetzt + 5_000)))
    }

    @Test
    fun `Schwelle 48 fps`() {
        assertFalse(wahl(e = LiveFpsEintrag(47.9f, jetzt)))
        assertTrue(wahl(e = LiveFpsEintrag(48f, jetzt)))
        assertTrue(wahl(e = LiveFpsEintrag(59.94f, jetzt)))
    }

    @Test
    fun `Tabelle - LRU auf 500, Rundreise, kaputte Zeilen`() {
        val m = LinkedHashMap<String, LiveFpsEintrag>()
        for (i in 0 until 510) LiveFpsTabelle.setzen(m, "k$i", LiveFpsEintrag(50f, i.toLong()))
        assertEquals(500, m.size)
        assertFalse("k0" in m)
        assertTrue("k509" in m)
        // Berühren schiebt nach hinten, Ältester fällt.
        LiveFpsTabelle.setzen(m, "k10", LiveFpsEintrag(25f, 999))
        LiveFpsTabelle.setzen(m, "neu", LiveFpsEintrag(50f, 1000))
        assertTrue("k10" in m)
        assertFalse("k11" in m)
        val zurueck = LiveFpsTabelle.lesen(LiveFpsTabelle.schreiben(m) + "\nkaputt\n\tx\t1\nz\tabc\t1")
        assertEquals(m, zurueck)
        assertEquals("neu", zurueck.keys.last())
        assertEquals(LiveFpsEintrag(50f, 3, 0), LiveFpsTabelle.lesen("a\t50.0\t3").getValue("a"))
    }

    @Test
    fun `Schluessel ohne URL im Speicher`() {
        assertEquals("1:LIVE:9", LiveFpsTabelle.schluessel("1:LIVE:9", "http://x/y.ts?token=1"))
        val k = LiveFpsTabelle.schluessel(null, "http://x/y.ts?token=1")
        assertTrue(k.startsWith("u:"))
        assertFalse(k.contains("http"))
    }
}
