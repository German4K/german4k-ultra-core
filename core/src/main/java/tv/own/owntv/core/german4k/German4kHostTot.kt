package tv.own.owntv.core.german4k

import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * German4K 3.2.1 (p-15, Schalter [German4kSchalter.TOTER_HOST_SCHNELL]): Ein Live-Sender, dessen Server gar nicht
 * antwortet, lief durch die ganze Leiter — ExoPlayer, dann mpv mit drei Wiederholungen und Formatwechsel
 * (.ts → .m3u8) — und zeigte den Fehler erst nach 20–55 s (Abnahme k8, tv41). Scheitert schon der
 * Verbindungsaufbau (abgelehnt, Name nicht auflösbar, kein Weg, Zeitüberschreitung beim Verbinden) und bestätigt
 * eine kurze zweite Probe das, ist jeder weitere Versuch auf demselben Server verlorene Zeit.
 *
 * Bewusst NICHT hier: ein Server, der die Verbindung annimmt und dann langsam oder gar nicht liefert
 * (Lesezeitüberschreitung, „unexpected end of stream“, HTTP-Status) — langsame, aber antwortende Streams bleiben
 * wie bisher.
 */
object German4kHostTot {
    /** Verbindungsaufbau für Live-Streams (statt 15 s): ein erreichbarer Server antwortet auf SYN in Millisekunden. */
    const val VERBINDEN_MS = 9_000L

    /** Frist der zweiten Probe nach einem Verbindungsfehler. */
    const val PROBE_MS = 3_000L

    // Technische Erkennungswörter aus Java-/OkHttp-/FFmpeg-Fehlertexten, keine Oberflächentexte.
    private val VERBINDUNG = listOf(
        "connectexception", "connection refused", "econnrefused",
        "unknownhostexception", "unable to resolve host", "failed to resolve hostname", "no address associated with hostname",
        "noroutetohostexception", "no route to host", "ehostunreach", "network is unreachable", "enetunreach",
        "failed to connect to",
    )
    private val HTTP_STATUS = Regex("""(?:response code[:= ]+|http(?: error)? |status(?: code)?[:= ]+)(\d{3})\b""")

    // German4K 3.2.1 (p-16): Server nimmt an und schließt, bevor auch nur eine Kopfzeile kommt — OkHttp/ExoPlayer bzw. FFmpeg.
    private val ENDE_OHNE_ANTWORT = listOf("unexpected end of stream", "error reading http response: end of file")

    /**
     * German4K 3.2.1 (p-16, Schalter [German4kSchalter.TOTER_STREAM_SCHNELL]): [reason] heißt „Verbindung angenommen,
     * sofort geschlossen, keine HTTP-Antwort". Mit HTTP-Status (der Server hat geantwortet) nie. Ein langsamer Server
     * fällt nicht darunter — der schließt nicht, er schweigt (Zeitüberschreitung).
     */
    fun endeOhneAntwort(reason: String?): Boolean {
        if (reason.isNullOrBlank()) return false
        val r = reason.lowercase(Locale.ROOT)
        if (HTTP_STATUS.containsMatchIn(r)) return false
        return ENDE_OHNE_ANTWORT.any { it in r }
    }

    /** [reason] beschreibt einen gescheiterten Verbindungsaufbau (kein HTTP-Status, keine Antwort des Servers). */
    fun verbindungsfehler(reason: String?): Boolean {
        if (reason.isNullOrBlank()) return false
        val r = reason.lowercase(Locale.ROOT)
        if (HTTP_STATUS.containsMatchIn(r)) return false
        return VERBINDUNG.any { it in r }
    }

    // German4K 3.2.1 (t-8): Ziel der Probe — Host und Port der Stream-URL; fehlt der Port, der Standard des Schemas
    // (http 80, https 443). Kein http(s) → null, die Probe bleibt dann unklar (= „antwortet“).
    fun ziel(url: String): Pair<String, Int>? {
        val u = url.trim().toHttpUrlOrNull() ?: return null
        return u.host to u.port
    }

    /**
     * German4K 3.2.1 (t-10): Mindestzeit, die einem Verbindungsversuch nach der Namensauflösung bleiben muss. Weniger
     * Rest = kein echter Versuch = unklar (alte Leiter), nie „tot“.
     */
    const val MIN_VERSUCH_MS = 500L

    /** German4K 3.2.1 (t-10): Höchstens so viele Adressen gleichzeitig; mehr Adressen = nicht alle geprüft = unklar. */
    const val MAX_ADRESSEN = 4

