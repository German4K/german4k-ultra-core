package tv.own.owntv.core.german4k

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.german4k.German4kAudioSprache.Quelle
import tv.own.owntv.core.german4k.German4kAudioSprache.Spur

/** German4K 3.2.1 (AS): Auswahl a–d, Normalisierung, mpv-alang, Titel-Erkennung, Konfig `audio_standard`. */
class German4kAudioSpracheTest {
    private val a = German4kAudioSprache

    // --- Auswahl ---
    @Test fun aKundeGewinnt() {
        assertEquals("en", a.wirksam("eng", "tr", "pl"))
        assertEquals(Quelle.KUNDE, a.quelle("eng", "tr", "pl"))
    }

    @Test fun aKeineBewusstGesetztBleibtLeer() {
        assertEquals("", a.wirksam("", "de", "tr"))
        assertEquals(Quelle.KUNDE, a.quelle("", null, null))
    }

    @Test fun aUnbekannterKundenwertUnveraendert() = assertEquals("xyz1", a.wirksam("xyz1", null, null))

    @Test fun bServerWennNieGesetzt() {
        assertEquals("tr", a.wirksam(null, "tr", "pl"))
        assertEquals("sq", a.wirksam(null, "alb", "de"))
        assertEquals(Quelle.SERVER, a.quelle(null, "tr", "pl"))
    }

    @Test fun bKaputterServerwertFaelltDurch() = assertEquals("pl", a.wirksam(null, "quatsch", "pl"))

    @Test fun cGeraetespracheAusserDeEn() {
        for (g in listOf("tr", "sq", "pl", "ro", "ru", "ar")) assertEquals(g, a.wirksam(null, null, g))
        assertEquals("tr", a.wirksam(null, "", "tr"))
        assertEquals(Quelle.GERAET, a.quelle(null, null, "tr"))
    }

    @Test fun dDeutschBeiDeEnOderNichts() {
        assertEquals("de", a.wirksam(null, null, "de"))
        assertEquals("de", a.wirksam(null, null, "en"))
        assertEquals("de", a.wirksam(null, null, null))
        assertEquals("de", a.wirksam(null, null, ""))
        assertEquals(Quelle.STANDARD, a.quelle(null, null, "en"))
    }

    // --- Normalisierung ---
    @Test fun deDeuGerGleich() {
        for (c in listOf("de", "deu", "ger", "DE", "GER", "de-DE", "de_AT")) assertEquals(c, "de", a.normalisiere(c))
        assertTrue(a.gleich("ger", "deu"))
        assertTrue(a.gleich("de", "ger"))
        assertFalse(a.gleich("de", "eng"))
        assertFalse(a.gleich(null, null))
    }

    @Test fun weitereBUndTCodes() {
        assertEquals("sq", a.normalisiere("alb")); assertEquals("sq", a.normalisiere("sqi"))
        assertEquals("ro", a.normalisiere("rum")); assertEquals("ro", a.normalisiere("ron"))
        assertEquals("fr", a.normalisiere("fre")); assertEquals("tr", a.normalisiere("tur"))
        assertEquals("en", a.normalisiere("eng")); assertEquals("he", a.normalisiere("iw"))
    }

    @Test fun unbrauchbarIstNull() {
        for (c in listOf(null, "", " ", "und", "mul", "qaa", "zz", "deutsch", "1de")) assertNull(c, a.normalisiere(c))
    }

    @Test fun varianten() {
        assertEquals(listOf("de", "deu", "ger"), a.varianten("de"))
        assertEquals(listOf("de", "deu", "ger"), a.varianten("ger"))
        assertEquals(listOf("tr", "tur"), a.varianten("tr"))
        assertEquals(listOf("sq", "sqi", "alb"), a.varianten("sq"))
        assertEquals(emptyList<String>(), a.varianten(""))
    }

