package tv.own.owntv.core.german4k

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** German4K 3.2: Notschalter (fehlt = an, nur "aus" = alt) und `konfig` mit Standardwerten. */
class German4kSchalterKonfigTest {

    @After fun zuruecksetzen() { German4kFeatures.set(emptyMap()); German4kKonfig.set(null) }

    private fun antwort(extra: String = "") = German4kPanelAnswer.parse("""{"mac":"aa","sources":[]$extra}""")

    // ---- Features ---------------------------------------------------------------------------

    // German4K 3.2.1 (t-15): vor geladenen Schaltern gilt „aus“; Cache zählt als geladen, aber nicht als frisch.
    // t-16: ein Cache ohne den Schalter (3.2) zählt nicht.
    @Test fun `anGeladen - vor den Schaltern aus, Cache geladen aber nicht frisch`() {
        German4kFeatures.vergessenFuerTests()
        assertTrue(German4kFeatures.an(German4kSchalter.ZAP_ERST_SCHLIESSEN))
        assertFalse(German4kFeatures.anGeladen(German4kSchalter.ZAP_ERST_SCHLIESSEN))
        // German4K 3.2.1 (t-16): 3.2-Cache ohne den Schalter → weiter „aus“ bis zur frischen Antwort
        German4kFeatures.set(mapOf("sport" to "an"), frisch = false)
        assertFalse(German4kFeatures.anGeladen(German4kSchalter.ZAP_ERST_SCHLIESSEN))
        assertFalse(German4kFeatures.frischGeladen.value)
        // Cache, in dem der Server den Schalter schon genannt hat → gilt
        German4kFeatures.set(mapOf(German4kSchalter.ZAP_ERST_SCHLIESSEN to "an"), frisch = false)
        assertTrue(German4kFeatures.anGeladen(German4kSchalter.ZAP_ERST_SCHLIESSEN))
        // frische Antwort ohne den Schalter → „fehlt = an“
        German4kFeatures.set(mapOf("sport" to "an"))
        assertTrue(German4kFeatures.anGeladen(German4kSchalter.ZAP_ERST_SCHLIESSEN))
        German4kFeatures.set(mapOf(German4kSchalter.ZAP_ERST_SCHLIESSEN to "aus"))
        assertFalse(German4kFeatures.anGeladen(German4kSchalter.ZAP_ERST_SCHLIESSEN))
        assertTrue(German4kFeatures.frischGeladen.value)
    }

    @Test fun `fehlender Schalter ist an`() {
        German4kFeatures.set(mapOf("sport" to "an"))
        assertTrue(German4kFeatures.an(German4kSchalter.TOP10))
        assertTrue(German4kFeatures.an(German4kSchalter.ERINNERN))
    }

    @Test fun `nur exakt aus schaltet ab`() {
        German4kFeatures.set(mapOf("top10" to "aus", "erinnern" to "an", "zapp_liste" to "entwicklung", "laender_v2" to "AUS", "vereinslogos" to ""))
        assertFalse(German4kFeatures.an("top10"))
        assertTrue(German4kFeatures.an("erinnern"))
        assertTrue(German4kFeatures.an("zapp_liste"))
        assertTrue(German4kFeatures.an("laender_v2"))
        assertTrue(German4kFeatures.an("vereinslogos"))
    }

    @Test fun `u-5 Bedienschalter - Namen fest, fehlend an, aus schaltet ab`() {
        // Der Server (ULTRA_FEATURES) muss genau diese Namen kennen.
        assertEquals("tastatur_zurueck", German4kSchalter.TASTATUR_ZURUECK)
        assertEquals("knopf_fokus", German4kSchalter.KNOPF_FOKUS)
        assertEquals("shell_fokus", German4kSchalter.SHELL_FOKUS)
        German4kFeatures.set(emptyMap())
        assertTrue(German4kFeatures.an(German4kSchalter.TASTATUR_ZURUECK))
        assertTrue(German4kFeatures.an(German4kSchalter.KNOPF_FOKUS))
        assertTrue(German4kFeatures.an(German4kSchalter.SHELL_FOKUS))
        German4kFeatures.set(mapOf("tastatur_zurueck" to "aus", "knopf_fokus" to "aus", "shell_fokus" to "aus"))
        assertFalse(German4kFeatures.an(German4kSchalter.TASTATUR_ZURUECK))
        assertFalse(German4kFeatures.an(German4kSchalter.KNOPF_FOKUS))
        assertFalse(German4kFeatures.an(German4kSchalter.SHELL_FOKUS))
    }

    @Test fun `u-6 Bedienschalter - Namen fest, fehlend an, aus schaltet ab`() {
        // Der Server (ULTRA_FEATURES) muss genau diese Namen kennen.
        val namen = listOf(
            German4kSchalter.EINSTELLUNGEN_FOKUS to "einstellungen_fokus",
            German4kSchalter.AVATAR_FOKUS to "avatar_fokus",
            German4kSchalter.EINSTELLUNGEN_SUCHE to "einstellungen_suche",
            German4kSchalter.AUSWAHL_FOKUS to "auswahl_fokus",
            German4kSchalter.LEISTE_UMBRUCH to "leiste_umbruch",
            German4kSchalter.LIVE_FOKUS to "live_fokus",
            German4kSchalter.FOKUS_RUECKFALL to "fokus_rueckfall",
            German4kSchalter.DETAIL_LADEN to "detail_laden",
            German4kSchalter.SUCHE_ZURUECK to "suche_zurueck",
            German4kSchalter.ZURUECK_PFEIL to "zurueck_pfeil",
            German4kSchalter.FOLGEN_FOKUS to "folgen_fokus",
            German4kSchalter.EPG_ZURUECK to "epg_zurueck",
            German4kSchalter.MEHR_TEXTE to "mehr_texte",
        )
        namen.forEach { (konstante, name) -> assertEquals(name, konstante) }
        German4kFeatures.set(emptyMap())
        namen.forEach { (konstante, _) -> assertTrue(German4kFeatures.an(konstante)) }
        German4kFeatures.set(namen.associate { it.second to "aus" })
        namen.forEach { (konstante, _) -> assertFalse(German4kFeatures.an(konstante)) }
    }

