package tv.own.owntv.core.german4k

import tv.own.owntv.core.content.SearchResults
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity

/**
 * German4K 3.2.1 (U45, Schalter `suche_buendeln`): Suchtreffer wie das Filme-/Serien-Raster bündeln. Gleiches Werk
 * (Titel ohne Anbietervorsatz + Jahr, [German4kFassungsGruppen]) steht nur einmal in der Liste; die Zeile nennt
 * „N Fassungen" ([anzahlFilme]/[anzahlSerien]) und OK öffnet die bevorzugte Fassung (deutsch, dann 4K, dann die erste).
 * Vorher stand „4K - Doctor Strange (2016)" fünfmal untereinander, ohne Unterschied.
 */
class German4kSucheBuendel private constructor(
    val movies: List<MovieEntity>,
    val series: List<SeriesEntity>,
    val anzahlFilme: Map<Long, Int>,
    val anzahlSerien: Map<Long, Int>,
    private val filmWahl: Map<Long, MovieEntity>,
    private val serienWahl: Map<Long, SeriesEntity>,
) {
    /** Die Fassung, die ein Klick auf den Vertreter [vertreter] öffnet. */
    fun film(vertreter: MovieEntity): MovieEntity = filmWahl[vertreter.id] ?: vertreter
    fun serie(vertreter: SeriesEntity): SeriesEntity = serienWahl[vertreter.id] ?: vertreter

    companion object {
        fun aus(r: SearchResults): German4kSucheBuendel {
            val gf = German4kFassungsGruppen()
            val filme = r.movies.filter { gf.annehmen(it.id, it.name, it.year ?: it.parsedYear) }
            val nachIdF = r.movies.associateBy { it.id }
            val gs = German4kFassungsGruppen()
            val serien = r.series.filter { gs.annehmen(it.id, it.name, it.year ?: it.parsedYear) }
            val nachIdS = r.series.associateBy { it.id }
            return German4kSucheBuendel(
                movies = filme,
                series = serien,
                anzahlFilme = gf.anzahl.value,
                anzahlSerien = gs.anzahl.value,
                filmWahl = filme.associate { it.id to (nachIdF[gf.bevorzugt(it.id)] ?: it) },
                serienWahl = serien.associate { it.id to (nachIdS[gs.bevorzugt(it.id)] ?: it) },
            )
        }
    }
}
