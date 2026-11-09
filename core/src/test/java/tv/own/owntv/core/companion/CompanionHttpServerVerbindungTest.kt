package tv.own.owntv.core.companion

import android.content.ContextWrapper
import android.content.SharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.i18n.LocaleStore
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList

/**
 * German4K 3.3.1 (B2): Feld-Absturz „SocketTimeoutException: Read timed out“ in CompanionHttpServer.readLine — ein
 * Handy/Scanner verbindet und schickt nichts. Die Lese-Frist darf nur protokolliert werden, nie an den Thread-Handler
 * gehen (das beendet die App), und der Server muss danach weiter annehmen.
 */
class CompanionHttpServerVerbindungTest {

    private val leerePrefs = java.lang.reflect.Proxy.newProxyInstance(
        javaClass.classLoader, arrayOf(SharedPreferences::class.java),
    ) { _, _, _ -> null } as SharedPreferences

    @Test(timeout = 20_000)
    fun `stumme Verbindung laeuft in die Frist ohne unbehandelte Ausnahme, danach nimmt der Server weiter an`() {
        val unbehandelt = CopyOnWriteArrayList<Throwable>()
        val vorher = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, e -> unbehandelt.add(e) }
        val server = CompanionHttpServer(ContextWrapper(null), LocaleStore(leerePrefs, null))
        server.leseFristTestMs = 200
        try {
            val port = ServerSocket(0).use { it.localPort }
            server.start(port, pin = "123456", fontBytes = byteArrayOf(1, 2, 3))

            // Verbinden, nichts senden: der Server läuft in die Frist und schließt die Verbindung.
            Socket("127.0.0.1", port).use { stumm ->
                stumm.soTimeout = 5_000
                assertEquals(-1, stumm.getInputStream().read())
            }
            Thread.sleep(300) // Fang/Log nach dem Schließen abwarten
            assertTrue("unbehandelt: $unbehandelt", unbehandelt.isEmpty())

            // Der Server nimmt weiter an und antwortet.
            Socket("127.0.0.1", port).use { s ->
                s.soTimeout = 5_000
                s.getOutputStream().write("GET /lora.ttf HTTP/1.1\r\n\r\n".toByteArray())
                val antwort = s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
                assertTrue(antwort, antwort.startsWith("HTTP/1.1 200"))
            }
            assertTrue("unbehandelt: $unbehandelt", unbehandelt.isEmpty())
        } finally {
            server.close()
            Thread.setDefaultUncaughtExceptionHandler(vorher)
        }
    }
}
