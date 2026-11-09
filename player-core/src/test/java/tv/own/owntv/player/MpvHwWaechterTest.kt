package tv.own.owntv.player

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.player.MpvHwWaechter.Probe
import tv.own.owntv.player.MpvHwWaechter.Urteil

/** German4K 3.2.1 (A1, p-6): Urteil des frühen Hardware-Wächters. */
class MpvHwWaechterTest {
    private fun u(
        seitLaden: Long = 3_000,
        seitFl: Long? = 1_000,
        steckt: Long = 0,
        probe: Probe? = null,
        bezug: Long? = null,
        ton: Boolean = false,
    ) = MpvHwWaechter.urteil(seitLaden, seitFl, steckt, probe, bezug, ton)

    private fun probe(h: Int = 0, t: Long? = 0, hw: String? = "mediacodec", video: Boolean = true, puffer: Long? = 0) =
        Probe(h, t, hw, video, puffer)

    @Test fun `vor file-loaded wartet er, auch wenn der faden steckt`() {
        // Öffnen (Netz) ist Sache der T_OPEN-Wächter, nicht ein Decoder-Hänger.
        assertEquals(Urteil.WEITER, u(seitFl = null, steckt = 5_000))
    }

    @Test fun `nach file-loaded steckt der faden ab 2 s = haengt`() {
        assertEquals(Urteil.WEITER, u(steckt = MpvHwWaechter.HAENGT_MS - 1))
        assertEquals(Urteil.HAENGT, u(steckt = MpvHwWaechter.HAENGT_MS))
    }

    @Test fun `ein bild beendet den waechter`() {
        assertEquals(Urteil.FERTIG, u(probe = probe(h = 360)))
    }

    @Test fun `kein bild bei laufender zeit nach 4 s = kein bild`() {
        val p = probe(t = 5_000)
        assertEquals(Urteil.WEITER, u(seitFl = 3_999, probe = p, bezug = 3_000))
        assertEquals(Urteil.KEIN_BILD, u(seitFl = 4_000, probe = p, bezug = 3_000))
    }

    @Test fun `kein bild bei vollem puffer und stehender zeit = kein bild`() {
        // mpv startet den Ton erst mit dem ersten Bild — die Zeit steht, der Puffer füllt sich.
        val p = probe(t = 0, puffer = 2_500)
        assertEquals(Urteil.KEIN_BILD, u(seitFl = 4_500, probe = p, bezug = 0))
    }

    @Test fun `netz stockt (zeit steht, puffer leer) bleibt bei den anderen waechtern`() {
        assertEquals(Urteil.WEITER, u(seitFl = 8_000, probe = probe(t = 0, puffer = 300), bezug = 0))
    }

    @Test fun `decoder nicht angesprungen ist sache des decode-checks`() {
        assertEquals(Urteil.WEITER, u(seitFl = 6_000, probe = probe(t = 5_000, hw = "no"), bezug = 0))
        assertEquals(Urteil.WEITER, u(seitFl = 6_000, probe = probe(t = 5_000, hw = ""), bezug = 0))
    }

    @Test fun `reine tonspur und audio-modus beenden ihn`() {
        assertEquals(Urteil.FERTIG, u(seitFl = 6_000, probe = probe(t = 5_000, video = false), bezug = 0))
        assertEquals(Urteil.FERTIG, u(seitFl = 6_000, probe = probe(t = 5_000), bezug = 0, ton = true))
    }

    @Test fun `nach max gibt er auf, ausser der kern haengt`() {
        assertEquals(Urteil.FERTIG, u(seitLaden = MpvHwWaechter.MAX_MS, seitFl = 1_000))
        assertEquals(Urteil.HAENGT, u(seitLaden = MpvHwWaechter.MAX_MS, steckt = 3_000))
    }

    @Test fun `vod setzt an der stelle fort, live und archiv von vorn`() {
        assertEquals(0L, MpvHwWaechter.fortsetzenBei(live = true, archiv = false, positionMs = 9_000, geplantMs = 0))
        assertEquals(0L, MpvHwWaechter.fortsetzenBei(live = false, archiv = true, positionMs = 9_000, geplantMs = 0))
        assertEquals(9_000L, MpvHwWaechter.fortsetzenBei(live = false, archiv = false, positionMs = 9_000, geplantMs = 1_000))
        assertEquals(1_000L, MpvHwWaechter.fortsetzenBei(live = false, archiv = false, positionMs = 0, geplantMs = 1_000))
    }

