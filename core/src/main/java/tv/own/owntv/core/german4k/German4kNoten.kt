package tv.own.owntv.core.german4k

/**
 * Bewertungen eines Films oder einer Serie, je Quelle.
 *
 * Kundenwunsch vom 21.09.2026: die IMDb-Note wie in UHF — auf dem Titelbild und in der
 * Detailansicht, dort zusammen mit den anderen Seiten. Unser Server holt sie nachts bei MDBList
 * (german4k-website, scripts/tv/noten-holen.mjs) und liefert sie in `get_vod_info` /
 * `get_series_info` als zusätzliche Felder im `info`-Block: `imdb_rating`, `imdb_votes`,
 * `tmdb_rating`, `rt_rating`, `metacritic`, `letterboxd`, `trakt`. Fremde Server kennen die Felder
 * nicht — dann bleibt alles null und die Zeile erscheint nicht.
 */
data class German4kNoten(
    val imdb: Double? = null,
    val imdbStimmen: Int? = null,
    val tmdb: Double? = null,
    /** Rotten Tomatoes, Kritiker, in Prozent. */
    val rt: Int? = null,
    val metacritic: Int? = null,
    /** Letterboxd zählt bis fünf. */
    val letterboxd: Double? = null,
    /** Trakt, in Prozent. */
    val trakt: Int? = null,
) {
    val leer: Boolean
        get() = imdb == null && tmdb == null && rt == null && metacritic == null && letterboxd == null && trakt == null
}
