package tv.own.owntv.core.player

import android.media.AudioDeviceInfo
import android.media.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** German4K 3.2.1 (T1/T2/T5): Stufen-Abbildung, Ausgangserkennung mit Fakes, Downmix-Matrizen. */
class TonTest {

    // ---- T2: Stufen ------------------------------------------------------------------------------

    @Test fun `alte gespeicherte Werte bleiben lesbar - Surround wird Receiver`() {
        assertEquals(SurroundMode.SURROUND, SurroundMode.of("SURROUND", null))
        assertEquals(SurroundMode.AUTO, SurroundMode.of("AUTO", false))
        assertEquals(SurroundMode.STEREO, SurroundMode.of(null, false))
        assertEquals(SurroundMode.SURROUND, SurroundMode.of(null, true))
        assertEquals(SurroundMode.AUTO, SurroundMode.of(null, null))
        assertEquals(SurroundMode.MULTICHANNEL, SurroundMode.of("MULTICHANNEL", true))
        // Unbekannter Name (Downgrade-Rückweg) fällt auf den alten Schalter zurück.
        assertEquals(SurroundMode.SURROUND, SurroundMode.of("IRGENDWAS", true))
    }

    @Test fun `durchschalten mit ton_auto vier Stufen`() {
        var m = SurroundMode.AUTO
        val gesehen = mutableListOf(m)
        repeat(4) { m = m.naechste(true); gesehen += m }
        assertEquals(
            listOf(SurroundMode.AUTO, SurroundMode.STEREO, SurroundMode.MULTICHANNEL, SurroundMode.SURROUND, SurroundMode.AUTO),
            gesehen,
        )
    }

    @Test fun `durchschalten ohne ton_auto wie 3_2`() {
        assertEquals(SurroundMode.STEREO, SurroundMode.AUTO.naechste(false))
        assertEquals(SurroundMode.SURROUND, SurroundMode.STEREO.naechste(false))
        assertEquals(SurroundMode.AUTO, SurroundMode.SURROUND.naechste(false))
        assertEquals(SurroundMode.AUTO, SurroundMode.MULTICHANNEL.naechste(false))
        assertEquals(SurroundMode.SURROUND, SurroundMode.MULTICHANNEL.wirksam(false))
        assertEquals(SurroundMode.MULTICHANNEL, SurroundMode.MULTICHANNEL.wirksam(true))
    }

    @Test fun `ton_auto aus ist exakt 3_2`() {
        val receiver = hdmi(8, AudioFormat.ENCODING_AC3, AudioFormat.ENCODING_E_AC3)
        assertEquals(TonWeg.STEREO, TonWahl.weg(SurroundMode.AUTO, false, false, receiver))
        assertEquals(TonWeg.STEREO, TonWahl.weg(SurroundMode.STEREO, false, false, receiver))
        assertEquals(TonWeg.ALT_GERAET, TonWahl.weg(SurroundMode.SURROUND, false, false, receiver))
        assertEquals(TonWeg.ALT_GERAET, TonWahl.weg(SurroundMode.MULTICHANNEL, false, false, null))
    }

    @Test fun `Sperre schlaegt alles`() {
        val receiver = hdmi(8, AudioFormat.ENCODING_AC3)
        SurroundMode.entries.forEach { m ->
            assertEquals(TonWeg.STEREO, TonWahl.weg(m, true, true, receiver))
            assertEquals(TonWeg.STEREO, TonWahl.weg(m, false, true, receiver))
        }
    }

    @Test fun `Automatisch nach Ausgang`() {
        // Nichts erkannt / Lautsprecher / Bluetooth → Stereo
        assertEquals(TonWeg.STEREO, TonWahl.weg(SurroundMode.AUTO, true, false, null))
        assertEquals(TonWeg.STEREO, TonWahl.weg(SurroundMode.AUTO, true, false, aus(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)))
        // Fernseher ohne Bitstream, nur Stereo
        assertEquals(TonWeg.STEREO, TonWahl.weg(SurroundMode.AUTO, true, false, hdmi(2)))
        // Receiver mit 7.1 PCM und Dolby
        assertEquals(TonWeg.DURCHREICHEN, TonWahl.weg(SurroundMode.AUTO, true, false, hdmi(8, AudioFormat.ENCODING_AC3)))
        // Fernseher meldet Dolby, aber nur 2 Kanäle PCM → Dolby durch, AAC 5.1 heruntermischen
        assertEquals(TonWeg.DURCHREICHEN_PCM_STEREO, TonWahl.weg(SurroundMode.AUTO, true, false, hdmi(2, AudioFormat.ENCODING_AC3)))
        // Mehrkanal-PCM ohne Bitstream
        assertEquals(TonWeg.MEHRKANAL_PCM, TonWahl.weg(SurroundMode.AUTO, true, false, hdmi(6)))
        // Fire TV: nie Mehrkanal-PCM automatisch, Dolby aber durch
        assertEquals(TonWeg.DURCHREICHEN_PCM_STEREO, TonWahl.weg(SurroundMode.AUTO, true, false, hdmi(8, AudioFormat.ENCODING_E_AC3, fireTv = true)))
        assertEquals(TonWeg.STEREO, TonWahl.weg(SurroundMode.AUTO, true, false, hdmi(8, fireTv = true)))
    }

    @Test fun `ausdrueckliche Stufen mit ton_auto`() {
        assertEquals(TonWeg.STEREO, TonWahl.weg(SurroundMode.STEREO, true, false, hdmi(8, AudioFormat.ENCODING_AC3)))
        assertEquals(TonWeg.MEHRKANAL_PCM, TonWahl.weg(SurroundMode.MULTICHANNEL, true, false, null))
        assertEquals(TonWeg.DURCHREICHEN, TonWahl.weg(SurroundMode.SURROUND, true, false, null))
    }

