package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tv.own.owntv.core.settings.SettingsRepository.SortMode
import java.io.File

/** German4K 3.2.1 (A4): jede Sortierung hat im Favoriten-Ordner ihre eigene Abfrage. */
class German4kFavoritenSortierungTest {

    @Test fun `jede Sortierung waehlt eine eigene Abfrage`() {
        for (anbieter in listOf(true, false)) {
            val abfragen = SortMode.values().map { German4kFavoritenSortierung.abfrage(it, anbieter) }
            assertEquals(SortMode.values().size, abfragen.toSet().size)
        }
        assertEquals(FavoritenAbfrage.EIGENE, German4kFavoritenSortierung.abfrage(SortMode.PLAYLIST, false))
        assertEquals(FavoritenAbfrage.A_Z, German4kFavoritenSortierung.abfrage(SortMode.ALPHA, true))
    }

    // German4K 3.2.1 (U52): „Anbieter" in den Favoriten war „zuletzt favorisiert" (MP4 vor MKV).
    @Test fun `Anbieter in den Favoriten ist die Anbieter-Reihenfolge, Schalter aus = eigene Reihenfolge`() {
        assertEquals(FavoritenAbfrage.ANBIETER, German4kFavoritenSortierung.abfrage(SortMode.PLAYLIST, true))
        assertEquals(FavoritenAbfrage.EIGENE, German4kFavoritenSortierung.abfrage(SortMode.PLAYLIST, false))
    }

    @Test fun `Anbieter-Abfrage - eigene Reihenfolge vorn, dann Quelle und Playlist-Position, nie Favorisierzeit`() {
        for ((datei, a) in listOf("MovieDao.kt" to "m", "SeriesDao.kt" to "s")) {
            val o = orderBy(File(dao, datei).readText(), "pagingFavoritesProvider")
            assertEquals(
                "$datei: $o",
                "(CASE WHEN o.position IS NULL THEN 1 ELSE 0 END), o.position, $a.sourceId ASC, $a.sortOrder ASC, $a.name ASC, $a.id ASC",
                o,
            )
            assertFalse(o.contains("f.addedAt"))
        }
    }

    private val dao = File("src/main/java/tv/own/owntv/core/database/dao")

    private fun orderBy(src: String, fn: String): String {
        val i = src.indexOf("fun $fn(")
        assertTrue("$fn fehlt", i > 0)
        val query = src.substring(src.lastIndexOf("@Query(", i), i)
        return Regex("ORDER BY ([^\"]*)\"").find(query)!!.groupValues[1]
    }

    @Test fun `Favoriten-Abfragen in Film- und Serien-DAO sortieren verschieden und SQLite-3_19-tauglich`() {
        for ((datei, a) in listOf("MovieDao.kt" to "m", "SeriesDao.kt" to "s")) {
            val src = File(dao, datei).readText()
            val order = mapOf(
                "pagingFavoritesManual" to orderBy(src, "pagingFavoritesManual"),
                "pagingFavoritesProvider" to orderBy(src, "pagingFavoritesProvider"),
                "pagingFavoritesAlpha" to orderBy(src, "pagingFavoritesAlpha"),
                "pagingFavoritesRating" to orderBy(src, "pagingFavoritesRating"),
                "pagingFavoritesDateAdded" to orderBy(src, "pagingFavoritesDateAdded"),
                "pagingFavoritesYear" to orderBy(src, "pagingFavoritesYear"),
            )
            assertEquals("$datei: Sortierungen nicht verschieden", order.size, order.values.toSet().size)
            assertTrue(order.getValue("pagingFavoritesAlpha").startsWith("$a.name"))
            assertTrue(order.getValue("pagingFavoritesRating").contains("$a.rating DESC"))
            assertTrue(order.getValue("pagingFavoritesDateAdded").contains("$a.addedAt DESC"))
            assertTrue(order.getValue("pagingFavoritesYear").contains("COALESCE($a.year, $a.parsedYear) DESC"))
            order.values.forEach { o ->
                // Android 9 = SQLite 3.19: kein NULLS FIRST/LAST (3.30), keine Fensterfunktionen (3.25).
                assertFalse("$datei: $o", Regex("(?i)NULLS (FIRST|LAST)| OVER ?\\(").containsMatchIn(o))
            }
        }
    }
}
