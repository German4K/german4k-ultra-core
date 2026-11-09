package tv.own.owntv.core.german4k

import tv.own.owntv.core.model.HomeConfig
import tv.own.owntv.core.model.HomeRow

/**
 * German4K 3.2.1 (D2): Die eigenen Startregale „Jetzt im Fußball“, „Jetzt im TV“ und „Neu bei Filme“
 * als [HomeRow]-Einträge — ein-/ausblendbar und sortierbar in Layout ▸ Startbildschirm.
 * Schalter [German4kSchalter.STARTREGALE_V2] aus = feste Plätze wie 3.2 (die Einträge bleiben in der
 * gespeicherten Reihenfolge stehen, werden aber weder gezeigt noch beim Verschieben berührt).
 */
object German4kStartregale {
    val EIGENE: Set<HomeRow> = setOf(HomeRow.G4K_FUSSBALL, HomeRow.G4K_JETZT_TV, HomeRow.G4K_NEU_FILME)

    fun istEigen(row: HomeRow): Boolean = row in EIGENE

    /**
     * Reihenfolge der Startseite unter „Angesagt“ (v2): die gespeicherte Reihenfolge ohne Ausgeblendete;
     * „Aktuell im Trend“ (TRENDING) steht — wie bisher — hinter den führenden Fußball/Jetzt-im-TV-Regalen.
     */
    fun reihenfolge(config: HomeConfig): List<HomeRow> {
        val liste = config.order.filter { it != HomeRow.TRENDING && it.implemented && it !in config.hidden }
        if (HomeRow.TRENDING in config.hidden) return liste
        val vorn = liste.takeWhile { it == HomeRow.G4K_FUSSBALL || it == HomeRow.G4K_JETZT_TV }.size
        return liste.take(vorn) + HomeRow.TRENDING + liste.drop(vorn)
    }

    /**
     * Verschiebt [row] in [rows] um einen Schritt ([hoch]) bzw. an den Rand ([rand]) — gezählt werden nur
     * Einträge, für die [sichtbar] gilt. Unsichtbare (Schalter aus) behalten ihren Platz.
     */
    fun verschiebe(rows: List<HomeRow>, row: HomeRow, hoch: Boolean, rand: Boolean, sichtbar: (HomeRow) -> Boolean): List<HomeRow> {
        val sicht = rows.filter(sichtbar)
        val von = sicht.indexOf(row)
        if (von < 0) return rows
        val nach = when {
            rand -> if (hoch) 0 else sicht.lastIndex
            hoch -> von - 1
            else -> von + 1
        }
        if (nach !in sicht.indices || nach == von) return rows
        val neu = sicht.toMutableList().also { it.add(nach, it.removeAt(von)) }
        // Sichtbare Plätze der Reihe nach neu belegen; unsichtbare bleiben, wo sie sind.
        val it = neu.iterator()
        return rows.map { r -> if (sichtbar(r)) it.next() else r }
    }
}
