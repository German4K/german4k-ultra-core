package tv.own.owntv.player

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** German4K 3.2.1 (p-13): Review-Funde Player-Kern (Kandidat 6). */
class SpurP13Test {
    @Before fun vorher() { ExoUhr.zuruecksetzen(); LiveStreamQuirks.clearForTest() }
    @After fun nachher() { ExoUhr.zuruecksetzen(); LiveStreamQuirks.clearForTest() }

    // --- 5: Felder, die der Einstellungs-init schreibt, stehen vor ihm ---
    @Test fun live_engine_init_schreibt_nur_vorher_deklarierte_felder() {
        val datei = listOf(
            "src/main/java/tv/own/owntv/player/LivePreviewEngine.kt",
            "player-core/src/main/java/tv/own/owntv/player/LivePreviewEngine.kt",
        ).map(::File).first { it.exists() }
        val zeilen = datei.readLines()
        val decl = Regex("""^    (?:@\S+\s+)*(?:private |internal |override |protected )?(?:@\S+\s+)*(?:lateinit )?(?:var|val) (\w+)""")
        val deklariert = HashMap<String, Int>()
        zeilen.forEachIndexed { i, l -> decl.find(l)?.let { deklariert.putIfAbsent(it.groupValues[1], i) } }
        val fehler = ArrayList<String>()
        var initBloecke = 0
        zeilen.forEachIndexed { i, l ->
            if (!Regex("""^    init\s*\{""").containsMatchIn(l)) return@forEachIndexed
            initBloecke++
            var tiefe = 0; var j = i
            do { tiefe += zeilen[j].count { it == '{' } - zeilen[j].count { it == '}' }; j++ } while (tiefe > 0)
            val rumpf = zeilen.subList(i, j).joinToString("\n")
            deklariert.filter { (_, zeile) -> zeile >= j }.keys
                .filter { Regex("""\b$it\s*=[^=]""").containsMatchIn(rumpf) }
                .forEach { fehler += "$it (init Zeile ${i + 1})" }
        }
        assertTrue("init-Blöcke gefunden", initBloecke >= 2)
        assertEquals("nach dem init deklariert, im init beschrieben", emptyList<String>(), fehler)
        // Die vier aus dem Fund ausdrücklich.
        val settingsInit = zeilen.indexOfFirst { it.contains("settings.liveBufferSeconds.onEach") }
        listOf("hwDecodingEnabled", "defaultZoom", "defaultVolume", "surroundMode").forEach {
            assertTrue("$it vor dem init", deklariert.getValue(it) < settingsInit)
        }
    }

    // --- 6: 460 / Kachel merkt den Host nicht als Ein-Sitzungs-Panel ---
    @Test fun nur_458_407_ausserhalb_der_kachel_merken_eine_sitzung() {
        assertTrue(LiveStreamQuirks.merktEineSitzung(458, kachel = false))
        assertTrue(LiveStreamQuirks.merktEineSitzung(PlayerErrors.HTTP_PROXY_AUTH, kachel = false))
        assertFalse(LiveStreamQuirks.merktEineSitzung(460, kachel = false))
        assertFalse(LiveStreamQuirks.merktEineSitzung(458, kachel = true))
        assertFalse(LiveStreamQuirks.merktEineSitzung(460, kachel = true))
    }

    // --- 8: ExoUhr zählt nur belegten Tonstillstand, Erfolg setzt zurück ---
    @Test fun ton_stillstand_braucht_ton_puffer_und_stehende_tonposition() {
        assertTrue(ExoUhr.tonStillstandBelegt(hatTon = true, tonPositionLief = false, underruns = 0, pufferMs = 5_000))
        assertTrue(ExoUhr.tonStillstandBelegt(hatTon = true, tonPositionLief = true, underruns = 2, pufferMs = 5_000))
        // toter Sender: kein Puffer
        assertFalse(ExoUhr.tonStillstandBelegt(hatTon = true, tonPositionLief = false, underruns = 0, pufferMs = 300))
        // stumm/ohne Ton: nichts belegt
        assertFalse(ExoUhr.tonStillstandBelegt(hatTon = false, tonPositionLief = false, underruns = 0, pufferMs = 5_000))
        // Ton lief ohne Underrun → Ausgabe nicht schuld
        assertFalse(ExoUhr.tonStillstandBelegt(hatTon = true, tonPositionLief = true, underruns = 0, pufferMs = 5_000))
    }

    @Test fun melde_ohne_beleg_zaehlt_nicht_und_erfolg_setzt_zurueck() {
        ExoUhr.melde(belegt = false); ExoUhr.melde(belegt = false); ExoUhr.melde(belegt = false)
        assertEquals(0, ExoUhr.trefferStand())
        ExoUhr.melde(belegt = true); ExoUhr.melde(belegt = true)
        assertEquals(2, ExoUhr.trefferStand())
        ExoUhr.erfolg()
        assertEquals(0, ExoUhr.trefferStand())
    }

    // --- Zusatz (K6): Text-Ersatz unter mpv bleibt Ersatz, der Bild-Wunsch bleibt der Wunsch ---
    private fun t(id: Int, lang: String?, label: String, image: Boolean = false, selected: Boolean = false) =
        TrackOption(label = label, mpvId = id, selected = selected, image = image, lang = lang, typeIndex = id, labelKind = TrackLabelKind.SUBTITLE)

    private val exoSubs = listOf(
        t(0, "de", "Deutsch (VobSub)", true), t(1, "de", "Deutsch (PGS)", true),
        t(2, "en", "English SDH (VobSub)", true), t(3, "en", "English SDH (PGS)", true), t(4, "de", "Deutsch (Text)"),
    )

    @Test fun ersatz_text_traegt_den_bild_wunsch_zurueck_nach_exo() {
        val wunsch = exoSubs[1].alsWunsch() // Kunde: Deutsch (PGS) auf Exo
        val ersatzAufMpv = t(5, "ger", "Deutsch (Text)", selected = true)
        val weiter = UntertitelWechsel.wunschBeiWechsel(ersatzAufMpv, wunsch)
        assertEquals(wunsch, weiter)
        assertEquals("Deutsch (PGS)", UntertitelWechsel.finde(exoSubs, weiter!!)?.label)
    }

    @Test fun echte_textwahl_oder_andere_sprache_bleibt_auswahl() {
        val text = t(5, "ger", "Deutsch (Text)", selected = true)
        // Kunde hat Text selbst gewählt → selectSubtitle setzte den Wunsch auf Text.
        assertEquals(text.alsWunsch(), UntertitelWechsel.wunschBeiWechsel(text, text.alsWunsch()))
        // ohne Wunsch: die gewählte Spur
        assertEquals(text.alsWunsch(), UntertitelWechsel.wunschBeiWechsel(text, null))
        // Bild-Wunsch Englisch, gewählt deutscher Text → kein Ersatz, sondern Auswahl
        assertEquals(text.alsWunsch(), UntertitelWechsel.wunschBeiWechsel(text, exoSubs[3].alsWunsch()))
        // nichts gewählt → null (Aufrufer entscheidet)
        assertEquals(null, UntertitelWechsel.wunschBeiWechsel(null, exoSubs[1].alsWunsch()))
    }
}
