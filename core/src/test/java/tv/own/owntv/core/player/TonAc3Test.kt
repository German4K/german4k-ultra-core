package tv.own.owntv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** German4K 3.2.1 (T7): wann AAC-Mehrkanal zu AC3 umkodiert wird. */
class TonAc3Test {

    private val ac3 = setOf(TonAusgang.Kodierung.AC3, TonAusgang.Kodierung.EAC3)
    private val fireTvAc3 = TonAusgang(TonAusgang.Art.HDMI, maxKanaele = 8, kodierungen = ac3, fireTv = true)
    private val boxPcm8 = TonAusgang(TonAusgang.Art.HDMI, maxKanaele = 8, kodierungen = ac3, fireTv = false)
    private val boxNurBitstream = TonAusgang(TonAusgang.Art.HDMI, maxKanaele = 2, kodierungen = ac3, fireTv = false)
    private val AAC = TonAc3.AAC_MIME

    private fun aktiv(
        weg: TonWeg? = TonWeg.DURCHREICHEN_PCM_STEREO,
        ausgang: TonAusgang? = fireTvAc3,
        mime: String? = AAC,
        kanaele: Int = 6,
        schalter: Boolean = true,
    ) = TonAc3.aktiv(schalter, weg, ausgang, mime, kanaele)

    @Test fun `Fire TV Automatisch mit Dolby - AAC 5_1 wird AC3`() {
        // So sieht der Stick des Kunden aus: Automatisch → Bitstream, PCM stereo (fireTv).
        val weg = TonWahl.weg(SurroundMode.AUTO, tonAuto = true, gesperrt = false, ausgang = fireTvAc3)
        assertEquals(TonWeg.DURCHREICHEN_PCM_STEREO, weg)
        assertTrue(aktiv(weg = weg))
    }

    @Test fun `Fire TV Receiver-Stufe - AAC 5_1 wird AC3`() {
        val weg = TonWahl.weg(SurroundMode.SURROUND, tonAuto = true, gesperrt = false, ausgang = fireTvAc3)
        assertEquals(TonWeg.DURCHREICHEN, weg)
        assertTrue(aktiv(weg = weg))
    }

    @Test fun `AAC 7_1 und 3_0 auch, Stereo und Mono nie`() {
        assertTrue(aktiv(kanaele = 8))
        assertTrue(aktiv(kanaele = 3))
        assertFalse(aktiv(kanaele = 2))
        assertFalse(aktiv(kanaele = 1))
        assertFalse(aktiv(kanaele = -1)) // Format.NO_VALUE
    }

    @Test fun `nur AAC - Dolby DTS Opus bleiben wie sie sind`() {
        assertFalse(aktiv(mime = "audio/ac3"))
        assertFalse(aktiv(mime = "audio/eac3"))
        assertFalse(aktiv(mime = "audio/vnd.dts"))
        assertFalse(aktiv(mime = "audio/opus"))
        assertFalse(aktiv(mime = null))
    }

    @Test fun `Schalter ton_ac3 aus - nie`() {
        assertFalse(aktiv(schalter = false))
    }

    @Test fun `Stereo, Mehrkanal-PCM, 3_2-Surround und Sperre - nie`() {
        assertFalse(aktiv(weg = TonWeg.STEREO))
        assertFalse(aktiv(weg = TonWeg.MEHRKANAL_PCM))
        assertFalse(aktiv(weg = TonWeg.ALT_GERAET))
        assertFalse(aktiv(weg = null))
        // Sitzungssperre (Ton fiel aus) → Stereo → kein AC3.
        val gesperrt = TonWahl.weg(SurroundMode.AUTO, tonAuto = true, gesperrt = true, ausgang = fireTvAc3)
        assertFalse(aktiv(weg = gesperrt))
        // ton_auto aus = Verhalten 3.2 → kein AC3.
        val alt = TonWahl.weg(SurroundMode.SURROUND, tonAuto = false, gesperrt = false, ausgang = fireTvAc3)
        assertFalse(aktiv(weg = alt))
    }

    @Test fun `Ausgang ohne AC3 oder ohne HDMI - nie`() {
        assertFalse(aktiv(ausgang = null))
        assertFalse(aktiv(ausgang = fireTvAc3.copy(kodierungen = setOf(TonAusgang.Kodierung.DTS))))
        assertFalse(aktiv(ausgang = fireTvAc3.copy(kodierungen = emptySet())))
        assertFalse(aktiv(ausgang = fireTvAc3.copy(art = TonAusgang.Art.BLUETOOTH)))
        assertFalse(aktiv(ausgang = fireTvAc3.copy(art = TonAusgang.Art.LAUTSPRECHER)))
        assertTrue(aktiv(ausgang = fireTvAc3.copy(art = TonAusgang.Art.HDMI_ARC)))
        assertTrue(aktiv(ausgang = fireTvAc3.copy(art = TonAusgang.Art.HDMI_EARC)))
    }

    @Test fun `andere Geraete - nur ohne Mehrkanal-PCM`() {
        // Box mit 8ch-PCM: PCM 5.1 ist besser (verlustfrei) → kein AC3.
        val wegPcm = TonWahl.weg(SurroundMode.AUTO, tonAuto = true, gesperrt = false, ausgang = boxPcm8)
        assertEquals(TonWeg.DURCHREICHEN, wegPcm)
        assertFalse(aktiv(weg = wegPcm, ausgang = boxPcm8))
        val wegReceiver = TonWahl.weg(SurroundMode.SURROUND, tonAuto = true, gesperrt = false, ausgang = boxPcm8)
        assertFalse(aktiv(weg = wegReceiver, ausgang = boxPcm8))
        // Box, die nur Bitstream kann (PCM 2ch): AC3.
        val wegBit = TonWahl.weg(SurroundMode.AUTO, tonAuto = true, gesperrt = false, ausgang = boxNurBitstream)
        assertEquals(TonWeg.DURCHREICHEN_PCM_STEREO, wegBit)
        assertTrue(aktiv(weg = wegBit, ausgang = boxNurBitstream))
    }

    @Test fun `Kanaele und Diagnose-Text`() {
        assertEquals(6, TonAc3.ac3Kanaele(8))
        assertEquals(6, TonAc3.ac3Kanaele(6))
        assertEquals(5, TonAc3.ac3Kanaele(5))
        assertEquals(3, TonAc3.ac3Kanaele(3))
        assertEquals("aac 5.1 → ac3 5.1", TonAc3.umwandlung(6, 6))
        assertEquals("aac 7.1 → ac3 5.1", TonAc3.umwandlung(8, TonAc3.ac3Kanaele(8)))
    }
}