    /** German4K 3.2.1 (t-10): IPv4 zuerst (wie [German4kDns]), Reihenfolge innerhalb einer Familie bleibt. */
    fun reihenfolge(liste: List<java.net.InetAddress>): List<java.net.InetAddress> =
        liste.filterIsInstance<java.net.Inet4Address>() + liste.filter { it !is java.net.Inet4Address }

    /**
     * German4K 3.2.1 (t-8): Zweite Probe als reiner TCP-Verbindungsaufbau auf Host:Port von [url] — KEIN HTTP, keine
     * Weiterleitung. Ein HTTP-GET (auch mit Range) öffnet bei Live-TS nach dem 302 eine echte Sitzung beim Anbieter;
     * startet danach mpv auf derselben Leitung, meldet ein 1-Verbindungs-Zugang 458 → falscher Belegt-Dialog.
     *
     * [lookup] ist das DNS der Wiedergabe (gleicher Resolver). Alles Unklare zählt als „antwortet“ (alte Leiter läuft).
     *
     * German4K 3.2.1 (t-10): „Tot“ nur, wenn JEDE Adresse wirklich versucht wurde und jede den Aufbau abgelehnt hat bzw.
     * innerhalb ihrer Teilfrist unerreichbar blieb. Vorher fraß die erste Adresse (oft IPv6, kaputt beim Kunden) die
     * ganze Frist, IPv4 wurde nie versucht, und eine lange Namensauflösung ließ gar keinen Versuch übrig — beides
     * endete als „tot“ (falscher Schnell-Fehler). Jetzt: IPv4 zuerst, alle Adressen (höchstens [MAX_ADRESSEN])
     * gleichzeitig mit dem vollen Rest als Teilfrist (Happy-Eyeballs-artig), die erste Antwort gewinnt; bleibt nach der
     * Namensauflösung weniger als [MIN_VERSUCH_MS] oder gibt es mehr Adressen als geprüft → unklar.
     * [ueberProxy]: Die App leitet über ihren Proxy — ein direkter Aufbau sagt dann nichts → Probe entfällt, unklar.
     */
    suspend fun tcpAntwortet(
        url: String,
        fristMs: Long = PROBE_MS,
        lookup: (String) -> List<java.net.InetAddress>,
        ueberProxy: Boolean = false,
        // German4K 3.2.1 (t-11): Socket-Quelle austauschbar, damit der Test das Schließen beim Abbruch prüfen kann.
        // Vor [log], damit der nachgestellte Log-Lambda der Aufrufer weiter an [log] geht.
        neuerSocket: () -> java.net.Socket = { java.net.Socket() },
        log: (String) -> Unit = {},
    ): Boolean {
        val (host, port) = ziel(url) ?: return true
        // German4K 3.2.1 (t-10): Proxy aktiv → die Wiedergabe baut gar nicht direkt auf; Probe überspringen.
        if (ueberProxy) { log("g4k host probe skipped: proxy"); return true }
        val start = System.nanoTime()
        fun rest(): Long = fristMs - (System.nanoTime() - start) / 1_000_000L
        // Die Namensauflösung blockiert ohne eigene Frist — losgelöst starten und nur [fristMs] lang warten.
        val aufloesung = CoroutineScope(Dispatchers.IO).async { runCatching { lookup(host) } }
        val adressen = withTimeoutOrNull(fristMs) { aufloesung.await() } ?: run {
            log("g4k host probe inconclusive: dns timeout"); return true
        }
        adressen.exceptionOrNull()?.let { e ->
            val tot = e is java.net.UnknownHostException
            log("g4k host probe ${if (tot) "dead" else "inconclusive"}: ${e.javaClass.simpleName}")
            return !tot
        }
        val liste = reihenfolge(adressen.getOrNull().orEmpty())
        if (liste.isEmpty()) { log("g4k host probe dead: no address"); return false }
        // German4K 3.2.1 (t-10): Kein echter Versuch möglich (DNS hat die Frist verbraucht) → unklar, nicht tot.
        val teilfrist = rest()
        if (teilfrist < MIN_VERSUCH_MS) { log("g4k host probe inconclusive: no time left"); return true }
        val auswahl = liste.take(MAX_ADRESSEN)
        val sockets = java.util.concurrent.ConcurrentLinkedQueue<java.net.Socket>()
        val ergebnis = kotlinx.coroutines.CompletableDeferred<Boolean>()
        val offen = java.util.concurrent.atomic.AtomicInteger(auswahl.size)
        val r: Boolean
        // Losgelöst wie die Namensauflösung: ein blockierender connect() lässt sich nicht abbrechen; nach der
        // Entscheidung schließen wir die übrigen Sockets, das beendet hängende Versuche sofort.
        val scope = CoroutineScope(Dispatchers.IO)
        // German4K 3.2.1 (t-11): Abbruch (Zappen) während der Probe → finally schließt alle Sockets und bricht die
        // Versuche ab. Ein Versuch, der erst danach anläuft, registriert seinen Socket zuerst und prüft dann [zu] —
        // so verbindet nach dem Schließen keiner mehr (entweder sieht finally den Socket, oder der Versuch sieht [zu]).
        val zu = java.util.concurrent.atomic.AtomicBoolean(false)
        val versuche = ArrayList<kotlinx.coroutines.Deferred<Unit>>(auswahl.size)
        try {
            for (a in auswahl) versuche += scope.async {
                val s = neuerSocket().also { sockets.add(it) }
                if (zu.get()) { runCatching { s.close() }; return@async }
                try {
                    s.use { it.connect(java.net.InetSocketAddress(a, port), teilfrist.toInt().coerceAtLeast(1)) }
                    if (ergebnis.complete(true)) log("g4k host probe alive")
                } catch (e: java.io.IOException) {
                    if (ergebnis.isCompleted || zu.get()) return@async // entschieden/abgebrochen; u. a. unser eigenes close()
                    if (!verbindungsaufbauGescheitert(e)) {
                        log("g4k host probe inconclusive: ${e.javaClass.simpleName}")
                        ergebnis.complete(true)
                    } else if (offen.decrementAndGet() == 0) {
                        log("g4k host probe dead: ${e.javaClass.simpleName}")
                        ergebnis.complete(false)
                    }
                }
            }
            // Etwas Spielraum über der Teilfrist; kommt trotzdem nichts → unklar.
            r = withTimeoutOrNull(teilfrist + 1_000L) { ergebnis.await() } ?: run {
                log("g4k host probe inconclusive: no verdict"); true
            }
        } finally {
            zu.set(true)
            sockets.forEach { runCatching { it.close() } }
            versuche.forEach { it.cancel() }
        }
        // Mehr Adressen als geprüft: die ungeprüften könnten antworten → unklar.
        if (!r && auswahl.size < liste.size) { log("g4k host probe inconclusive: untested addresses"); return true }
        return r
    }

