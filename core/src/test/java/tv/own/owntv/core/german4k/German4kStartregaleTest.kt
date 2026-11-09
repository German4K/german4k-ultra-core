package tv.own.owntv.core.german4k

import org.junit.Assert.assertEquals
import org.junit.Test
import tv.own.owntv.core.model.HomeConfig
import tv.own.owntv.core.model.HomeRow
import tv.own.owntv.core.model.HomeRow.CONTINUE_MOVIES
import tv.own.owntv.core.model.HomeRow.CONTINUE_SERIES
import tv.own.owntv.core.model.HomeRow.FAVORITE_CHANNELS
import tv.own.owntv.core.model.HomeRow.G4K_FUSSBALL
import tv.own.owntv.core.model.HomeRow.G4K_JETZT_TV
import tv.own.owntv.core.model.HomeRow.G4K_NEU_FILME
import tv.own.owntv.core.model.HomeRow.HERO
import tv.own.owntv.core.model.HomeRow.RECENT_CHANNELS
import tv.own.owntv.core.model.HomeRow.TRENDING

/** German4K 3.2.1 (D2): Bestandskunden behalten nach dem Update exakt die Startseiten-Reihenfolge von 3.2. */
class German4kStartregaleTest {

    /** So hat 3.2 die Startseite gebaut (HomeScreen `slots`, alle Regale mit Daten): Fußball, Jetzt im TV,
     *  dann die sichtbaren Reihen, „Neu bei Filme“ hinter HERO bzw. am Ende. */
    private fun wie32(c: HomeConfig): List<HomeRow> = buildList {
        add(G4K_FUSSBALL); add(G4K_JETZT_TV)
        c.visibleOrder.filterNot { German4kStartregale.istEigen(it) }.forEach { row ->
            add(row)
            if (row == HERO) add(G4K_NEU_FILME)
        }
        if (G4K_NEU_FILME !in this) add(G4K_NEU_FILME)
    }

    private fun json32(order: List<HomeRow>, hidden: Set<HomeRow>) =
        """{"order":[${order.joinToString(",") { "\"${it.name}\"" }}],"hidden":[${hidden.joinToString(",") { "\"${it.name}\"" }}]}"""

    @Test fun `gespeicherte 3_2-Reihenfolgen bleiben gleich`() {
        val alt = listOf(TRENDING, HERO, RECENT_CHANNELS, FAVORITE_CHANNELS, CONTINUE_MOVIES, CONTINUE_SERIES)
        val faelle = listOf(
            alt to setOf(RECENT_CHANNELS),
            alt to emptySet(),
            listOf(CONTINUE_SERIES, FAVORITE_CHANNELS, HERO, CONTINUE_MOVIES, RECENT_CHANNELS, TRENDING) to setOf(TRENDING),
            // „Verschieben“ in 3.2 schrieb die Liste ohne TRENDING.
            listOf(FAVORITE_CHANNELS, CONTINUE_MOVIES, RECENT_CHANNELS, HERO, CONTINUE_SERIES) to emptySet(),
            // HERO ausgeblendet → Neu bei Filme stand am Ende.
            listOf(HERO, CONTINUE_MOVIES, CONTINUE_SERIES, RECENT_CHANNELS, FAVORITE_CHANNELS) to setOf(HERO, CONTINUE_MOVIES),
        )
        for ((order, hidden) in faelle) {
            val c = HomeConfig.fromJson(json32(order, hidden))
            val alt32 = HomeConfig(order = order.filter { it in HomeRow.entries }, hidden = hidden)
            assertEquals("$order / $hidden", wie32(alt32), German4kStartregale.reihenfolge(c))
        }
    }

    @Test fun `Neuinstallation zeigt die 3_2-Reihenfolge`() {
        val c = HomeConfig()
        assertEquals(wie32(c), German4kStartregale.reihenfolge(c))
    }

    @Test fun `ausblenden und gespeicherte v2-Reihenfolge wirken`() {
        val c = HomeConfig.fromJson(json32(listOf(HERO, G4K_NEU_FILME, G4K_FUSSBALL, CONTINUE_MOVIES, G4K_JETZT_TV), setOf(G4K_JETZT_TV, TRENDING)))
        assertEquals(listOf(HERO, G4K_NEU_FILME, G4K_FUSSBALL, CONTINUE_MOVIES), German4kStartregale.reihenfolge(c).take(4))
        assertEquals(false, G4K_JETZT_TV in German4kStartregale.reihenfolge(c))
    }

    @Test fun `verschieben mit Schalter aus laesst eigene Regale an ihrem Platz`() {
        val rows = listOf(G4K_FUSSBALL, G4K_JETZT_TV, HERO, G4K_NEU_FILME, RECENT_CHANNELS, CONTINUE_MOVIES)
        val ohne = { r: HomeRow -> !German4kStartregale.istEigen(r) }
        assertEquals(
            listOf(G4K_FUSSBALL, G4K_JETZT_TV, RECENT_CHANNELS, G4K_NEU_FILME, HERO, CONTINUE_MOVIES),
            German4kStartregale.verschiebe(rows, RECENT_CHANNELS, hoch = true, rand = false, sichtbar = ohne),
        )
        assertEquals(
            listOf(G4K_FUSSBALL, G4K_JETZT_TV, RECENT_CHANNELS, G4K_NEU_FILME, CONTINUE_MOVIES, HERO),
            German4kStartregale.verschiebe(rows, HERO, hoch = false, rand = true, sichtbar = ohne),
        )
        // Schalter an: alles zählt.
        assertEquals(
            listOf(G4K_FUSSBALL, HERO, G4K_JETZT_TV, G4K_NEU_FILME, RECENT_CHANNELS, CONTINUE_MOVIES),
            German4kStartregale.verschiebe(rows, HERO, hoch = true, rand = false, sichtbar = { true }),
        )
    }
}
