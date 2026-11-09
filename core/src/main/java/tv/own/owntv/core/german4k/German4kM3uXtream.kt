package tv.own.owntv.core.german4k

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/**
 * German4K 3.3 (Schalter `m3u_als_xtream`, Kunde 02.11.2026): Ein Kunde trägt in der Ultra seinen M3U-Link ein
 * (`https://german4k.tv/get.php?username=…&password=…&type=m3u_plus&output=ts[&kind=live]`). Als M3U holt die App nur
 * `get.php` — Filme, Serien und die Xtream-Programmvorschau fehlen dann ohne jeden Hinweis. Steht im Link einer
 * unserer Hosts und stecken Benutzer und Passwort darin, legen wir stattdessen einen Xtream-Zugang an (Server =
 * `scheme://host[:port]` aus dem Link), genau wie TiviMate/Smarters es mit derselben Adresse tun (lib/tv/links.ts).
 *
 * „Unser Host“ (abgeleitet aus der Server-Konfiguration und den Panel-Adressen, 02.11.2026): german4k.tv mit allen
 * Unterdomains (www, tv, apps, cdn2 …), german4k.xyz mit allen Unterdomains (cf = alte Panel-Adresse, apps =
 * Sondereingang), german4k.com/www (dieselbe App beantwortet dort get.php und player_api.php) — plus jeder Host,
 * den das Panel als Quelle/Ausweich gemeldet hat ([German4kHostFailover.knows]). Nachahmer wie german4k.net oder
 * germany4k.com sind NICHT unser und bleiben M3U. Fremde Links bleiben unverändert M3U.
 */
object German4kM3uXtream {

    /** Was aus dem Link wird. [server] ohne Pfad und ohne Schrägstrich am Ende. */
    data class Zugang(val server: String, val username: String, val password: String) {
        override fun toString(): String = "Zugang(server=$server, username=$username)" // Passwort nie ins Log
    }

    private val DOMAINS = listOf("german4k.tv", "german4k.xyz")
    private val EXAKT = setOf("german4k.com", "www.german4k.com")

    /** Schalter an (fehlt = an) und Link umwandelbar → Zugang, sonst null (= wie bisher als M3U anlegen). */
    fun alsXtream(url: String?, bekannt: (String) -> Boolean = ::panelHost): Zugang? =
        if (German4kFeatures.an(German4kSchalter.M3U_ALS_XTREAM)) umwandeln(url, bekannt) else null

    /**
     * Rein (unit-getestet): Benutzer und Passwort aus den Query-Parametern `username`/`password` eines http(s)-Links
     * mit unserem Host. Fehlt eines davon, ist der Host fremd oder der Link kein http(s) → null.
     */
    fun umwandeln(url: String?, bekannt: (String) -> Boolean = ::panelHost): Zugang? {
        val text = url?.trim().orEmpty()
        if (text.isEmpty()) return null
        val uri = runCatching { URI(text) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase(Locale.ROOT)?.trimEnd('.') ?: return null
        val server = scheme + "://" + host + (if (uri.port > 0) ":" + uri.port else "")
        if (!unserHost(host) && !runCatching { bekannt(server) }.getOrDefault(false)) return null
        val parameter = abfrage(uri.rawQuery)
        val username = parameter["username"]?.trim().orEmpty()
        // Passwort NICHT trimmen: Leerzeichen können dazugehören (wie im Xtream-Formular).
        val password = parameter["password"].orEmpty()
        if (username.isEmpty() || password.isEmpty()) return null
        return Zugang(server, username, password)
    }

    /** german4k.tv / german4k.xyz samt Unterdomains, german4k.com samt www — echte Endung, kein „enthält“. */
    fun unserHost(host: String): Boolean {
        val h = host.lowercase(Locale.ROOT).trimEnd('.')
        return h in EXAKT || DOMAINS.any { d -> h == d || h.endsWith(".$d") }
    }

    private fun panelHost(origin: String): Boolean = German4kHostFailover.knows(origin)

    /** Query-Parameter, erster Wert gewinnt; `+` und `%XX` wie ein Formular (so kodieren URLSearchParams/encodeURIComponent). */
    private fun abfrage(raw: String?): Map<String, String> {
        if (raw.isNullOrEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (teil in raw.split('&')) {
            if (teil.isEmpty()) continue
            val i = teil.indexOf('=')
            val name = dekodieren(if (i < 0) teil else teil.substring(0, i)).lowercase(Locale.ROOT)
            val wert = if (i < 0) "" else dekodieren(teil.substring(i + 1))
            if (name !in out) out[name] = wert
        }
        return out
    }

    private fun dekodieren(s: String): String = runCatching { URLDecoder.decode(s, Charsets.UTF_8.name()) }.getOrDefault(s)
}