    @Test fun `steckende probe ist am faden messbar`() {
        // Die Probe ist ein normaler Auftrag auf dem mpv-Faden: hängt sie, wächst laeuftSeitMs — darauf urteilt der Wächter.
        val f = MpvFaden()
        val los = CountDownLatch(1)
        val drin = CountDownLatch(1)
        f.execute { drin.countDown(); los.await(5, TimeUnit.SECONDS) }
        assertTrue(drin.await(2, TimeUnit.SECONDS))
        Thread.sleep(50)
        assertTrue(f.laeuftSeitMs() >= 40)
        los.countDown()
        f.shutdown()
    }

    // German4K 3.2.1 (p-9): Heilung nur, wo Software trägt (GL + Quelle ≤ 1080p), sonst Exo/warten/neu ohne Direktweg.
    private fun h(haengt: Boolean, live: Boolean, gl: Boolean, hoehe: Int) = MpvHwWaechter.heilung(haengt, live, gl, hoehe)

    @Test fun `heilung - alle kombinationen`() {

        for (haengt in listOf(false, true)) for (live in listOf(false, true)) for (gl in listOf(false, true)) {
            for (hoehe in listOf(0, 576, 1080, 1081, 2160)) {
                val soll = when {
                    gl && hoehe in 1..1080 -> MpvHwWaechter.Heilung.SOFTWARE
                    !live -> MpvHwWaechter.Heilung.EXO_VOD
                    haengt -> MpvHwWaechter.Heilung.NEU_OHNE_DIREKTWEG
                    else -> MpvHwWaechter.Heilung.WEITER
                }
                assertEquals("haengt=$haengt live=$live gl=$gl h=$hoehe", soll, h(haengt, live, gl, hoehe))
            }
        }
    }

    @Test fun `4k-live mit langsamem erstem bild wartet statt software`() {
        assertEquals(MpvHwWaechter.Heilung.WEITER, h(haengt = false, live = true, gl = true, hoehe = 2160))
    }

    @Test fun `ohne gl nie software`() {
        assertEquals(MpvHwWaechter.Heilung.WEITER, h(haengt = false, live = true, gl = false, hoehe = 720))
        assertEquals(MpvHwWaechter.Heilung.NEU_OHNE_DIREKTWEG, h(haengt = true, live = true, gl = false, hoehe = 720))
        assertEquals(MpvHwWaechter.Heilung.EXO_VOD, h(haengt = false, live = false, gl = false, hoehe = 720))
        assertEquals(MpvHwWaechter.Heilung.EXO_VOD, h(haengt = true, live = false, gl = false, hoehe = 720))
    }

    @Test fun `film ueber 1080p geht gleich zu exo, haengend oder nicht`() {
        assertEquals(MpvHwWaechter.Heilung.EXO_VOD, h(haengt = false, live = false, gl = true, hoehe = 2160))
        assertEquals(MpvHwWaechter.Heilung.EXO_VOD, h(haengt = true, live = false, gl = true, hoehe = 2160))
    }

    @Test fun `1080p mit gl heilt wie bisher auf software`() {
        assertEquals(MpvHwWaechter.Heilung.SOFTWARE, h(haengt = false, live = true, gl = true, hoehe = 1080))
        assertEquals(MpvHwWaechter.Heilung.SOFTWARE, h(haengt = true, live = true, gl = true, hoehe = 1080))
        assertEquals(MpvHwWaechter.Heilung.SOFTWARE, h(haengt = true, live = false, gl = true, hoehe = 720))
    }

    @Test fun `unbekannte quellhoehe gilt nicht als 1080p`() {
        assertEquals(MpvHwWaechter.Heilung.WEITER, h(haengt = false, live = true, gl = true, hoehe = 0))
        assertEquals(MpvHwWaechter.Heilung.NEU_OHNE_DIREKTWEG, h(haengt = true, live = true, gl = true, hoehe = 0))
    }
}
