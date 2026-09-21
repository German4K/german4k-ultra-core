package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class German4kDetailsParseTest {
    private val film = """{"info":{"name":"DE - Fight Club (1999)","plot":"Ein Mann.","genre":"Drama","releasedate":"1999-10-15",
      "duration_secs":8340,"backdrop_path":["https://image.tmdb.org/t/p/w1280/x.jpg"],"youtube_trailer":"SUXWAEX2jlg",
      "rating":"8.8","imdb_rating":"8.8","imdb_votes":2700000,"tmdb_rating":"8.4","rt_rating":81,"metacritic":67,"letterboxd":"4.3","trakt":87,
      "cast_list":[{"id":7467,"name":"David Fincher","role":"","photo":"https://image.tmdb.org/t/p/w185/f.jpg","job":"director"},{"id":819,"name":"Edward Norton","role":"The Narrator","photo":"","job":"actor"},{"id":287,"name":"Brad Pitt","role":"Tyler Durden","photo":"https://image.tmdb.org/t/p/w185/a.jpg","job":"actor"}],
      "versions":[{"stream_id":100000067,"name":"4K-DE-DV - Fight Club","label":"DE · 4K DV","category":"DE 4K","kind":"movie"}]},
      "movie_data":{"stream_id":100000032}}"""

    @Test fun liestAlleFelder() {
        val d = German4kDetails.parse(film)!!
        assertEquals("https://image.tmdb.org/t/p/w1280/x.jpg", d.backdrop)
        assertEquals("Ein Mann.", d.plot); assertEquals("Drama", d.genre); assertEquals("1999", d.jahr); assertEquals("1999-10-15", d.datum); assertEquals(8340, d.dauerSek)
        assertEquals("SUXWAEX2jlg", d.trailer)
        assertEquals(8.8, d.noten!!.imdb!!, 0.001); assertEquals(2700000, d.noten.imdbStimmen)
        assertEquals(listOf(7467L, 819L, 287L), d.besetzung.map { it.id })
        assertEquals(listOf(true, false, false), d.besetzung.map { it.regie })
        assertNull(d.besetzung[1].foto)
        assertEquals("100000067", d.fassungen.single().remoteId); assertEquals("DE · 4K DV", d.fassungen.single().label)
    }

    @Test fun serieOhneZusatzfelder() {
        val d = German4kDetails.parse("""{"info":{"name":"x","plot":"","episode_run_time":"45","releaseDate":"2011-04-17","backdrop_path":[]},"episodes":{}}""")!!
        assertEquals(45 * 60, d.dauerSek); assertEquals("2011", d.jahr); assertEquals("2011-04-17", d.datum); assertNull(d.backdrop); assertNull(d.noten); assertNull(d.trailer)
        assertEquals(0, d.besetzung.size); assertEquals(0, d.fassungen.size)
    }

    @Test fun kaputtesJsonIstNull() {
        assertNull(German4kDetails.parse("{")); assertNull(German4kDetails.parse("[]")); assertNull(German4kDetails.parse("""{"user_info":{}}"""))
    }
}
