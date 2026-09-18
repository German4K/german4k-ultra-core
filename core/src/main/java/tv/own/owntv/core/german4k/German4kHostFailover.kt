package tv.own.owntv.core.german4k

import android.os.SystemClock
import android.util.Log
import java.util.Locale

/**
 * Multi-DNS, stage 2: one German4K playlist, several hosts. The panel hands out a primary host and
 * alternatives that serve the same line (`https://german4k.tv` first, `http://tv.german4k.tv` as the
 * fallback). Every URL the app builds — API calls, guide, stream URLs baked into the catalogue — is
 * passed through [rewrite], which swaps the origin for the host currently preferred; a connection-level
 * failure calls [demote], which pushes that host to the back for [COOLDOWN_MS] so the next request,
 * tune or retry lands on the alternative without the customer doing anything.
 *
 * A process-wide object on purpose: player-core, the HTTP layer and the view models all need the same
 * answer, and the state is small (a handful of hosts and timestamps). [register] is called by the
 * provisioner on every start, first from the cached panel answer, then from the live one.
 */
object German4kHostFailover {

    /** One line's hosts: origins without trailing slash, e.g. `https://german4k.tv`. */
    private class Group(val hosts: List<String>) {
        /** Host → time it last failed (elapsedRealtime), missing = healthy. */
        val failedAt = HashMap<String, Long>()
    }

    @Volatile private var groups: List<Group> = emptyList()

    /** Hosts demoted within this window stay at the back; afterwards the primary is tried again. */
    const val COOLDOWN_MS = 10 * 60_000L

    private const val TAG = "German4kHostFailover"

    /** Replace all groups. Origins are normalised; groups with fewer than two hosts are dropped. */
    fun register(groupsIn: List<List<String>>) {
        val old = groups
        groups = groupsIn.mapNotNull { hosts ->
            val clean = hosts.mapNotNull { originOf(it) }.distinct()
            if (clean.size < 2) null else Group(clean).also { g ->
                // Keep the failure memory of hosts we already knew.
                old.forEach { o -> o.failedAt.forEach { (h, t) -> if (h in clean) g.failedAt[h] = t } }
            }
        }
    }

    fun clear() { groups = emptyList(); synchronized(ereignisse) { ereignisse.clear() } }

    // --- Ereignis-Ring für Diagnosen -------------------------------------------------------
    //
    // Ein Host-Wechsel ist die halbe Antwort auf "das Bild war kurz weg": er sagt, dass der erste
    // Host nicht erreichbar war, und wann. Der Ring hält die letzten Wechsel im Speicher — er ist
    // Beiwerk eines Berichts, nichts, wofür eine Datei angelegt würde.
    private val ereignisse = ArrayDeque<String>()
    private const val RING = 20

    /** The last host switches / demotions, oldest first. Empty when nothing happened this run. */
    fun recentEvents(): List<String> = synchronized(ereignisse) { ereignisse.toList() }

    private fun note(text: String) {
        synchronized(ereignisse) {
            ereignisse.addLast("${java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date())} $text")
            while (ereignisse.size > RING) ereignisse.removeFirst()
        }
    }

    /** Whether [url] belongs to a registered host group. */
    fun knows(url: String): Boolean = groupFor(url) != null

    /** All hosts of [url]'s group, preferred first; empty when the URL is not ours. */
    fun hostsFor(url: String, now: Long = SystemClock.elapsedRealtime()): List<String> {
        val g = groupFor(url) ?: return emptyList()
        return ordered(g, now)
    }

    /** [url] on the preferred host of its group; unchanged when the URL is not ours. */
    fun rewrite(url: String, now: Long = SystemClock.elapsedRealtime()): String {
        val g = groupFor(url) ?: return url
        val current = originOf(url) ?: return url
        val preferred = ordered(g, now).first()
        return if (preferred.equals(current, ignoreCase = true)) url else swapOrigin(url, current, preferred)
    }

    /**
     * Mark the host of [url] as failed. Returns true when another host of the group is available *now*
     * (i.e. a retry on [rewrite] of the same URL will go somewhere else), false when the URL is not ours
     * or every other host failed within the cooldown too.
     */
    fun demote(url: String, reason: String, now: Long = SystemClock.elapsedRealtime()): Boolean {
        val g = groupFor(url) ?: return false
        val host = originOf(url) ?: return false
        synchronized(g) { g.failedAt[host] = now }
        val after = ordered(g, now).first()
        // A switch is only worth a retry when the new head is HEALTHY. With every host down, rewrite()
        // still picks the least recently failed one, but callers must not loop between dead hosts.
        val healthy = synchronized(g) { g.failedAt[after]?.let { now - it >= COOLDOWN_MS } ?: true }
        val switched = healthy && !after.equals(host, ignoreCase = true)
        Log.w(TAG, "demote $host ($reason) -> ${if (switched) "now $after" else "no healthy alternative"}")
        note("$host abgestuft ($reason) → ${if (switched) after else "kein gesunder Ausweich-Host"}")
        return switched
    }

    /**
     * Whether a playback/HTTP failure text looks like the *host* is the problem (unreachable, timing out,
     * overloaded) rather than the stream or the account. HTTP 4xx (403 forbidden, 404 gone, 458 busy)
     * is the panel answering — switching hosts would not change the answer.
     */
    fun looksLikeHostFailure(reason: String?): Boolean {
        if (reason.isNullOrBlank()) return false
        val r = reason.lowercase(Locale.ROOT)
        HTTP_STATUS.find(r)?.groupValues?.get(1)?.toIntOrNull()?.let { code ->
            return code in 500..599 || code == 429
        }
        return HOST_FAILURE_WORDS.any { it in r }
    }

    private val HTTP_STATUS = Regex("""(?:http(?: error)? |response code[:= ]+|status(?: code)?[:= ]+|http )(\d{3})\b""")
    private val HOST_FAILURE_WORDS = listOf(
        "timed out", "timeout", "connection refused", "connection reset", "unreachable", "unknownhost", "unknown host",
        "failed to connect", "unexpected end of stream", "no route to host", "network is unreachable", "software caused connection abort",
        "connection closed", "econnrefused", "econnreset", "ehostunreach", "enetunreach", "gave up after", "did not open",
        "lost connection", "socket closed", "ssl handshake", "handshake failed", "eof",
    )

    // --- helpers ---

    private fun groupFor(url: String): Group? {
        val origin = originOf(url) ?: return null
        return groups.firstOrNull { g -> g.hosts.any { it.equals(origin, ignoreCase = true) } }
    }

    private fun ordered(g: Group, now: Long): List<String> {
        val failed = synchronized(g) { HashMap(g.failedAt) }
        // Healthy hosts in panel order first, then demoted ones (oldest failure first).
        return g.hosts.sortedWith(
            compareBy<String>({ h -> failed[h]?.let { if (now - it < COOLDOWN_MS) 1 else 0 } ?: 0 }, { h -> failed[h]?.takeIf { now - it < COOLDOWN_MS } ?: 0L }),
        )
    }

    /** `scheme://host[:port]` of [url] in lower case, or null when it is not an http(s) URL. */
    fun originOf(url: String): String? {
        val m = ORIGIN.find(url.trim()) ?: return null
        return m.value.trimEnd('/').lowercase(Locale.ROOT)
    }

    private val ORIGIN = Regex("""^https?://[^/?#]+""", RegexOption.IGNORE_CASE)

    private fun swapOrigin(url: String, from: String, to: String): String {
        val t = url.trim()
        return if (t.length >= from.length && t.substring(0, from.length).equals(from, ignoreCase = true)) to + t.substring(from.length) else url
    }
}