    @Test fun `an weicht bewusst von visible ab`() {
        German4kFeatures.set(emptyMap())
        assertFalse(German4kFeatures.visible("sport"))
        assertTrue(German4kFeatures.an("sport"))
    }

    @Test fun `Erinnern aus sperrt das Repository`() {
        assertTrue(German4kErinnerungen.aktiv())
        German4kFeatures.set(mapOf("erinnern" to "aus"))
        assertFalse(German4kErinnerungen.aktiv())
    }

    @Test fun `Konferenz-Karte aus sortiert die Konferenz wie ein Spiel ein`() {
        val heute = LocalDate.of(2026, 10, 17)
        val start = java.time.Instant.parse("2026-10-17T13:30:00Z")
        val konf = German4kSpiel(-900001, "Bundesliga-Konferenz", "Bundesliga-Konferenz", "5 Spiele", "Bundesliga", start, "",
            "geplant", 1.0, German4kSpiel.ART_KONFERENZ, emptyList(), false, null, null)
        val mit = regale(listOf(konf), heute, konferenzKarte = true)
        assertEquals(1, mit.konferenzen.size); assertTrue(mit.heute.isEmpty())
        val ohne = regale(listOf(konf), heute, konferenzKarte = false)
        assertTrue(ohne.konferenzen.isEmpty()); assertEquals(listOf(konf), ohne.heute)
        German4kFeatures.set(mapOf("konferenz_karte" to "aus"))
        assertTrue(regale(listOf(konf), heute).konferenzen.isEmpty())
    }

    // ---- Konfig -----------------------------------------------------------------------------

    @Test fun `Konfig fehlt - Standardwerte`() {
        val a = antwort()
        assertNull(a.konfig)
        German4kKonfig.set(a.konfig)
        assertEquals(5, German4kKonfig.erinnernVorlaufSendungMin)
        assertEquals(10, German4kKonfig.erinnernVorlaufSpielMin)
        assertEquals(6L * 3600_000, German4kKonfig.angesagtFrischMs)
        assertEquals(setOf(458, 460), German4kKonfig.leitungCodes)
        assertEquals("App", German4kKonfig.text("angesagt_titel", "App"))
    }

    @Test fun `Konfig teilweise - Rest Standard`() {
        val k = antwort(""","konfig":{"erinnern_vorlauf_spiel_min":15}""").konfig!!
        assertEquals(15, k.erinnernVorlaufSpielMin)
        assertEquals(5, k.erinnernVorlaufSendungMin)
        assertEquals(6, k.angesagtFrischH)
        assertEquals(setOf(458, 460), k.leitungCodes)
        assertTrue(k.texte.isEmpty())
    }

    @Test fun `Konfig vollstaendig`() {
        val k = antwort(""","konfig":{"erinnern_vorlauf_sendung_min":2,"erinnern_vorlauf_spiel_min":20,"angesagt_frisch_h":12,"leitung_codes":[458,509]}""").konfig!!
        assertEquals(2, k.erinnernVorlaufSendungMin); assertEquals(20, k.erinnernVorlaufSpielMin)
        assertEquals(12, k.angesagtFrischH); assertEquals(setOf(458, 509), k.leitungCodes)
    }

    @Test fun `Konfig ungueltig - Standard greift`() {
        val k = antwort(""","konfig":{"erinnern_vorlauf_sendung_min":"viel","erinnern_vorlauf_spiel_min":-3,"angesagt_frisch_h":0.5,"leitung_codes":[200,"x"],"texte":"kaputt"}""").konfig!!
        assertEquals(German4kKonfigWerte(), k)
        // Kein Objekt (Zahl, Text) → wie fehlend.
        assertNull(antwort(""","konfig":7""").konfig)
        assertEquals(German4kKonfigWerte(), German4kKonfigWerte.parse(JSONObject("""{"angesagt_frisch_h":9999,"leitung_codes":[]}""")))
    }

    @Test fun `Texte ueberschreiben nur bekannte Schluessel`() {
        val k = antwort(""","konfig":{"texte":{"angesagt_titel":"🔥 Gerade heiß","top10_untertitel":"  ","fremd":"x","leitung_belegt_text":"Anderes Gerät aktiv"}}""").konfig!!
        German4kKonfig.set(k)
        assertEquals("🔥 Gerade heiß", German4kKonfig.text("angesagt_titel", "App"))
        assertEquals("App", German4kKonfig.text("top10_untertitel", "App")) // leer → App-Text
        assertEquals("App", German4kKonfig.text("fremd", "App"))
        assertEquals("App", German4kKonfig.text("top10_filme_titel", "App")) // fehlt → App-Text
        assertEquals("Anderes Gerät aktiv", German4kKonfig.text("leitung_belegt_text", "App"))
    }

    @Test fun `Konfig ueberlebt den Cache`() {
        val a = antwort(""","konfig":{"angesagt_frisch_h":3,"leitung_codes":[460],"texte":{"konferenz_titel_bl":"BL live"}}""")
        val b = German4kPanelAnswer.parse(a.toJson())
        assertEquals(a.konfig, b.konfig)
        assertNull(German4kPanelAnswer.parse(antwort().toJson()).konfig)
    }
}
