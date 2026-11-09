package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** German4K 3.2.1 (q-3): A Live-Spurübergabe, C Spurmerker, D Bild-Kennung, E Bitrate-Fenster. */
class SpurQ3Test {
    private fun t(id: Int, lang: String?, label: String, image: Boolean = false, selected: Boolean = false) =
        TrackOption(label = label, mpvId = id, selected = selected, image = image, lang = lang, typeIndex = id, labelKind = TrackLabelKind.SUBTITLE)

    // --- A: mpv (ger, PGS) → ExoPlayer live (de) ---
    private val mpvSubs = listOf(t(1, "ger", "Deutsch (VobSub)", true), t(2, "ger", "Deutsch (PGS)", true, selected = true), t(5, "ger", "Deutsch (Text)"))
    private val exoSubs = listOf(t(0, "de", "Deutsch (VobSub)", true), t(1, "de", "Deutsch (PGS)", true), t(2, "de", "Deutsch (Text)"))

    @Test fun live_mpv_nach_exo_findet_bild_untertitel() {
        val u = LiveSpurUebergabe()
        u.setzen(mpvSubs[1].alsWunsch(), null)
        val (sub, ton) = u.anwenden(exoSubs, emptyList())
        assertEquals(1, sub)
        assertNull(ton)
        assertFalse(u.offen)
    }

    @Test fun live_mpv_nach_exo_text_untertitel() {
        val u = LiveSpurUebergabe()
        u.setzen(mpvSubs[2].alsWunsch(), null)
        assertEquals(2, u.anwenden(exoSubs, emptyList()).first)
    }

    @Test fun live_untertitel_wartet_bis_exo_seine_textspuren_meldet() {
        val u = LiveSpurUebergabe()
        val ton = listOf(t(0, "de", "Deutsch", selected = true), t(1, "en", "English"))
        u.setzen(mpvSubs[2].alsWunsch(), ton[1].copy(lang = "eng").alsWunsch())
        val erst = u.anwenden(emptyList(), ton) // Ton zuerst gemeldet
        assertNull(erst.first)
        assertEquals(1, erst.second)
        assertTrue(u.offen)
        assertEquals(2, u.anwenden(exoSubs, ton).first)
        assertFalse(u.offen)
    }

    @Test fun live_schon_gewaehlte_spur_wird_nicht_neu_gesetzt() {
        val u = LiveSpurUebergabe()
        u.setzen(mpvSubs[2].alsWunsch(), null)
        assertNull(u.anwenden(exoSubs.map { it.copy(selected = it.mpvId == 2) }, emptyList()).first)
    }

    // --- C: Merker-Kodierung ---
    @Test fun merker_kodierung_ueberlebt_rundreise() {
        val g = SpurMerker.Gemerkt(
            audio = SpurWunsch("eng", false, 1, "English"),
            sub = SpurWunsch("ger", true, 1, "Deutsch (PGS)"),
            subAus = false, zeitMs = 42L,
        )
        assertEquals(g, SpurMerker.decode(SpurMerker.encode(g)))
        val aus = SpurMerker.Gemerkt(audio = SpurWunsch(null, false, -1, null), sub = null, subAus = true, zeitMs = 7L)
        assertEquals(aus, SpurMerker.decode(SpurMerker.encode(aus)))
        assertNull(SpurMerker.decode("kaputt"))
    }

    @Test fun gemerkte_englische_tonspur_trifft_exo_und_mpv() {
        val w = SpurMerker.decodeWunsch(SpurMerker.encodeWunsch(SpurWunsch("eng", false, 1, "English")))!!
        val mpvTon = listOf(t(1, "ger", "Deutsch", selected = true), t(2, "eng", "English"))
        val exoTon = listOf(t(0, "de", "Deutsch", selected = true), t(1, "en", "English"))
        assertEquals(2, UntertitelWechsel.finde(mpvTon, w)?.mpvId)
        assertEquals(1, UntertitelWechsel.finde(exoTon, w)?.mpvId)
    }

    // --- D ---
    @Test fun bild_untertitel_auch_hinter_media3_cues() {
        assertTrue(BildUntertitel.istBild(androidx.media3.common.MimeTypes.APPLICATION_MEDIA3_CUES, androidx.media3.common.MimeTypes.APPLICATION_PGS))
        assertTrue(BildUntertitel.istBild(androidx.media3.common.MimeTypes.APPLICATION_DVBSUBS, null))
        assertFalse(BildUntertitel.istBild(androidx.media3.common.MimeTypes.APPLICATION_MEDIA3_CUES, androidx.media3.common.MimeTypes.APPLICATION_SUBRIP))
    }

    // --- E ---
    @Test fun bitrate_unbekannt_bis_erstes_byte() {
        assertNull(BitrateFenster().bps(5_000))
    }

    @Test fun bitrate_stossweises_laden_ergibt_mittel_statt_null() {
        val f = BitrateFenster()
        // 1 MB je 4 s in einem Stoß, dazwischen nichts (Puffer voll)
        for (s in 0 until 20 step 4) f.add(s * 1000L, 1_000_000)
        val bps = f.bps(19_500)!!
        assertTrue("bps=$bps", bps in 1_500_000L..3_000_000L)
    }

    @Test fun bitrate_erste_sekunde_keine_fantasiezahl() {
        val f = BitrateFenster()
        f.add(10_000, 125_000) // 1 Mbit im ersten Augenblick
        assertEquals(1_000_000L, f.bps(10_100))
    }
}
