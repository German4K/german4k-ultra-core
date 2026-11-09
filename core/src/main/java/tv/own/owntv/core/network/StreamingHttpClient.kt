package tv.own.owntv.core.network

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The OkHttp client the **playback** engines stream through.
 *
 * It is derived from the app-wide singleton with [OkHttpClient.newBuilder], so it keeps every piece of
 * shared configuration — proxy selector/authenticator, custom DNS, timeouts, HTTP/1.1, the default
 * User-Agent interceptor — and picks up runtime proxy/DNS changes exactly as before. What it does *not*
 * share is the **connection pool**: this one owns its sockets.
 *
 * That split is the whole point (F28). A panel that allows one session per account refuses the second
 * client, so after an engine handoff a pooled ExoPlayer connection can lock mpv out of the channel the
 * user just asked for — the live engine therefore evicts its idle sockets on every stop. Evicting the
 * *singleton's* pool did that to the whole app: EPG downloads, panel API calls, TMDB metadata and Coil
 * image loads all lost keep-alive on every zap and paid a fresh TCP+TLS handshake afterwards.
 * [evictAll] here touches stream connections only.
 */
class StreamingHttpClient(base: OkHttpClient) {

    /** Sized for the handful of concurrent stream/segment connections one playback session opens; the
     *  idle keep-alive matches OkHttp's own default, since a zap back to the previous channel benefits
     *  from a warm socket and only a *stop* clears the pool. */
    private val pool = ConnectionPool(MAX_IDLE_CONNECTIONS, KEEP_ALIVE_MINUTES, TimeUnit.MINUTES)

    val client: OkHttpClient = base.newBuilder().connectionPool(pool).build()

    /** Close the idle stream sockets. Connections with a call in flight are untouched — OkHttp only
     *  evicts what nothing is using. */
    fun evictAll() {
        pool.evictAll()
    }

    /** German4K 3.2.1 (q-4/7): Verbindungen mit laufendem Aufruf (Stream/Segment noch offen). */
    fun belegt(): Int = (pool.connectionCount() - pool.idleConnectionCount()).coerceAtLeast(0)

    /**
     * German4K 3.2.1 (q-4/7): wartet, bis keine Stream-Verbindung mehr belegt ist (ExoPlayer schließt die Leitung einer
     * freigegebenen Engine auf seinem Lade-Faden, also kurz NACH release()), und schließt dann die leeren Sockets.
     * true = frei, false = Frist abgelaufen. Ohne das öffnete das Vollbild nach Multiview, während der Anbieter die
     * Kachel noch zählte → 458/460 „Leitung belegt" für die eigene Sitzung.
     */
    suspend fun warteBisFrei(fristMs: Long = LeitungFreigabe.FRIST_MS): Boolean =
        // German4K 3.2.1 (p-14): evictAll schließt TLS-Sockets (Netz-I/O) → nie auf Main, nie ungefangen.
        LeitungFreigabe.warteAufIo(fristMs, raeumen = ::evictAll, belegt = ::belegt)

    private companion object {
        const val MAX_IDLE_CONNECTIONS = 5
        const val KEEP_ALIVE_MINUTES = 5L
    }
}