    // --- mpv ---
    @Test fun mpvAlang() {
        assertEquals("de,deu,ger", a.mpvAlang("de"))
        assertEquals("de,deu,ger", a.mpvAlang("deu"))
        assertEquals("en,eng", a.mpvAlang("eng"))
        assertEquals("tr,tur", a.mpvAlang("tr"))
        assertEquals("", a.mpvAlang(""))
    }

    // --- Titel ohne Sprachcode ---
    @Test fun titel() {
        assertEquals("de", a.ausTitel("Deutsch"))
        assertEquals("de", a.ausTitel("German 5.1"))
        assertEquals("de", a.ausTitel("DE AC3"))
        assertEquals("de", a.ausTitel("[GER] Dolby"))
        assertEquals("en", a.ausTitel("English (Original)"))
        assertEquals("tr", a.ausTitel("Türkçe"))
        assertNull(a.ausTitel("Audio de la película"))
        assertNull(a.ausTitel("Stereo"))
        assertNull(a.ausTitel(null))
    }

    @Test fun titelTrefferNurOhnePassendenCode() {
        val nurTitel = listOf(Spur(0, null, "English", true), Spur(1, null, "Deutsch", false))
        assertEquals(1, a.titelTreffer(nurTitel, "de")?.id)
        // Sprachcode vorhanden → Exo/mpv wählen selbst
        assertNull(a.titelTreffer(listOf(Spur(0, "eng", "", true), Spur(1, "ger", "", false)), "de"))
        // schon gewählt
        assertNull(a.titelTreffer(listOf(Spur(0, null, "Deutsch", true), Spur(1, null, "English", false)), "de"))
        // kein Treffer → Standardspur bleibt
        assertNull(a.titelTreffer(listOf(Spur(0, null, "Stereo", true), Spur(1, "und", "Kommentar", false)), "de"))
        // Exo meldet „und" für Spuren ohne Sprache
        assertEquals(1, a.titelTreffer(listOf(Spur(0, "und", "English", true), Spur(1, "und", "Deutsch", false)), "de")?.id)
        // Wunsch leer
        assertNull(a.titelTreffer(nurTitel, ""))
    }

    // --- Konfig vom Server ---
    @Test fun konfigAudioStandard() {
        assertNull(German4kKonfigWerte.parse(JSONObject()).audioStandard)
        assertEquals("tr", German4kKonfigWerte.parse(JSONObject("""{"audio_standard":"tr"}""")).audioStandard)
        assertEquals("de", German4kKonfigWerte.parse(JSONObject("""{"audio_standard":"ger"}""")).audioStandard)
        assertNull(German4kKonfigWerte.parse(JSONObject("""{"audio_standard":"quatsch"}""")).audioStandard)
        assertNull(German4kKonfigWerte.parse(JSONObject("""{"audio_standard":5}""")).audioStandard)
        val rund = German4kKonfigWerte.parse(German4kKonfigWerte(audioStandard = "pl").toJson())
        assertEquals("pl", rund.audioStandard)
    }

    // --- German4K 3.2.1 (p-15): Betreiber — Multi-Audio-VODs starten deutsch, auch bei englischem Gerät ---
    @Test fun p15EnglischesGeraetFrischInstalliertStartetDeutsch() {
        // frische Installation / nie gewählt = Schlüssel fehlt (null), Server ohne audio_standard
        for (geraet in listOf("en", "EN", "en-US", "en_GB", "eng")) {
            assertEquals(geraet, "de", a.wirksam(null, null, geraet))
            assertEquals(geraet, Quelle.STANDARD, a.quelle(null, null, geraet))
        }
        assertEquals("de,deu,ger", a.mpvAlang(a.wirksam(null, null, "en")))
    }

    @Test fun p15BewussteWahlBleibt() {
        assertEquals("en", a.wirksam("en", null, "en"))
        assertEquals("en", a.wirksam("eng", "de", "de"))
        assertEquals("", a.wirksam("", null, "en")) // „Keine“ bewusst gewählt
        assertEquals(Quelle.KUNDE, a.quelle("en", null, "en"))
    }
}
