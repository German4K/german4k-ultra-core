package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.player.UntertitelWechsel.BildWeg

/** German4K 3.2.1 (P7): Spur-Abbildung über Engine-Wechsel und die Bild-Untertitel-Entscheidung. */
class UntertitelWechselTest {
    private fun sub(i: Int, label: String, lang: String?, image: Boolean, codec: String? = null) =
        TrackOption(label = label, mpvId = i + 1, selected = false, image = image, codec = codec, lang = lang, typeIndex = i, labelKind = TrackLabelKind.SUBTITLE)

    // Doctor Strange (Fire TV 4K Max, 22.10.): so sieht mpv die Datei …
    private val mpv = listOf(
        sub(0, "Deutsch (VobSub)", "ger", true, "dvd_subtitle"),
        sub(1, "Deutsch (PGS)", "ger", true, "hdmv_pgs_subtitle"),
        sub(2, "English SDH (VobSub)", "eng", true, "dvd_subtitle"),
        sub(3, "English SDH (PGS)", "eng", true, "hdmv_pgs_subtitle"),
    )
    // … und so ExoPlayer (Sprachen normalisiert).
    private val exo = listOf(
        sub(0, "Deutsch (VobSub)", "de", true),
        sub(1, "Deutsch (PGS)", "de", true),
        sub(2, "English SDH (VobSub)", "en", true),
        sub(3, "English SDH (PGS)", "en", true),
    )

    @Test fun `ger und de sind dieselbe sprache`() {
        assertTrue(UntertitelWechsel.sprachGleich("ger", "de"))
        assertTrue(UntertitelWechsel.sprachGleich("deu", "de-DE"))
        assertTrue(UntertitelWechsel.sprachGleich("fre", "fr"))
        assertTrue(subtitleLanguageMatches("ger", "de"))
        assertFalse(UntertitelWechsel.sprachGleich("ger", "en"))
        assertFalse(UntertitelWechsel.sprachGleich(null, "de"))
    }

    @Test fun `mpv nach exo findet deutsch pgs trotz ger gegen de`() {
        // Der Befund: „no text track matches the picked subtitle (index=1 lang=ger)".
        assertEquals("Deutsch (PGS)", UntertitelWechsel.finde(exo, mpv[1].alsWunsch())?.label)
    }

    @Test fun `exo nach mpv findet dieselbe spur auch ohne titel`() {
        val w = exo[3].alsWunsch().copy(label = null)
        assertEquals(3, UntertitelWechsel.finde(mpv, w)?.typeIndex)
    }

    @Test fun `titel schlaegt verschobene ordnungszahl`() {
        // Exo kennt eine Spur nicht → alle rücken eins auf; der Titel trifft trotzdem.
        val exoOhneErste = exo.drop(1).mapIndexed { i, t -> t.copy(typeIndex = i, mpvId = i + 1) }
        assertEquals("Deutsch (PGS)", UntertitelWechsel.finde(exoOhneErste, mpv[1].alsWunsch())?.label)
    }

    @Test fun `ohne bild erlaubt springt gleiche sprache als text ein`() {
        val mitText = mpv + sub(4, "Deutsch (Text)", "ger", false, "subrip")
        val ziel = UntertitelWechsel.finde(mitText, exo[1].alsWunsch(), bildErlaubt = false)
        assertEquals("Deutsch (Text)", ziel?.label)
    }

    @Test fun `ohne bild erlaubt und ohne text gibt es keinen treffer`() {
        assertNull(UntertitelWechsel.finde(mpv, exo[1].alsWunsch(), bildErlaubt = false))
    }

    @Test fun `andere sprache wird nie geraten`() {
        val nurEnglisch = mpv.filter { it.lang == "eng" }
        assertNull(UntertitelWechsel.finde(nurEnglisch, exo[1].alsWunsch().copy(label = null)))
    }

    @Test fun `ton ohne titel folgt sprache`() {
        val ton = listOf(
            TrackOption("", 1, false, lang = "ger", typeIndex = 0),
            TrackOption("", 2, false, lang = "eng", typeIndex = 1),
        )
        val w = SpurWunsch(lang = "en", image = false, ordinal = 0, label = null) // Exo-Ordnung anders
        assertEquals(2, UntertitelWechsel.finde(ton, w)?.mpvId)
    }

    @Test fun `cc platzhalter nur wo cc gehen kann`() {
        assertTrue(UntertitelWechsel.istCcPlatzhalter("eia_608"))
        assertFalse(UntertitelWechsel.istCcPlatzhalter("hdmv_pgs_subtitle"))
        assertFalse(UntertitelWechsel.ccZeigen(2160, glKaputt = false))
        assertTrue(UntertitelWechsel.ccZeigen(1080, glKaputt = false))
        assertFalse(UntertitelWechsel.ccZeigen(720, glKaputt = true))
        assertTrue(UntertitelWechsel.ccZeigen(null, glKaputt = false))
    }

    @Test fun `bild untertitel weg`() {
        assertEquals(BildWeg.MPV_ZEICHNET, UntertitelWechsel.bildWeg(direkt = false, live = false, exoGescheitert = true))
        assertEquals(BildWeg.AN_EXO, UntertitelWechsel.bildWeg(direkt = true, live = false, exoGescheitert = false))
        assertEquals(BildWeg.HINWEIS_EXO_GEHT_NICHT, UntertitelWechsel.bildWeg(direkt = true, live = false, exoGescheitert = true))
        assertEquals(BildWeg.HINWEIS_LIVE, UntertitelWechsel.bildWeg(direkt = true, live = true, exoGescheitert = false))
    }
}
