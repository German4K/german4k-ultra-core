package tv.own.owntv.player

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import dev.jdtech.mpv.MPVLib
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * German4K 3.2.1 (P2): beweist auf dem Gerät, woran der HTTP-403-Grund im Fehlerbild verloren ging.
 *
 * libmpv bindet FFmpegs av_log prozessweit an EINEN Kern. Entsteht ein Kern, während ein älterer noch
 * lebt (A1: verbrauchter Kern), und stirbt der ältere danach, bekommt KEIN Kern mehr FFmpeg-Zeilen wie
 * „https: HTTP error 403 Forbidden“. Erst ein Kern, der nach dem Tod aller anderen entsteht, bindet neu
 * — genau das macht OwnTVPlayer.kernNeuFuerFfmpegLog.
 *
 * Lokaler Server auf 127.0.0.1 antwortet immer 403. Kein Netz, kein Konto.
 */
@RunWith(AndroidJUnit4::class)
class MpvFfmpegLogGeraetTest {
    private lateinit var server: ServerSocket
    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun startServer() {
        server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true, name = "403-server") {
            while (!server.isClosed) {
                val s = runCatching { server.accept() }.getOrNull() ?: break
                runCatching {
                    s.soTimeout = 2_000
                    val inp = s.getInputStream().bufferedReader()
                    while (true) { val l = inp.readLine() ?: break; if (l.isEmpty()) break }
                    s.getOutputStream().write(
                        "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(),
                    )
                    s.getOutputStream().flush()
                }
                runCatching { s.close() }
            }
        }
    }

    @After fun stopServer() { runCatching { server.close() } }

    private class Kern(val mpv: MPVLib) {
        val zeilen: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val ende = CountDownLatch(1)
        val log = object : MPVLib.LogObserver {
            override fun logMessage(prefix: String, level: Int, text: String) { zeilen += "$prefix: ${text.trim()}" }
        }
        val ev = object : MPVLib.EventObserver {
            override fun eventProperty(property: String) {}
            override fun eventProperty(property: String, value: Long) {}
            override fun eventProperty(property: String, value: Boolean) {}
            override fun eventProperty(property: String, value: String) {}
            override fun eventProperty(property: String, value: Double) {}
            override fun event(eventId: Int) { if (eventId == MPVLib.MpvEvent.MPV_EVENT_END_FILE) ende.countDown() }
        }
    }

    private fun neuerKern(): Kern {
        val m = MPVLib.create(ctx)!!
        m.setOptionString("vo", "null")
        m.setOptionString("ao", "null")
        m.setOptionString("idle", "yes")
        m.setOptionString("msg-level", "all=warn")
        m.init()
        return Kern(m).also { m.addLogObserver(it.log); m.addObserver(it.ev) }
    }

    /** Öffnet die 403-URL und liefert true, wenn FFmpegs „HTTP error 403“ bei DIESEM Kern ankam. */
    private fun sieht403(k: Kern): Boolean {
        k.zeilen.clear()
        k.mpv.command(arrayOf("loadfile", "http://127.0.0.1:${server.localPort}/live/u/p/364.ts"))
        assertTrue("kein END_FILE", k.ende.await(15, TimeUnit.SECONDS))
        Thread.sleep(300) // Logzeilen kommen über denselben Ereignisfaden, kurz nachlaufen lassen
        android.util.Log.i("MpvFfmpegLogTest", "Zeilen: ${k.zeilen.filter { it.contains("403") || it.contains("open", true) }}")
        return k.zeilen.any { it.contains("HTTP error 403") }
    }

    @Test fun ffmpegLogHaengtAmErstenKernUndKommtNachNeubauZurueck() {
        // 1) Ein Kern allein: 403 kommt an (3.2-Verhalten).
        val a = neuerKern()
        assertTrue("allein lebender Kern sieht 403", sieht403(a))

        // 2) A1: neuer Kern B entsteht, während A noch lebt; danach stirbt A.
        val b = neuerKern()
        a.mpv.destroy()
        assertFalse("B entstand neben A → nach A's Tod keine FFmpeg-Zeilen (der 3.2.1-Fehler)", sieht403(b))

        // 3) Fix: B zerstören, DANN C erzeugen → C bindet FFmpeg-Log neu.
        b.mpv.destroy()
        val c = neuerKern()
        assertTrue("nach Neubau (erst zerstören, dann erzeugen) kommt 403 wieder an", sieht403(c))
        c.mpv.destroy()
    }

    /**
     * Review-Fix P2: so baut OwnTVPlayer jetzt neu — der alte Kern stirbt auf SEINEM Faden (Main wartet nicht),
     * der neue entsteht erst danach (nächster Titel). Auch dann kommt 403 an.
     */
    @Test fun abbauAufEigenemFadenDannNeuerKernSieht403() {
        val a = neuerKern()
        val b = neuerKern()
        a.mpv.destroy()
        val alterFaden = java.util.concurrent.Executors.newSingleThreadExecutor()
        val fertig = CountDownLatch(1)
        alterFaden.execute { b.mpv.command(arrayOf("stop")); b.mpv.destroy(); fertig.countDown() }
        alterFaden.shutdown()
        assertTrue("Abbau fertig", fertig.await(5, TimeUnit.SECONDS))
        val c = neuerKern()
        assertTrue("Kern nach asynchronem Abbau sieht 403", sieht403(c))
        c.mpv.destroy()
    }

    /** Solange der alte Kern lebt, laufen die FFmpeg-Zeilen des neuen über IHN — darum die ffmpeg-Weiterleitung. */
    @Test fun ffmpegZeilenDesNeuenKernsLaufenUeberDenAltenSolangeErLebt() {
        val a = neuerKern()
        val b = neuerKern()
        a.zeilen.clear()
        assertFalse("B selbst bekommt die FFmpeg-Zeile nicht", sieht403(b))
        assertTrue("…aber A (Bindung) bekommt sie", a.zeilen.any { it.startsWith("ffmpeg") && it.contains("HTTP error 403") })
        b.mpv.destroy()
        a.mpv.destroy()
    }
}
