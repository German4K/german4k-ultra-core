package tv.own.owntv.core.german4k

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.launch

/** German4K 3.2.1 (p-15): nur ein gescheiterter Verbindungsaufbau zählt als „Server antwortet gar nicht“. */
class German4kHostTotTest {
    private val t = German4kHostTot

    @Test fun abgelehntExo() = assertTrue(t.verbindungsfehler(
        "ExoPlayer error before first frame: ERROR_CODE_IO_NETWORK_CONNECTION_FAILED: java.io.IOException: " +
            "java.util.concurrent.ExecutionException: java.net.ConnectException: Failed to connect to /127.0.0.1:8853",
    ))

    @Test fun abgelehntMpv() = assertTrue(t.verbindungsfehler("tcp: Connection to tcp://127.0.0.1:8853 failed: Connection refused"))

    @Test fun dns() {
        assertTrue(t.verbindungsfehler("java.net.UnknownHostException: Unable to resolve host \"x.example\": No address associated with hostname"))
        assertTrue(t.verbindungsfehler("Failed to resolve hostname x.example: Try again"))
    }

    @Test fun verbindungsaufbauZeitueberschreitung() = assertTrue(t.verbindungsfehler(
        "ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT: java.net.SocketTimeoutException: failed to connect to /10.255.255.1 (port 80) from /10.0.2.16 (port 4711) after 9000ms",
    ))

    @Test fun keinWeg() = assertTrue(t.verbindungsfehler("java.net.NoRouteToHostException: Host unreachable"))

    @Test fun langsamOderAntwortendBleibt() {
        assertFalse(t.verbindungsfehler("ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT: java.net.SocketTimeoutException: timeout"))
        assertFalse(t.verbindungsfehler("java.io.IOException: unexpected end of stream on http://127.0.0.1:8853/..."))
        assertFalse(t.verbindungsfehler("ExoPlayer never opened it (12s, no frame and no error)"))
        assertFalse(t.verbindungsfehler("ERROR_CODE_IO_BAD_HTTP_STATUS: Response code: 458"))
        assertFalse(t.verbindungsfehler("HTTP error 503 Service Unavailable, failed to connect to upstream"))
        assertFalse(t.verbindungsfehler(null))
        assertFalse(t.verbindungsfehler(""))
    }
}

/** German4K 3.2.1 (t-8): Probe nur als TCP-Verbindungsaufbau, nie HTTP; Gültigkeit nach der Probe. */
class German4kHostTotTcpTest {
    private val t = German4kHostTot
    private val lokal: (String) -> List<java.net.InetAddress> = { listOf(java.net.InetAddress.getLoopbackAddress()) }

    @Test fun zielMitStandardPort() {
        org.junit.Assert.assertEquals("a.example" to 80, t.ziel("http://a.example/live/u/p/1.ts"))
        org.junit.Assert.assertEquals("a.example" to 443, t.ziel("https://a.example/live/u/p/1.m3u8"))
        org.junit.Assert.assertEquals("a.example" to 8080, t.ziel("http://a.example:8080/x"))
        org.junit.Assert.assertEquals("b.example" to 8443, t.ziel("https://b.example:8443/x"))
        org.junit.Assert.assertNull(t.ziel("rtmp://a.example/x"))
        org.junit.Assert.assertNull(t.ziel("kaputt"))
    }

    @Test fun offenerPortAntwortetOhneHttp() {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { srv ->
            srv.soTimeout = 3_000
            val gelesen = java.util.concurrent.atomic.AtomicInteger(-2)
            val th = Thread {
                runCatching { srv.accept().use { s -> s.soTimeout = 1_500; gelesen.set(runCatching { s.getInputStream().read() }.getOrDefault(-3)) } }
            }.apply { start() }
            val ok = kotlinx.coroutines.runBlocking { t.tcpAntwortet("http://x.example:${srv.localPort}/live/1.ts", 2_000, lokal) }
            th.join(4_000)
            assertTrue(ok)
            // Kein einziges Byte gesendet (kein GET, kein Range) — die Gegenseite sieht nur Aufbau und Schließen.
            org.junit.Assert.assertEquals(-1, gelesen.get())
        }
    }

