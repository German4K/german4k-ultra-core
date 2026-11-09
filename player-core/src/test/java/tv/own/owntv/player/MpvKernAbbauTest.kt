package tv.own.owntv.player

import dev.jdtech.mpv.MPVLib
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * German4K 3.2.1 (A1, p-5): hängender mpv-Kern (Emulator 21.10.: FFmpeg-MediaCodec-Decoder dreht sich nach file-loaded
 * endlos, jeder API-Aufruf wartet für immer). Erkennung, Verwerfen ohne Warten, höchstens ein Abbau-Faden je Kern.
 */
class MpvKernAbbauTest {
    @Before fun setUp() = MpvRestKerne.zuruecksetzenFuerTest()

    @Test fun `freier faden steckt nicht`() {
        val f = MpvFaden()
        assertEquals(0L, f.laeuftSeitMs())
        val fertig = CountDownLatch(1)
        f.execute { fertig.countDown() }
        assertTrue(fertig.await(1, TimeUnit.SECONDS))
        Thread.sleep(20)
        assertEquals(0L, f.laeuftSeitMs())
        assertFalse(MpvDetachFrist.haengtSchon(f.laeuftSeitMs()))
        f.shutdownNow()
    }

    @Test fun `haengender lesezugriff wird nach einer sekunde als haenger erkannt`() {
        val f = MpvFaden()
        val haengt = CountDownLatch(1)
        val laeuft = CountDownLatch(1)
        f.execute { laeuft.countDown(); haengt.await() } // getPropertyString auf dem hängenden Kern
        assertTrue(laeuft.await(1, TimeUnit.SECONDS))
        assertFalse(MpvDetachFrist.haengtSchon(f.laeuftSeitMs()))
        val spaeter = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(MpvDetachFrist.HAENGT_AB_MS + 50)
        assertTrue(MpvDetachFrist.haengtSchon(f.laeuftSeitMs(spaeter)))
        haengt.countDown()
        f.shutdownNow()
    }

    @Test fun `verwerfen streicht die hinter dem haenger wartenden auftraege`() {
        val f = MpvFaden()
        val haengt = CountDownLatch(1)
        f.execute { haengt.await() }
        val barriere = CountDownLatch(1)
        f.execute { barriere.countDown() } // stop/Barriere/destroy lagen früher für immer hier
        val gestrichen = f.shutdownNow()
        assertEquals(1, gestrichen.size)
        haengt.countDown()
        assertTrue(f.awaitTermination(1, TimeUnit.SECONDS))
        assertEquals(1L, barriere.count) // lief nie — und hält nichts mehr fest
    }

    @Test fun `abbau blockiert nie und zaehlt den rest-kern bis destroy zurueck ist`() {
        val kernFrei = CountDownLatch(1)
        val reihenfolge = mutableListOf<String>()
        val fertig = CountDownLatch(1)
        val start = System.nanoTime()
        val faden = MpvRestKerne.abbauen(
            stoppen = { kernFrei.await(); synchronized(reihenfolge) { reihenfolge += "stop" } },
            zerstoeren = { synchronized(reihenfolge) { reihenfolge += "destroy" } },
            gestorben = { synchronized(reihenfolge) { reihenfolge += "gestorben" }; fertig.countDown() },
        )
        assertTrue("Aufrufer wartete", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start) < 200)
        assertEquals(1, MpvRestKerne.anzahl())
        assertEquals("owntv-mpv-abbau", faden.name)
        Thread.sleep(50)
        assertEquals(1, MpvRestKerne.anzahl()) // Kern hängt noch → bleibt die eine Rest-Instanz
        kernFrei.countDown() // Kern antwortet wieder
        assertTrue(fertig.await(1, TimeUnit.SECONDS))
        faden.join(1_000)
        assertEquals(listOf("stop", "destroy", "gestorben"), reihenfolge)
        assertEquals(0, MpvRestKerne.anzahl())
    }

    @Test fun `abbau zaehlt auch nach nativer ausnahme zurueck`() {
        val faden = MpvRestKerne.abbauen(stoppen = { error("stop") }, zerstoeren = { error("destroy") }, gestorben = {})
        faden.join(1_000)
        assertEquals(0, MpvRestKerne.anzahl())
    }

    @Test fun `geschlossenes tor laesst spaete ereignisse des alten kerns nicht durch`() {
        val gesehen = mutableListOf<String>()
        val ziel = object : MPVLib.EventObserver {
            override fun eventProperty(property: String) { gesehen += property }
            override fun eventProperty(property: String, value: Long) { gesehen += "$property=$value" }
            override fun eventProperty(property: String, value: Double) { gesehen += "$property=$value" }
            override fun eventProperty(property: String, value: Boolean) { gesehen += "$property=$value" }
            override fun eventProperty(property: String, value: String) { gesehen += "$property=$value" }
            override fun event(eventId: Int) { gesehen += "event$eventId" }
        }
        val tor = MpvKernTor(ziel)
        tor.eventProperty("time-pos", 5L)
        tor.event(MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED)
        tor.schliessen()
        tor.eventProperty("pause", true)
        tor.event(MPVLib.MpvEvent.MPV_EVENT_END_FILE)
        assertEquals(listOf("time-pos=5", "event${MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED}"), gesehen)
    }

    @Test fun `geschlossenes log-tor reicht nur noch ffmpeg-zeilen durch`() {
        val zeilen = mutableListOf<String>()
        val tor = MpvKernLogTor(object : MPVLib.LogObserver {
            override fun logMessage(prefix: String, level: Int, text: String) { zeilen += "$prefix:$text" }
        })
        tor.logMessage("cplayer", 20, "a")
        tor.schliessen()
        tor.logMessage("cplayer", 20, "b")
        tor.logMessage("ffmpeg", 20, "https: HTTP error 458")
        assertEquals(listOf("cplayer:a", "ffmpeg:https: HTTP error 458"), zeilen)
    }
}
