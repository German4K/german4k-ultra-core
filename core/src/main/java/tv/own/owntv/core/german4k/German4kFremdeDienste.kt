package tv.own.owntv.core.german4k

/**
 * German4K 3.2.1 (Q1): Die Ultra stammt von OwnTV ab und fragte dessen Dienste eines Dritten
 * (TMDB- und OpenSubtitles-Worker auf workers.dev/owntv.me, GitHub-Releases). Für German4K sind die
 * Daten über unseren Server da (get_vod_info, Angesagt, Panel-Update) — die fremden Wege bleiben zu.
 * Schalter [German4kSchalter.FREMDE_DIENSTE_AUS]: fehlt = an (zu), "aus" = altes Verhalten.
 */
object German4kFremdeDienste {
    fun zu(): Boolean = German4kFeatures.an(German4kSchalter.FREMDE_DIENSTE_AUS)

    /** TMDB-Standardweg (fremder Worker) — eigener Schlüssel/eigener Server bleiben immer erlaubt. */
    fun tmdbStandardWegErlaubt(): Boolean = !zu()

    /** OpenSubtitles nur mit eigenem Zugang des Kunden (eigener API-Schlüssel oder eigener Server). */
    fun openSubtitlesErlaubt(eigenerServer: String, eigenerSchluessel: String): Boolean =
        !zu() || eigenerServer.isNotBlank() || eigenerSchluessel.isNotBlank()

    /** GitHub-Releases als Update-Quelle — bei German4K ist das Panel die Quelle. */
    fun githubUpdateErlaubt(): Boolean = !zu()
}

/**
 * German4K 3.2.1 (Q1): Bremse je Host gegen Wiederholungsschleifen. 401/403 = Zugang verweigert, kommt
 * nicht von allein wieder → Host [sperreAbgelehntMs] lang still. 404 = nur diese Adresse gibt es nicht →
 * die Adresse [sperre404Ms] lang nicht neu fragen. 429/5xx = Host mit wachsender Pause (1, 2, 4 … max. 30 min).
 * Rein (Uhr von außen), damit sie ohne Android testbar ist.
 */
class German4kHostBremse(
    private val uhr: () -> Long = { System.currentTimeMillis() },
    private val sperreAbgelehntMs: Long = 30 * 60_000L,
    private val sperre404Ms: Long = 10 * 60_000L,
    private val basisPauseMs: Long = 60_000L,
    private val maxPauseMs: Long = 30 * 60_000L,
) {
    private val hostBis = HashMap<String, Long>()
    private val hostFehler = HashMap<String, Int>()
    private val adresseBis = object : LinkedHashMap<String, Long>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Long>?) = size > 256
    }

    @Synchronized fun darf(url: String): Boolean {
        val jetzt = uhr()
        hostBis[host(url)]?.let { if (jetzt < it) return false }
        adresseBis[url]?.let { if (jetzt < it) return false }
        return true
    }

    @Synchronized fun erfolg(url: String) {
        val h = host(url)
        hostFehler.remove(h); hostBis.remove(h)
    }

    @Synchronized fun fehler(url: String, code: Int) {
        val jetzt = uhr()
        val h = host(url)
        when {
            code == 401 || code == 403 -> hostBis[h] = jetzt + sperreAbgelehntMs
            code == 404 || code == 410 -> adresseBis[url] = jetzt + sperre404Ms
            code == 429 || code >= 500 -> {
                val n = (hostFehler[h] ?: 0) + 1
                hostFehler[h] = n
                val pause = (basisPauseMs shl (n - 1).coerceAtMost(10)).coerceAtMost(maxPauseMs)
                hostBis[h] = jetzt + pause
            }
        }
    }

    companion object {
        fun host(url: String): String =
            url.substringAfter("://", url).substringBefore('/').substringBefore('?').lowercase()
    }
}
