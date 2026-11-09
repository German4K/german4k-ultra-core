package tv.own.owntv.core.german4k

import org.json.JSONObject
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.dao.SourceDao
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.parser.XtreamClient

/** Die Antwort von get_person_titles: nur Name und Kennungen — die Zeilen selbst hat die App schon. */
data class German4kPersonAntwort(val name: String, val filmIds: List<String>, val serienIds: List<String>) {
    companion object {
        fun parse(json: String): German4kPersonAntwort? {
            val o = runCatching { JSONObject(json) }.getOrNull() ?: return null
            fun ids(feld: String, schluessel: String): List<String> {
                val a = o.optJSONArray(feld) ?: return emptyList()
                return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optLong(schluessel)?.takeIf { id -> id > 0 }?.toString() }
            }
            return German4kPersonAntwort(o.optString("name"), ids("movies", "stream_id"), ids("series", "series_id"))
        }
    }
}

data class German4kPersonTitel(val name: String, val filme: List<MovieEntity>, val serien: List<SeriesEntity>)

/**
 * „Weitere Titel mit …": fragt unseren Server nach den Kennungen und holt dazu die lokalen
 * Zeilen — so hat jede Kachel dieselbe MovieEntity wie im Raster (Favorit, Fortschritt,
 * Abspielen funktionieren ohne Sonderweg). Reihenfolge wie vom Server (alphabetisch).
 */
class German4kPersonRepository(
    private val xtream: XtreamClient,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val sourceDao: SourceDao,
) {
    suspend fun laden(sourceId: Long, personId: Long): German4kPersonTitel? {
        val source = sourceDao.getById(sourceId) ?: return null
        val antwort = xtream.getPersonTitles(source, personId) ?: return null
        val filme = movieDao.findByRemoteIds(sourceId, antwort.filmIds).associateBy { it.remoteId }
        val serien = seriesDao.findSeriesByRemoteIds(sourceId, antwort.serienIds).associateBy { it.remoteId }
        return German4kPersonTitel(
            name = antwort.name,
            filme = antwort.filmIds.mapNotNull { filme[it] },
            serien = antwort.serienIds.mapNotNull { serien[it] },
        )
    }
}