    // ---- T1: Ausgangserkennung (Fakes) ----------------------------------------------------------

    @Test fun `HDMI mit Receiver`() {
        val a = TonAusgang.aus(
            listOf(TonAusgang.Geraet(AudioDeviceInfo.TYPE_HDMI, listOf(2, 6, 8))),
            capsMaxKanaele = 8,
            capsEncodings = listOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_AC3, AudioFormat.ENCODING_E_AC3, AudioFormat.ENCODING_E_AC3_JOC, AudioFormat.ENCODING_DTS),
        )
        assertEquals(TonAusgang.Art.HDMI, a.art)
        assertEquals(8, a.maxKanaele)
        assertTrue(a.bitstream)
        assertEquals("ac3,eac3,dts", a.mpvSpdif())
        assertEquals("hdmi 8ch ac3,eac3,eac3_joc,dts", a.technisch())
    }

    @Test fun `Bluetooth schlaegt HDMI und reicht nie durch`() {
        val a = TonAusgang.aus(
            listOf(TonAusgang.Geraet(AudioDeviceInfo.TYPE_HDMI), TonAusgang.Geraet(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)),
            capsMaxKanaele = 8, capsEncodings = listOf(AudioFormat.ENCODING_AC3),
        )
        assertEquals(TonAusgang.Art.BLUETOOTH, a.art)
        assertFalse(a.bitstream)
        assertEquals(2, a.maxKanaele)
        assertEquals("", a.mpvSpdif())
    }

    @Test fun `ARC und eARC werden erkannt, eARC vor ARC`() {
        assertEquals(TonAusgang.Art.HDMI_ARC, TonAusgang.artVon(listOf(TonAusgang.Geraet(AudioDeviceInfo.TYPE_HDMI_ARC), TonAusgang.Geraet(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER))))
        assertEquals(TonAusgang.Art.HDMI_EARC, TonAusgang.artVon(listOf(TonAusgang.Geraet(29), TonAusgang.Geraet(AudioDeviceInfo.TYPE_HDMI_ARC))))
        assertEquals(TonAusgang.Art.UNBEKANNT, TonAusgang.artVon(emptyList()))
    }

    @Test fun `Kanalzahl faellt auf Geraeteliste zurueck`() {
        val a = TonAusgang.aus(listOf(TonAusgang.Geraet(AudioDeviceInfo.TYPE_HDMI, listOf(2, 6))), capsMaxKanaele = 0, capsEncodings = emptyList())
        assertEquals(6, a.maxKanaele)
        assertFalse(a.bitstream)
        val b = TonAusgang.aus(listOf(TonAusgang.Geraet(AudioDeviceInfo.TYPE_HDMI)), capsMaxKanaele = 0, capsEncodings = emptyList())
        assertEquals(2, b.maxKanaele)
    }

    @Test fun `TrueHD allein ist fuer mpv kein spdif`() {
        val a = hdmi(8, AudioFormat.ENCODING_DOLBY_TRUEHD)
        assertFalse(a.bitstream)
        assertEquals("", a.mpvSpdif())
    }

    // ---- T5: Downmix-Matrizen je Layout ----------------------------------------------------------

    @Test fun `Matrizen je Layout - Mitte symmetrisch, LFE weg, konstante Leistung`() {
        for (n in 3..8) {
            val m = TonDownmix.dialogMitte(n)!!
            val rollen = TonDownmix.layout(n)!!
            assertEquals(n * 2, m.size)
            var l2 = 0.0; var r2 = 0.0
            for (i in 0 until n) {
                l2 += m[i * 2] * m[i * 2]; r2 += m[i * 2 + 1] * m[i * 2 + 1]
            }
            assertEquals("n=$n links", 1.0, l2, 1e-4)
            assertEquals("n=$n rechts", 1.0, r2, 1e-4)
            rollen.forEachIndexed { i, rolle ->
                val l = m[i * 2]; val r = m[i * 2 + 1]
                when (rolle) {
                    0 -> { assertTrue(l > 0f); assertEquals(0f, r) } // FL nur links
                    1 -> { assertTrue(r > 0f); assertEquals(0f, l) } // FR nur rechts
                    2 -> { // FC: beide Seiten gleich, −3 dB gegenüber FL
                        assertEquals("n=$n Mitte symmetrisch", l, r, 1e-6f)
                        val fl = m[0]
                        assertTrue("n=$n Mitte 0,707×FL", abs(l / fl - TonDownmix.MITTE) < 1e-4f)
                    }
                    3 -> { assertEquals(0f, l); assertEquals(0f, r) } // LFE
                }
            }
        }
    }

    @Test fun `51 genau wie erwartet`() {
        val m = TonDownmix.dialogMitte(6)!!
        val fl = 0.70710677f; val c = 0.5f
        val erwartet = floatArrayOf(fl, 0f, 0f, fl, c, c, 0f, 0f, c, 0f, 0f, c)
        erwartet.forEachIndexed { i, v -> assertEquals("i=$i", v, m[i], 1e-4f) }
    }

    @Test fun `keine Matrix fuer Mono, Stereo und mehr als 8`() {
        assertNull(TonDownmix.dialogMitte(1))
        assertNull(TonDownmix.dialogMitte(2))
        assertNull(TonDownmix.dialogMitte(10))
    }

    private fun hdmi(kanaele: Int, vararg enc: Int, fireTv: Boolean = false) =
        TonAusgang.aus(listOf(TonAusgang.Geraet(AudioDeviceInfo.TYPE_HDMI)), kanaele, enc.toList(), fireTv)

    private fun aus(typ: Int) = TonAusgang.aus(listOf(TonAusgang.Geraet(typ)), 2, emptyList())
}
