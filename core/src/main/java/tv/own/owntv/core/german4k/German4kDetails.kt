package tv.own.owntv.core.german4k

import org.json.JSONArray
import org.json.JSONObject

/**
 * Was die Detailseite braucht — aus EINEM Aufruf von get_vod_info bzw. get_series_info gegen
 * unseren Server (german4k.tv). Die Zusatzfelder cast_list / versions / *_rating liefert nur
 * unser Server; bei einem fremden Xtream-Server bleiben sie leer, die Seite zeigt dann Plakat,
 * Handlung und die Standardnote.
 *
 * Bewusst org.json statt android.util.JsonReader: die Antwort ist klein (ein Titel), und so
 * laeuft der Parser als reiner JVM-Test — JsonReader gibt in Unit-Tests nur Nullen zurueck.
 */
/** `regie` = Regisseur (Serien: Schoepfer); der Server liefert Regie zuerst. */
data class German4kDarsteller(val id: Long, val name: String, val rolle: String, val foto: String?, val regie: Boolean)

data class German4kFassung(val remoteId: String, val name: String, val label: String, val kategorie: String, val serie: Boolean)

data class German4kDetails(
    val backdrop: String?,
    val plot: String,
    val genre: String,
    val jahr: String,
    /** Volles Datum "JJJJ-MM-TT" oder "" — die Seite zeigt es in der Schreibweise des Geraets. */
    val datum: String,
    val dauerSek: Int,
    /** YouTube-Kennung des Trailers, null wenn keiner bekannt ist. */
    val trailer: String?,
    val noten: German4kNoten?,
    val besetzung: List<German4kDarsteller>,
    val fassungen: List<German4kFassung>,
) {
    companion object {
        fun parse(json: String): German4kDetails? {
            val info = runCatching { JSONObject(json).optJSONObject("info") }.getOrNull() ?: return null
            val backdrop = info.optJSONArray("backdrop_path")?.let { a -> (0 until a.length()).map { a.optString(it) }.firstOrNull { it.startsWith("http") } }
            val datum = listOf("releasedate", "releaseDate", "release_date").map { info.optString(it) }.firstOrNull { it.isNotBlank() } ?: ""
            val dauer = info.optInt("duration_secs", 0).takeIf { it > 0 }
                ?: info.optString("episode_run_time").toIntOrNull()?.times(60) ?: 0
            val noten = German4kNoten(
                imdb = info.optDouble("imdb_rating").takeIf { it > 0 },
                imdbStimmen = info.optInt("imdb_votes").takeIf { it > 0 },
                tmdb = info.optDouble("tmdb_rating").takeIf { it > 0 },
                rt = info.optInt("rt_rating").takeIf { it > 0 },
                metacritic = info.optInt("metacritic").takeIf { it > 0 },
                letterboxd = info.optDouble("letterboxd").takeIf { it > 0 },
                trakt = info.optInt("trakt").takeIf { it > 0 },
            ).takeUnless { it.leer }
            return German4kDetails(
                backdrop = backdrop,
                plot = info.optString("plot"),
                genre = info.optString("genre"),
                jahr = datum.take(4).takeIf { it.length == 4 && it.all(Char::isDigit) } ?: "",
                datum = datum.takeIf { Regex("\\d{4}-\\d{2}-\\d{2}").matches(it) } ?: "",
                dauerSek = dauer,
                trailer = info.optString("youtube_trailer").takeIf { Regex("[A-Za-z0-9_-]{6,}").matches(it) },
                noten = noten,
                besetzung = info.optJSONArray("cast_list").objekte().mapNotNull { o ->
                    val id = o.optLong("id"); val name = o.optString("name")
                    if (id <= 0 || name.isBlank()) null
                    else German4kDarsteller(id, name, o.optString("role"), o.optString("photo").takeIf { it.startsWith("http") }, regie = o.optString("job") == "director")
                },
                fassungen = info.optJSONArray("versions").objekte().mapNotNull { o ->
                    val sid = o.optLong("stream_id"); if (sid <= 0) null
                    else German4kFassung(sid.toString(), o.optString("name"), o.optString("label"), o.optString("category"), o.optString("kind") == "series")
                },
            )
        }

        private fun JSONArray?.objekte(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
    }
}
