package tv.own.owntv.core.database

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * German4K 3.1.1: Jede Sortierung der Film-, Serien- und Eigene-Kategorien-Listen endet auf der id.
 *
 * Ohne eindeutiges Ende darf SQLite gleichrangige Zeilen (gleicher Name, gleiche Position) bei jeder
 * Abfrage anders ordnen. Paging lädt Seite für Seite mit OFFSET nach, eine Zeile kam so in zwei Seiten
 * vor, und LazyGrid stürzte mit „Key … was already used" ab (Fire TV Android 9, waipu-Box Android 14).
 * Room-Annotationen sind zur Laufzeit nicht lesbar, deshalb prüft der Test die Quelltexte.
 */
class OrderByTiebreakTest {
    private val dir = File("src/main/java/tv/own/owntv/core/database/dao")

    @Test fun `every content ORDER BY ends on the id`() {
        val offenders = mutableListOf<String>()
        for (name in listOf("MovieDao.kt", "SeriesDao.kt", "CustomCategoryDao.kt")) {
            val src = File(dir, name).readText()
            Regex("ORDER BY ([^\"]*?)( LIMIT :limit)?\"").findAll(src).forEach { m ->
                val clause = m.groupValues[1].trim()
                // Staffeln/Folgen sind kein Raster-Paging und haben eigene, fachliche Reihenfolgen.
                if (clause.startsWith("seasonNumber") || clause.startsWith("episodeNumber")) return@forEach
                if (!Regex("""(^|[ ,.])id( ASC| DESC)?$""").containsMatchIn(clause)) offenders += "$name: ORDER BY $clause"
            }
        }
        assertTrue("ORDER BY ohne id am Ende:\n" + offenders.joinToString("\n"), offenders.isEmpty())
    }
}
