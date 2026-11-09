package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** German4K 3.2.1 (p-12): 1 Text-Brücke mpv → ExoPlayer, 3 Live-Spurwahl ExoPlayer → mpv. */
class SpurP12Test {
    // --- 1: Brücke ---
    @Test fun bruecke_zeigt_zeile_bis_exo_eigene_cues_liefert() {
        val b = ExoTextBruecke()
        b.setzen("Deutsch Text-Untertitel", 599_000)
        assertTrue(b.aktiv)
        assertEquals("Deutsch Text-Untertitel", b.beiCues(nichtLeer = false, positionMs = 50_000))
        assertNull(b.beiCues(nichtLeer = true, positionMs = 52_000))
        assertFalse(b.aktiv)
        // Ein späteres leeres Cue-Update (Zeilenende) holt die Brücke nicht zurück.
        assertNull(b.beiCues(nichtLeer = false, positionMs = 53_000))
    }

    @Test fun bruecke_endet_mit_der_endzeit_der_zeile() {
        val b = ExoTextBruecke()
        b.setzen("Zeile", 10_000)
        assertEquals("Zeile", b.gueltigBei(9_999))
        assertNull(b.gueltigBei(10_000))
        assertFalse(b.aktiv)
    }

    @Test fun bruecke_ohne_text_oder_ende_bleibt_aus() {
        val b = ExoTextBruecke()
        b.setzen("   ", 10_000)
        assertFalse(b.aktiv)
        b.setzen("Zeile", ExoTextBruecke.endeAusMpv(null))
        assertFalse(b.aktiv)
        b.setzen(null, 10_000)
        assertFalse(b.aktiv)
    }

    @Test fun mpv_sub_end_in_ms() {
        assertEquals(599_000L, ExoTextBruecke.endeAusMpv(599.0))
        assertEquals(0L, ExoTextBruecke.endeAusMpv(Double.NaN))
        assertEquals(0L, ExoTextBruecke.endeAusMpv(-1.0))
    }

    // --- 3: Live ExoPlayer (de) → mpv (ger) ---
    private fun t(id: Int, lang: String?, label: String, image: Boolean = false, selected: Boolean = false) =
        TrackOption(label = label, mpvId = id, selected = selected, image = image, lang = lang, typeIndex = id, labelKind = TrackLabelKind.SUBTITLE)

    private val exoSubs = listOf(t(0, "de", "Deutsch (VobSub)", true), t(1, "de", "Deutsch (PGS)", true, selected = true), t(4, "de", "Deutsch (Text)"))
    private val mpvSubs = listOf(
        t(1, "ger", "Deutsch (VobSub)", true), t(2, "ger", "Deutsch (PGS)", true),
        t(3, "eng", "English SDH (VobSub)", true), t(4, "eng", "English SDH (PGS)", true), t(5, "ger", "Deutsch (Text)"),
    )

    @Test fun live_exo_nach_mpv_findet_pgs_nicht_vobsub() {
        val w = exoSubs[1].alsWunsch()
        assertEquals(2, UntertitelWechsel.finde(mpvSubs, w, bildErlaubt = true)?.mpvId)
    }

    @Test fun live_exo_nach_mpv_direktweg_pgs_wird_gleichsprachiger_text() {
        val w = exoSubs[1].alsWunsch()
        assertEquals(5, UntertitelWechsel.finde(mpvSubs, w, bildErlaubt = false)?.mpvId)
    }

    @Test fun live_exo_nach_mpv_textspur() {
        assertEquals(5, UntertitelWechsel.finde(mpvSubs, exoSubs[2].alsWunsch())?.mpvId)
    }
}
