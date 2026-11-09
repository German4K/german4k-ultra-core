package tv.own.owntv.core.german4k

import tv.own.owntv.core.database.entity.SourceEntity

/** German4K 3.2.1 (A3): Ist eine Wiedergabeliste unsere eigene (german4k.tv & Co. oder ein registrierter Host)? */
object German4kQuelle {
    fun ist(source: SourceEntity?): Boolean {
        val url = source?.url?.trim().orEmpty()
        if (url.isEmpty()) return false
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return false
        return host.contains(MARKE) || runCatching { German4kHostFailover.knows(url) }.getOrDefault(false)
    }

    /**
     * Aufnahmen auf dieser Quelle teilen sich mit dem Zuschauen eine einzige Leitung: German4K, der Server meldet
     * `max_connections` 0, Schalter `aufnahmen` an (aus = Verhalten 3.2, alles erlaubt).
     */
    fun einzelLeitungFuerAufnahmen(source: SourceEntity?): Boolean =
        source != null && source.maxConnections <= 0 && ist(source) && German4kFeatures.an(German4kSchalter.AUFNAHMEN)

    private const val MARKE = "german4k"
}