    /**
     * German4K 3.2.1 (t-10): Läuft [url] über einen Proxy des [client] (App-Proxy aus den Netzwerk-Einstellungen, liest
     * den lebenden Stand von ProxyConfigHolder; aktiv = `usable`)? Dann baut die Wiedergabe nie direkt auf — die
     * TCP-Probe würde etwas anderes messen als der Player. Unklar (Fehler beim Fragen) zählt als Proxy → Probe entfällt.
     */
    fun ueberProxy(client: okhttp3.OkHttpClient, url: String): Boolean = runCatching {
        client.proxy?.let { return@runCatching it.type() != java.net.Proxy.Type.DIRECT }
        client.proxySelector.select(java.net.URI(url.trim())).orEmpty().any { it.type() != java.net.Proxy.Type.DIRECT }
    }.getOrDefault(true)

    /** Ein Socket-Fehler, der eindeutig „Verbindungsaufbau gescheitert“ heißt (abgelehnt, kein Weg, Zeit abgelaufen). */
    internal fun verbindungsaufbauGescheitert(e: Throwable): Boolean =
        e is java.net.ConnectException || e is java.net.NoRouteToHostException ||
            e is java.net.SocketTimeoutException || e is java.net.UnknownHostException ||
            verbindungsfehler("${e.javaClass.name}: ${e.message}")

    /**
     * German4K 3.2.1 (t-8): Schnell-Fehler nur, wenn das GERÄT nachweislich online ist (aktives Netz mit
     * NET_CAPABILITY_VALIDATED). Sonst sind DNS-/„network is unreachable“-Fehler eigene Netzprobleme (Standby, WLAN
     * weg) und die alte Leiter, die sich oft von selbst fängt, läuft weiter. Unbekannt = nicht online.
     */
    fun geraetOnline(context: android.content.Context): Boolean = runCatching {
        val cm = context.getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as? android.net.ConnectivityManager
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        caps != null &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }.getOrDefault(false)

    /**
     * German4K 3.2.1 (t-8): Gilt das Ergebnis der Probe noch für den laufenden Abstimmversuch? Während der Probe kann
     * die Frist abgelaufen sein (Fehlerbild steht schon), oder es wurde weg- und zurückgezappt (neuer Versuch, gleiche
     * URL). Dann tut die alte Probe nichts — weder Fehler zeigen noch mpv starten.
     */
    fun probeGilt(tokenVorher: Long, tokenJetzt: Long, besitzt: Boolean, aufSchirm: Boolean, abgelaufen: Boolean): Boolean =
        tokenVorher == tokenJetzt && besitzt && aufSchirm && !abgelaufen
}
