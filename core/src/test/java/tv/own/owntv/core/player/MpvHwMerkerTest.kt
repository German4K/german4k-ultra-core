package tv.own.owntv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** German4K 3.2.1 (A1, p-6): Gerätegedächtnis „Hardware-Decoder von mpv hängt“. */
class MpvHwMerkerTest {
    private val jetzt = 1_000_000_000_000L

    @Test fun `schluessel aus codec und hoehe`() {
        assertEquals("avc|sd", MpvHwMerker.schluessel("h264", 360))
        assertEquals("avc|fhd", MpvHwMerker.schluessel("H.264 / AVC / MPEG-4 AVC / MPEG-4 part 10", 1080))
        assertEquals("hevc|uhd", MpvHwMerker.schluessel("hevc", 2160))
        assertEquals("hevc|hd", MpvHwMerker.schluessel("h265", 720))
        assertNull(MpvHwMerker.schluessel(null, 1080))
        assertNull(MpvHwMerker.schluessel("h264", 0))
    }

    @Test fun `unbekannte klasse oder ein haenger startet in hardware`() {
        val h = linkedMapOf("avc|sd" to MpvHwHaenger(1, jetzt))
        assertEquals(MpvHwStart.HARDWARE, MpvHwMerker.start(h, null, exoErlaubt = true, jetztMs = jetzt))
        assertEquals(MpvHwStart.HARDWARE, MpvHwMerker.start(h, "avc|sd", exoErlaubt = true, jetztMs = jetzt))
        assertEquals(MpvHwStart.HARDWARE, MpvHwMerker.start(h, "avc|fhd", exoErlaubt = true, jetztMs = jetzt))
    }

    @Test fun `zwei haenger bis 1080p starten in software`() {
        val h = LinkedHashMap<String, MpvHwHaenger>()
        MpvHwMerker.zaehlen(h, "avc|fhd", jetzt)
        MpvHwMerker.zaehlen(h, "avc|fhd", jetzt + 1)
        assertEquals(2, h["avc|fhd"]!!.anzahl)
        assertEquals(MpvHwStart.SOFTWARE, MpvHwMerker.start(h, "avc|fhd", exoErlaubt = false, jetztMs = jetzt + 2))
        // andere Klasse unberührt
        assertEquals(MpvHwStart.HARDWARE, MpvHwMerker.start(h, "hevc|fhd", exoErlaubt = false, jetztMs = jetzt + 2))
    }

    @Test fun `uhd geht auf exo, wenn erlaubt — sonst bleibt hardware mit waechter`() {
        val h = linkedMapOf("hevc|uhd" to MpvHwHaenger(2, jetzt))
        assertEquals(MpvHwStart.EXO, MpvHwMerker.start(h, "hevc|uhd", exoErlaubt = true, jetztMs = jetzt))
        assertEquals(MpvHwStart.HARDWARE, MpvHwMerker.start(h, "hevc|uhd", exoErlaubt = false, jetztMs = jetzt))
    }

    @Test fun `nach 30 tagen wieder hardware, zaehlung beginnt neu`() {
        val h = LinkedHashMap<String, MpvHwHaenger>()
        h["avc|sd"] = MpvHwHaenger(5, jetzt)
        val spaeter = jetzt + MpvHwMerker.VERFALL_MS + 1
        assertEquals(MpvHwStart.HARDWARE, MpvHwMerker.start(h, "avc|sd", exoErlaubt = false, jetztMs = spaeter))
        MpvHwMerker.zaehlen(h, "avc|sd", spaeter)
        assertEquals(1, h["avc|sd"]!!.anzahl)
    }

    @Test fun `tabellen ueberleben schreiben und lesen, kaputte zeilen fallen weg`() {
        val h = linkedMapOf("avc|sd" to MpvHwHaenger(2, jetzt), "hevc|uhd" to MpvHwHaenger(1, jetzt - 5))
        assertEquals(h, MpvHwMerker.haengerLesen(MpvHwMerker.haengerSchreiben(h)))
        assertEquals(1, MpvHwMerker.haengerLesen("x\tzwei\t1\navc|sd\t2\t3\n\n").size)
        val t = linkedMapOf("u:1a2b" to "avc|sd", "pin:42" to "hevc|uhd")
        assertEquals(t, MpvHwMerker.titelLesen(MpvHwMerker.titelSchreiben(t)))
        assertEquals(0, MpvHwMerker.titelLesen("nur-ein-feld\n\t\n").size)
    }

    @Test fun `lru haelt die grenze`() {
        val m = LinkedHashMap<String, String>()
        for (i in 0 until 5) MpvHwMerker.setzen(m, "t$i", "avc|sd", max = 3)
        assertEquals(listOf("t2", "t3", "t4"), m.keys.toList())
        MpvHwMerker.setzen(m, "t2", "avc|hd", max = 3) // zuletzt benutzt ans Ende
        assertEquals(listOf("t3", "t4", "t2"), m.keys.toList())
    }
}
