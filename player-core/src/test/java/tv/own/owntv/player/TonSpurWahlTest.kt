package tv.own.owntv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** German4K 3.2.1 (p-19): Live-Tonspurwahl (Das Erste HD = MP2 auf Fire TV) und Diagnose Durchleitung/PCM. */
class TonSpurWahlTest {
    private fun spur(id: Int, mime: String?, ok: Boolean) = TonSpurWahl.Spur(id, mime, ok)

    @Test fun mp2_wird_vom_ffmpeg_decoder_gedeckt() {
        // Media3 fragt für MPEG-Audio Layer I/II/III nach FFmpeg „mp3“ — seit p-19 in libffmpegJNI.so.
        assertTrue(TonSpurWahl.ffmpegDeckt("audio/mpeg-L2"))
        assertTrue(TonSpurWahl.ffmpegDeckt("audio/mpeg-L1"))
        assertTrue(TonSpurWahl.ffmpegDeckt("audio/mpeg"))
        assertTrue(TonSpurWahl.ffmpegDeckt("audio/ac3"))
        assertFalse(TonSpurWahl.ffmpegDeckt("audio/opus"))
        assertFalse(TonSpurWahl.ffmpegDeckt(null))
    }

    @Test fun mpv_nur_wenn_keine_spur_spielbar() {
        assertTrue(TonSpurWahl.mpvNoetig(listOf(spur(0, "audio/mpeg-L2", false))))
        // MP2 jetzt spielbar (FFmpeg) → bleibt auf Exo.
        assertFalse(TonSpurWahl.mpvNoetig(listOf(spur(0, "audio/mpeg-L2", true))))
        // Eine spielbare reicht (ZDF: drei AC3-Spuren, Gerät dekodiert AC3).
        assertFalse(TonSpurWahl.mpvNoetig(listOf(spur(0, "audio/vnd.dts", false), spur(1, "audio/ac3", true))))
        // Ohne Ton (Video ohne Spur) nie wegen Ton zu mpv.
        assertFalse(TonSpurWahl.mpvNoetig(emptyList()))
    }

    @Test fun titelwahl_nur_unter_spielbaren_spuren() {
        val optionen = listOf("de-mp2" to 0, "en-aac" to 1, "de-ac3" to 2)
        val spuren = listOf(spur(0, "audio/mpeg-L2", false), spur(1, "audio/mp4a-latm", true), spur(2, "audio/ac3", true))
        assertEquals(listOf("en-aac" to 1, "de-ac3" to 2), TonSpurWahl.nurSpielbare(optionen, { it.second }, spuren))
        // Nichts als spielbar gemeldet → Liste unverändert (kein leerer Kandidatensatz).
        val keine = spuren.map { it.copy(spielbar = false) }
        assertEquals(optionen, TonSpurWahl.nurSpielbare(optionen, { it.second }, keine))
    }

    @Test fun grund_nennt_mime_typen() {
        assertEquals("no decodable audio track (audio/mpeg-L2)", TonSpurWahl.grund(listOf(spur(0, "audio/mpeg-L2", false))))
        assertEquals("no decodable audio track", TonSpurWahl.grund(listOf(spur(0, null, false))))
    }

    @Test fun durchleitung_nur_bei_bitstream_senke() {
        // Das Erste FHD: AAC, Decoder weiterverwendet (reuse=1), Senke PCM → dekodiert, nicht „passthrough“.
        assertTrue(TonPfad.decoderWeiter(1))
        assertTrue(TonPfad.decoderWeiter(3))
        assertFalse(TonPfad.decoderWeiter(0))
        assertFalse(TonPfad.decoderWeiter(null))
        assertFalse(TonPfad.durchleitung(decoderAktiv = false, senkeBekannt = true, senkePcm = true))
        assertTrue(TonPfad.durchleitung(decoderAktiv = false, senkeBekannt = true, senkePcm = false))
        // Senke unbekannt → 3.2-Regel: kein Decoder = Durchleitung.
        assertTrue(TonPfad.durchleitung(decoderAktiv = false, senkeBekannt = false, senkePcm = false))
        assertFalse(TonPfad.durchleitung(decoderAktiv = true, senkeBekannt = false, senkePcm = false))
    }
}