    @Test fun geschlossenerPortIstTot() {
        val port = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }
        assertFalse(kotlinx.coroutines.runBlocking { t.tcpAntwortet("http://x.example:$port/1.ts", 2_000, lokal) })
    }

    @Test fun dnsUnbekanntIstTotAndereFehlerUnklar() {
        assertFalse(kotlinx.coroutines.runBlocking { t.tcpAntwortet("http://x.example/1.ts", 1_000, { throw java.net.UnknownHostException(it) }) })
        assertTrue(kotlinx.coroutines.runBlocking { t.tcpAntwortet("http://x.example/1.ts", 1_000, { throw java.io.IOException("x") }) })
        assertTrue(kotlinx.coroutines.runBlocking { t.tcpAntwortet("rtmp://x.example/1", 1_000, lokal) })
    }

    @Test fun dnsHaengtIstUnklar() {
        val start = System.nanoTime()
        assertTrue(kotlinx.coroutines.runBlocking { t.tcpAntwortet("http://x.example/1.ts", 300, { Thread.sleep(2_000); emptyList() }) })
        assertTrue((System.nanoTime() - start) / 1_000_000 < 1_500)
    }

    @Test fun probeGiltNurFuerDenselbenVersuch() {
        assertTrue(t.probeGilt(3, 3, besitzt = true, aufSchirm = true, abgelaufen = false))
        assertFalse(t.probeGilt(3, 4, besitzt = true, aufSchirm = true, abgelaufen = false)) // weg- und zurückgezappt
        assertFalse(t.probeGilt(3, 3, besitzt = false, aufSchirm = true, abgelaufen = false))
        assertFalse(t.probeGilt(3, 3, besitzt = true, aufSchirm = false, abgelaufen = false))
        assertFalse(t.probeGilt(3, 3, besitzt = true, aufSchirm = true, abgelaufen = true)) // Frist abgelaufen
    }

    /** Ohne ConnectivityManager (oder ohne aktives Netz) gilt das Gerät NICHT als online → kein Schnell-Fehler. */
    @Test fun unbekanntesNetzIstNichtOnline() = assertFalse(t.geraetOnline(android.content.ContextWrapper(null)))

    // German4K 3.2.1 (p-16): „angenommen, sofort zu" — ExoPlayer/OkHttp und FFmpeg, nie mit HTTP-Status, nie bei Zeitüberschreitung.
    @Test fun endeOhneAntwortExo() = assertTrue(
        t.endeOhneAntwort(
            "ExoPlayer error before first frame: ERROR_CODE_IO_NETWORK_CONNECTION_FAILED: java.io.IOException: " +
                "java.util.concurrent.ExecutionException: java.io.IOException: unexpected end of stream on http://10.0.2.2:8963/...",
        ),
    )

    @Test fun endeOhneAntwortMpv() = assertTrue(t.endeOhneAntwort("ffmpeg: http: Error reading HTTP response: End of file"))

    @Test fun endeOhneAntwortNichtBeiStatusZeitOderLeer() {
        assertFalse(t.endeOhneAntwort("ffmpeg: http: HTTP error 458 Unknown Error"))
        assertFalse(t.endeOhneAntwort("Response code: 503 unexpected end of stream"))
        assertFalse(t.endeOhneAntwort("java.net.SocketTimeoutException: timeout"))
        assertFalse(t.endeOhneAntwort("ffmpeg: tcp: Connection timed out"))
        assertFalse(t.endeOhneAntwort(null))
        assertFalse(t.endeOhneAntwort(""))
    }

    // --- German4K 3.2.1 (t-10) ---
    private fun geschlossenerPort() = java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress()).use { it.localPort }
    private val v4 = java.net.InetAddress.getByName("127.0.0.1")

    @Test fun dnsFrisstFristIstUnklarNichtTot() {
        val port = geschlossenerPort()
        // Namensauflösung braucht fast die ganze Frist → kein echter Verbindungsversuch → alte Leiter, nicht „tot“.
        assertTrue(kotlinx.coroutines.runBlocking {
            t.tcpAntwortet("http://x.example:$port/1.ts", 1_000, { Thread.sleep(800); listOf(v4) })
        })
    }

    @Test fun ipv4ZuerstReihenfolge() {
        val v6 = java.net.InetAddress.getByName("2001:db8::1")
        val v4b = java.net.InetAddress.getByName("192.0.2.7")
        org.junit.Assert.assertEquals(listOf(v4, v4b, v6), t.reihenfolge(listOf(v6, v4, v4b)))
    }

    @Test fun unerreichbaresIpv6FrisstIpv4Nicht() {
        java.net.ServerSocket(0, 5, v4).use { srv ->
            val th = Thread { runCatching { srv.soTimeout = 3_000; srv.accept().close() } }.apply { start() }
            val v6 = java.net.InetAddress.getByName("2001:db8::1") // Dokumentationsnetz: hängt oder kein Weg
            val start = System.nanoTime()
            val ok = kotlinx.coroutines.runBlocking {
                t.tcpAntwortet("http://x.example:${srv.localPort}/1.ts", 2_000, { listOf(v6, v4) })
            }
            th.join(4_000)
            assertTrue(ok)
            assertTrue((System.nanoTime() - start) / 1_000_000 < 1_500)
        }
    }

    @Test fun alleAbgelehntIstTot() {
        val port = geschlossenerPort()
        assertFalse(kotlinx.coroutines.runBlocking { t.tcpAntwortet("http://x.example:$port/1.ts", 2_000, { listOf(v4, v4) }) })
    }

    @Test fun ungepruefteAdressenSindUnklar() {
        val port = geschlossenerPort()
        val viele = List(t.MAX_ADRESSEN + 1) { v4 }
        assertTrue(kotlinx.coroutines.runBlocking { t.tcpAntwortet("http://x.example:$port/1.ts", 2_000, { viele }) })
    }

    @Test fun proxyUeberspringtProbe() {
        val port = geschlossenerPort()
        assertTrue(kotlinx.coroutines.runBlocking { t.tcpAntwortet("http://x.example:$port/1.ts", 2_000, { listOf(v4) }, ueberProxy = true) })
        val direkt = okhttp3.OkHttpClient()
        assertFalse(t.ueberProxy(direkt, "http://x.example/1.ts"))
        val mitProxy = okhttp3.OkHttpClient.Builder()
            .proxy(java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress.createUnresolved("p.example", 8080))).build()
        assertTrue(t.ueberProxy(mitProxy, "http://x.example/1.ts"))
        val holder = tv.own.owntv.core.network.ProxyConfigHolder(kotlinx.coroutines.flow.flowOf(
            tv.own.owntv.core.network.ProxyConfig(enabled = true, host = "p.example", port = 8080)))
        val ueberHolder = okhttp3.OkHttpClient.Builder().proxySelector(holder.proxySelector).build()
        val ende = System.currentTimeMillis() + 2_000
        while (holder.snapshot().port != 8080 && System.currentTimeMillis() < ende) Thread.onSpinWait()
        assertTrue(t.ueberProxy(ueberHolder, "http://x.example/1.ts"))
    }

    // German4K 3.2.1 (t-11): connect() hängt, bis der Socket geschlossen wird — wie ein Versuch, der die Teilfrist abwartet.
    private class HaengenderSocket : java.net.Socket() {
        val zu = java.util.concurrent.CountDownLatch(1)
        @Volatile var verbunden = false
        override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) {
            if (isClosed) throw java.net.SocketException("Socket is closed")
            zu.await(10, java.util.concurrent.TimeUnit.SECONDS)
            if (!isClosed) verbunden = true
            throw java.net.SocketException("Socket closed")
        }
        override fun close() { super.close(); zu.countDown() }
    }

    @Test fun abbruchSchliesstAlleSockets() = kotlinx.coroutines.runBlocking {
        val sockets = java.util.concurrent.ConcurrentLinkedQueue<HaengenderSocket>()
        val vier = List(t.MAX_ADRESSEN) { v4 }
        val probe = launch(kotlinx.coroutines.Dispatchers.Default) {
            t.tcpAntwortet("http://x.example:80/1.ts", 3_000, { vier }, neuerSocket = { HaengenderSocket().also { sockets.add(it) } })
        }
        val ende = System.currentTimeMillis() + 3_000
        while (sockets.size < t.MAX_ADRESSEN && System.currentTimeMillis() < ende) kotlinx.coroutines.delay(10)
        org.junit.Assert.assertEquals(t.MAX_ADRESSEN, sockets.size)
        val vorher = System.currentTimeMillis()
        probe.cancel()
        probe.join()
        assertTrue("Abbruch muss sofort greifen", System.currentTimeMillis() - vorher < 1_000)
        assertTrue(sockets.all { it.isClosed })
        assertTrue(sockets.none { it.verbunden })
    }

    @Test fun spaeterVersuchVerbindetNachAbbruchNicht() = kotlinx.coroutines.runBlocking {
        // Socket-Quelle wartet, bis der Abbruch durch ist — der Versuch startet also erst nach dem Schließen.
        val los = java.util.concurrent.CountDownLatch(1)
        val erzeugt = java.util.concurrent.ConcurrentLinkedQueue<HaengenderSocket>()
        val probe = launch(kotlinx.coroutines.Dispatchers.Default) {
            t.tcpAntwortet("http://x.example:80/1.ts", 3_000, { listOf(v4) }, neuerSocket = {
                los.await(5, java.util.concurrent.TimeUnit.SECONDS); HaengenderSocket().also { erzeugt.add(it) }
            })
        }
        kotlinx.coroutines.delay(200)
        probe.cancel(); probe.join()
        los.countDown()
        val ende = System.currentTimeMillis() + 2_000
        while (erzeugt.isEmpty() && System.currentTimeMillis() < ende) kotlinx.coroutines.delay(10)
        kotlinx.coroutines.delay(100)
        // Entweder kam der Versuch gar nicht mehr dran (abgebrochen) oder sein Socket ist sofort zu, ohne Verbindung.
        assertTrue(erzeugt.all { it.isClosed && !it.verbunden })
    }
}
