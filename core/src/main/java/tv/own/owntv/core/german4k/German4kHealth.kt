package tv.own.owntv.core.german4k

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.model.SourceType
import java.net.InetAddress
import java.util.Locale
import kotlin.math.abs

/**
 * "Verbindung prüfen" — the one button support can name instead of asking for photos.
 *
 * Every day the same three sentences are typed into chats: "läuft noch ein zweites Gerät?",
 * "hast du dich im Hotel-WLAN angemeldet?", "während des Spiels sperrt dein Anbieter". All three are
 * things the device can find out in five seconds, and all three are invisible from the server: we see
 * a line that works, the customer sees a black screen. So the app measures its own way out — internet,
 * panel, both hosts, one real stream — and turns the result into one sentence the customer can act on.
 *
 * The same classifier runs on playback failures ([klassifiziere]), so the player can say "dein Zugang
 * läuft gerade auf einem anderen Gerät" instead of "STREAM FAILED".
 */
class German4kHealth(
    private val context: Context,
    private val client: OkHttpClient,
    private val panel: German4kPanelClient,
    private val sourceDao: SourceDao,
    private val channelDao: ChannelDao,
) {

    /** What went wrong, in the customer's world rather than in HTTP terms. */
    enum class Klasse {
        ALLES_GUT,
        /** No network at all — flight mode, WiFi off, router dead. */
        KEIN_NETZ,
        /** The network demands a browser login first (hotel, guest WiFi, HTTP 511). */
        ZWANGSPORTAL,
        /** Internet works, but this router blocks our address (filter, parental control, blocklist). */
        ROUTER_SPERRE,
        /** Everything of ours answers, but no stream plays — typically an ISP block during a match. */
        ANBIETER_SPERRE,
        /**
         * Der Zulieferer weist die IP dieses Anschlusses am zweiten Sprung ab (HTTP 511), während die
         * Anmeldung durchgeht. Gemessen am 19.09.2026 bei einem VPN mit deutschem Ausgang: dieselbe
         * Line, derselbe Sender, zwanzig Minuten später über ein anderes Land — Bild. Von außen sieht
         * das aus wie [ANBIETER_SPERRE], der Rat ist aber der umgekehrte: nicht VPN einschalten,
         * sondern das Land wechseln oder den Bedrohungsschutz abschalten.
         */
        VPN_FILTER,
        /** One host is unreachable; the app switched to the other by itself. */
        HOST_AUSFALL,
        /** The line is busy on another device (or a stuck session). */
        LEITUNG_BELEGT,
        /** The access has run out. */
        ABGELAUFEN,
        /** The line is not allowed from this country. */
        LAND_GESPERRT,
        /** The device clock is far off — the guide looks shifted, catch-up misses. */
        UHRZEIT,
        UNBEKANNT,
    }

    /**
     * One measured step. [schluessel] is what the UI translates (netz/internet/panel/host/stream/uhr);
     * [name] is the plain German wording that goes into the report we read — those are two different
     * audiences, and forcing one string to serve both put German words on an English screen.
     */
    data class Schritt(val schluessel: String, val name: String, val ok: Boolean, val info: String, val zusatz: String = "")

    data class Befund(
        val klasse: Klasse,
        val schritte: List<Schritt>,
        /** Clock difference to the panel in seconds (positive: device ahead). */
        val uhrAbweichungSek: Long,
        val netzart: String,
        /** Die Weiterleitungskette bis zum Bild — leer, wenn keine Quelle eingerichtet ist. */
        val kette: List<Sprung> = emptyList(),
        /** Endkundennetz oder Rechenzentrum/VPN. Nur Einordnung, kein Urteil. */
        val zweig: String = "",
        val mbit: Double = 0.0,
    ) {
        fun bericht(): String = buildString {
            appendLine("Selbsttest: $klasse")
            appendLine("Netz: $netzart · Uhr ${if (abs(uhrAbweichungSek) < 60) "ok" else "${uhrAbweichungSek}s daneben"}")
            if (zweig.isNotBlank()) appendLine("Zweig: $zweig${if (mbit > 0) " · ${"%.1f".format(mbit)} Mbit/s" else ""}")
            schritte.forEach { appendLine("${if (it.ok) "ok  " else "FEHL"} ${it.name}: ${it.info}") }
            if (kette.isNotEmpty()) {
                appendLine("Kette:")
                kette.forEach { appendLine("  ${it.nr}. ${it.host} → HTTP ${it.status}${it.ziel?.let { z -> " → $z" } ?: ""}${it.fehler?.let { f -> " ($f)" } ?: ""}") }
            }
        }
    }

    /**
     * Run the whole check. Never throws: every step is caught, because this is the screen someone
     * opens *because* something is already broken.
     */
    suspend fun pruefe(answer: German4kPanelAnswer?): Befund = withContext(Dispatchers.IO) {
        val schritte = mutableListOf<Schritt>()
        val netzart = netzart()
        schritte += Schritt(KEY_NETZ, SCHRITT_NETZ, netzart != NETZ_KEINS, netzart)

        // 1. Plain internet. A captive portal answers this with 511 or redirects somewhere else —
        //    that is the difference between "no internet" and "this network wants a login first".
        val netz = pruefeInternet()
        schritte += netz.schritt

        // 2. Our panel. It always answers 200 with a body, so anything else is transport.
        val panelOk = runCatching {
            panel.fetch(German4kDeviceId.get(context), tv.own.owntv.core.CoreBuildInfo.versionName)
        }.onFailure { Log.w(TAG, "panel check: ${it.message}") }.getOrNull()
        schritte += Schritt(KEY_PANEL, SCHRITT_PANEL, panelOk != null, if (panelOk != null) "erreichbar" else "keine Antwort")

        // 3. Every host of the active line, with the real credentials: this is what the player does.
        val quelle = runCatching { sourceDao.getAllOnce().firstOrNull { it.type == SourceType.XTREAM } }.getOrNull()
        val hosts = (answer ?: panelOk)?.sources?.firstOrNull()?.let { listOf(it.server) + it.altServers }
            ?: quelle?.url?.let { listOf(it) }
            ?: emptyList()
        val hostBefunde = mutableListOf<HostBefund>()
        for (host in hosts) {
            val user = quelle?.username ?: (answer ?: panelOk)?.sources?.firstOrNull()?.username
            val pass = quelle?.password ?: (answer ?: panelOk)?.sources?.firstOrNull()?.password
            if (user.isNullOrBlank() || pass.isNullOrBlank()) break
            val b = pruefeHost(host, user, pass)
            hostBefunde += b
            schritte += Schritt(KEY_HOST, "$SCHRITT_HOST ${kurz(host)}", b.ok, b.info, zusatz = kurz(host))
        }

        // 4. Die Kette bis zum Bild, Sprung für Sprung — der Schritt, der den 511-Fall überhaupt sichtbar macht.
        val kanal = runCatching { quelle?.id?.let { channelDao.allForSources(listOf(it), 1).firstOrNull() } }.getOrNull()
        val stream = pruefeKette(quelle?.id, kanal?.streamUrl)
        if (stream != null) {
            schritte += stream.schritt
            stream.kette.forEach { sp ->
                schritte += Schritt(
                    KEY_SPRUNG, "$SCHRITT_SPRUNG ${sp.nr}", sp.status in 200..399,
                    sp.fehler ?: "HTTP ${sp.status}${sp.ziel?.let { " → ${hostVon(it)}" } ?: ""}",
                    zusatz = sp.nr.toString(),
                )
            }
        }

        // 5. Clock. A guide that looks two hours off is almost always this.
        val serverZeit = (answer ?: panelOk)?.serverTime.orEmpty()
        val abweichung = uhrAbweichung(serverZeit)
        if (serverZeit.isNotBlank()) {
            schritte += Schritt(KEY_UHR, SCHRITT_UHR, abs(abweichung) < 300, if (abs(abweichung) < 60) "stimmt" else "${abweichung}s daneben")
        }

        val klasse = entscheide(
            netzart = netzart,
            internetErreichbar = netz.erreichbar,
            portal = netz.portal,
            panelOk = panelOk != null,
            hosts = hostBefunde.map { it.status() },
            streamStatus = stream?.status,
            streamOk = stream?.ok,
            uhrAbweichungSek = abweichung,
            kette = stream?.kette ?: emptyList(),
        )
        Befund(klasse, schritte, abweichung, netzart, stream?.kette ?: emptyList(), zweig(stream?.endziel), stream?.mbit ?: 0.0)
            .also { Log.i(TAG, "Selbsttest: $klasse${if (it.zweig.isNotBlank()) " · ${it.zweig}" else ""}") }
    }

    // --- single steps ---------------------------------------------------------------------

    private data class NetzBefund(val schritt: Schritt, val status: Int, val portal: Boolean, val erreichbar: Boolean)

    private fun pruefeInternet(): NetzBefund {
        val request = Request.Builder().url(INTERNET_PROBE).head().header("User-Agent", "German4K-Ultra").build()
        return runCatching {
            client.newBuilder().followRedirects(false).build().newCall(request).execute().use { r ->
                val ziel = r.header("Location").orEmpty()
                // 511 is the status a captive portal is supposed to send; most send a redirect to
                // their own login page instead, which is why the target host is checked as well.
                val portal = r.code == 511 || (r.isRedirect && ziel.isNotBlank() && !ziel.contains("german4k.com", ignoreCase = true))
                NetzBefund(
                    Schritt(KEY_INTERNET, SCHRITT_INTERNET, r.isSuccessful || r.isRedirect && !portal, if (portal) "Anmeldeseite des Netzes" else "HTTP ${r.code}"),
                    r.code, portal, true,
                )
            }
        }.getOrElse {
            NetzBefund(Schritt(KEY_INTERNET, SCHRITT_INTERNET, false, kurzFehler(it)), 0, false, false)
        }
    }

    private data class HostBefund(val host: String, val ok: Boolean, val info: String, val status: Int, val auth: Boolean?, val zustand: String) {
        fun status() = HostStatus(ok, status, auth, zustand)
    }

    /** `player_api.php` with the real credentials: the same door the player knocks on. */
    private fun pruefeHost(host: String, user: String, pass: String): HostBefund {
        val url = "${host.trimEnd('/')}/player_api.php?username=${enc(user)}&password=${enc(pass)}"
        val request = Request.Builder().url(url).header("User-Agent", "German4K-Ultra").build()
        return runCatching {
            client.newCall(request).execute().use { r ->
                val body = runCatching { r.body.string() }.getOrDefault("")
                if (!r.isSuccessful) return@use HostBefund(host, false, "HTTP ${r.code}", r.code, null, "")
                val info = runCatching { JSONObject(body).optJSONObject("user_info") }.getOrNull()
                val auth = info?.optInt("auth", 0) == 1
                val zustand = info?.optString("status").orEmpty()
                HostBefund(host, auth, if (auth) "antwortet ($zustand)" else "Zugang abgelehnt ($zustand)", r.code, auth, zustand)
            }
        }.getOrElse { HostBefund(host, false, kurzFehler(it), 0, null, "") }
    }

    /**
     * Ein Sprung der Weiterleitungskette. Unser Eingang antwortet mit 302 auf den Stream-Host, der
     * wiederum auf eine Ausliefer-Kante — wo genau es bricht, ist die halbe Diagnose.
     */
    data class Sprung(val nr: Int, val status: Int, val ziel: String?, val host: String, val fehler: String? = null)

    private data class StreamBefund(
        val schritt: Schritt,
        val status: Int,
        val ok: Boolean,
        val kette: List<Sprung>,
        val endziel: String?,
        val bytes: Long,
        val mbit: Double,
    )

    /**
     * Die Kette bis zum Bild, Sprung für Sprung.
     *
     * Warum nicht einfach „lädt der Stream?": Am 19.09.2026 wurde gemessen, dass ein Teil der
     * Kunden-IPs erst am **zweiten** Sprung abgewiesen wird — die Anmeldung antwortet davor brav mit
     * `auth=1`, und genau deshalb hat jede bisherige Prüfung „alles in Ordnung" gesagt, während der
     * Kunde schwarz sah. Ein Test, der nur das Ergebnis kennt, kann diesen Fall nicht benennen.
     *
     * Dieselbe Logik wie `scripts/tv/vpn-kette.mjs`, damit ein Befund aus der App und einer von der
     * Kommandozeile vergleichbar sind.
     */
    private fun pruefeKette(sourceId: Long?, kanalUrl: String?): StreamBefund? {
        val start = kanalUrl ?: return null
        if (sourceId == null) return null
        val ohneFolgen = client.newBuilder().followRedirects(false).followSslRedirects(false).build()
        val spruenge = mutableListOf<Sprung>()
        var ziel: String? = German4kHostFailover.rewrite(start)
        var letzterStatus = 0

        for (nr in 1..MAX_SPRUENGE) {
            val url = ziel ?: break
            val request = Request.Builder().url(url).header("User-Agent", "German4K-Ultra").header("Range", "bytes=0-65535").build()
            val ergebnis = runCatching {
                ohneFolgen.newCall(request).execute().use { r ->
                    runCatching { r.body.bytes() }
                    Triple(r.code, r.header("Location"), null as String?)
                }
            }.getOrElse { Triple(0, null, kurzFehler(it)) }
            letzterStatus = ergebnis.first
            spruenge += Sprung(nr, ergebnis.first, ergebnis.second, hostVon(url), ergebnis.third)
            val weiter = ergebnis.first in 300..399 && !ergebnis.second.isNullOrBlank()
            if (!weiter) break
            ziel = ergebnis.second
        }

        // Datenrate nur, wenn das Endziel überhaupt ausliefert — sonst misst man die Zeit bis zum Nein.
        var bytes = 0L
        var mbit = 0.0
        if (letzterStatus in 200..299 && ziel != null) {
            val t0 = System.currentTimeMillis()
            runCatching {
                val r = Request.Builder().url(ziel!!).header("User-Agent", "German4K-Ultra").header("Range", "bytes=0-2000000").build()
                ohneFolgen.newCall(r).execute().use { antwort ->
                    val gelesen = antwort.body.bytes().size.toLong()
                    val sek = (System.currentTimeMillis() - t0) / 1000.0
                    bytes = gelesen
                    if (sek > 0) mbit = (gelesen * 8 / sek / 1_000_000)
                }
            }
        }

        val ok = letzterStatus in 200..299
        val info = if (ok) {
            if (bytes > 0) "HTTP $letzterStatus · ${bytes / 1000} kB (${"%.1f".format(mbit)} Mbit/s)" else "HTTP $letzterStatus"
        } else {
            val brech = spruenge.lastOrNull()
            "Sprung ${brech?.nr ?: 1}: ${brech?.fehler ?: "HTTP $letzterStatus"}"
        }
        return StreamBefund(
            Schritt(KEY_STREAM, SCHRITT_STREAM, ok, info),
            letzterStatus, ok, spruenge, ziel, bytes, mbit,
        )
    }

    /** Endkundennetz oder Rechenzentrum? Eine nackte IP als Endziel heißt VPN/Serverraum — beides normal. */
    private fun zweig(ziel: String?): String {
        val host = ziel?.let { hostVon(it) } ?: return ""
        return if (host.matches(Regex("""^[0-9.]+$"""))) ZWEIG_RZ else ZWEIG_ENDKUNDE
    }

    private fun hostVon(url: String): String = runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("")

    // --- classification -------------------------------------------------------------------

    /** One host's answer, reduced to what the verdict needs. */
    data class HostStatus(val ok: Boolean, val status: Int, val auth: Boolean?, val zustand: String)

    companion object {
        private const val TAG = "German4kHealth"
        /** Our own site: reachable, ours, and cheap to HEAD. */
        const val INTERNET_PROBE = "https://german4k.com/"

        const val KEY_NETZ = "netz"
        const val KEY_INTERNET = "internet"
        const val KEY_PANEL = "panel"
        const val KEY_HOST = "host"
        const val KEY_STREAM = "stream"
        const val KEY_SPRUNG = "sprung"
        const val KEY_UHR = "uhr"

        const val SCHRITT_NETZ = "Netzwerk"
        const val SCHRITT_INTERNET = "Internet"
        const val SCHRITT_PANEL = "German4K"
        const val SCHRITT_HOST = "Verbindung"
        const val SCHRITT_STREAM = "Sender"
        const val SCHRITT_SPRUNG = "Weg"

        const val ZWEIG_RZ = "Rechenzentrum/VPN"
        const val ZWEIG_ENDKUNDE = "Endkundennetz"
        /** Wie viele Weiterleitungen die Prüfung verfolgt. Der Kundenweg hat zwei, drei sind Reserve. */
        const val MAX_SPRUENGE = 4
        const val SCHRITT_UHR = "Uhrzeit"

        const val NETZ_WLAN = "WLAN"
        const val NETZ_KABEL = "LAN"
        const val NETZ_MOBIL = "Mobilfunk"
        const val NETZ_KEINS = "keins"

        /**
         * Same verdict from a playback failure text alone — used by the player, where no full check
         * can run before the customer sees something. Falls back to [Klasse.UNBEKANNT], which the UI
         * shows as OwnTV's own message.
         */
        fun klassifiziere(reason: String?): Klasse {
            if (reason.isNullOrBlank()) return Klasse.UNBEKANNT
            val r = reason.lowercase(Locale.ROOT)
            STATUS.find(r)?.groupValues?.get(1)?.toIntOrNull()?.let { code ->
                when (code) {
                    458, 460 -> return Klasse.LEITUNG_BELEGT
                    403 -> return Klasse.ABGELAUFEN
                    456 -> return Klasse.LAND_GESPERRT
                    511 -> return Klasse.ZWANGSPORTAL
                }
            }
            if ("connection limit" in r || "max connections" in r || "leitung" in r) return Klasse.LEITUNG_BELEGT
            if ("expired" in r) return Klasse.ABGELAUFEN
            if (German4kHostFailover.looksLikeHostFailure(reason)) return Klasse.HOST_AUSFALL
            return Klasse.UNBEKANNT
        }

        private val STATUS = Regex("""(?:http(?: error)? |response code[:= ]+|status(?: code)?[:= ]+|code[:= ]+)(\d{3})\b""")

        /**
         * The verdict, as a pure function of the measurements — kept free of Android so it can be
         * tested exhaustively (German4kHealthTest). Order matters: the cheapest, most certain causes
         * come first, and "alles gut" is only reached when nothing else fits.
         */
        fun entscheide(
            netzart: String,
            internetErreichbar: Boolean,
            portal: Boolean,
            panelOk: Boolean,
            hosts: List<HostStatus>,
            streamStatus: Int?,
            streamOk: Boolean?,
            uhrAbweichungSek: Long,
            kette: List<Sprung> = emptyList(),
        ): Klasse {
            if (netzart == NETZ_KEINS) return Klasse.KEIN_NETZ
            if (portal) return Klasse.ZWANGSPORTAL
            // Internet dead AND nothing of ours answers: the network, not us.
            if (!internetErreichbar && !panelOk && hosts.none { it.ok }) return Klasse.KEIN_NETZ
            // Internet fine, but nothing of ours answers: this router or its filter stands in the way.
            if (internetErreichbar && !panelOk && hosts.none { it.ok }) return Klasse.ROUTER_SPERRE

            hosts.firstOrNull { it.auth == false || it.status == 403 }?.let { h ->
                if (h.status == 403 || h.zustand.equals("Expired", true) ||
                    h.zustand.equals("Banned", true) || h.zustand.equals("Disabled", true)
                ) return Klasse.ABGELAUFEN
            }
            // Ein 511 IN DER KETTE ist kein Zwangsportal (das hätte schon den Internet-Test gefangen),
            // sondern die Abweisung dieser IP durch den Zulieferer — und die Anmeldung sagt trotzdem ja.
            // Deshalb steht diese Prüfung vor allen anderen Stream-Urteilen.
            if (kette.any { it.status == 511 } && (panelOk || hosts.any { it.ok })) return Klasse.VPN_FILTER
            if (hosts.any { it.status == 456 } || streamStatus == 456) return Klasse.LAND_GESPERRT
            if (streamStatus == 458 || streamStatus == 460) return Klasse.LEITUNG_BELEGT
            // Some hosts answer, some do not: the failover has it covered, the customer saw a hiccup.
            if (hosts.isNotEmpty() && hosts.any { it.ok } && hosts.any { !it.ok }) return Klasse.HOST_AUSFALL
            // Everything of ours answers and still nothing plays — almost always the ISP during a match.
            if (panelOk && hosts.isNotEmpty() && hosts.all { it.ok } && streamOk == false) return Klasse.ANBIETER_SPERRE
            if (abs(uhrAbweichungSek) >= 300) return Klasse.UHRZEIT
            if (panelOk && (hosts.isEmpty() || hosts.any { it.ok }) && (streamOk == null || streamOk)) return Klasse.ALLES_GUT
            return Klasse.UNBEKANNT
        }
    }

    // --- helpers --------------------------------------------------------------------------

    private fun netzart(): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return NETZ_KEINS
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return NETZ_KEINS
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NETZ_KABEL
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NETZ_WLAN
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NETZ_MOBIL
            else -> NETZ_KEINS
        }
    }

    /** Device clock minus panel clock, in seconds. 0 when the panel said nothing. */
    private fun uhrAbweichung(serverTime: String): Long {
        if (serverTime.isBlank()) return 0
        val server = runCatching { java.time.Instant.parse(serverTime).toEpochMilli() }.getOrNull() ?: return 0
        return (System.currentTimeMillis() - server) / 1000
    }

    /** Whether this host has an IPv6 address at all — context for "works on the phone, not on the TV". */
    fun hatIpv6(host: String): Boolean = runCatching {
        InetAddress.getAllByName(host).any { it !is java.net.Inet4Address }
    }.getOrDefault(false)

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
    private fun kurz(host: String) = host.removePrefix("https://").removePrefix("http://").trimEnd('/')
    private fun kurzFehler(t: Throwable) = (t.message ?: t.javaClass.simpleName).take(80)
}
