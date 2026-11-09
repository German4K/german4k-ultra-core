package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.content.SearchResults
import tv.own.owntv.core.database.entity.MovieEntity

class German4kSucheBuendelTest {
    private fun film(id: Long, name: String, jahr: Int? = null) = MovieEntity(id = id, sourceId = 1, name = name, streamUrl = "x", year = jahr)

    @Test fun gleicheWerkeEinmalMitAnzahl() {
        val r = SearchResults(movies = listOf(
            film(1, "4K - Doctor Strange (2016)"), film(2, "4K - Doctor Strange (2016)"), film(3, "DE - Doctor Strange (2016)"),
            film(4, "4K - Doctor Strange in the Multiverse of Madness (2022)"), film(5, "Doctor Strange (1978)"),
        ))
        val b = German4kSucheBuendel.aus(r)
        assertEquals(listOf(1L, 4L, 5L), b.movies.map { it.id })
        assertEquals(3, b.anzahlFilme[1L])
        assertEquals(null, b.anzahlFilme[4L])
        // Klick öffnet die deutsche Fassung
        assertEquals(3L, b.film(b.movies.first()).id)
    }

    @Test fun ohneDublettenUnveraendert() {
        val r = SearchResults(movies = listOf(film(1, "DE - A (2001)"), film(2, "DE - B (2001)")))
        val b = German4kSucheBuendel.aus(r)
        assertEquals(listOf(1L, 2L), b.movies.map { it.id })
        assertEquals(emptyMap<Long, Int>(), b.anzahlFilme)
    }
}
