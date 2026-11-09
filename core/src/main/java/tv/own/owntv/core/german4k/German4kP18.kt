package tv.own.owntv.core.german4k

// German4K 3.2.1 (p-18) — reine Entscheidungen zu den Befunden der Fire-TV-Abnahme von Kandidat 12 (testbar ohne Android).

/**
 * P18-2 (Schalter `multiview_migration`): einmalige Umstellung beim Update auf 3.2.1. B1 hat nur den Standard für
 * „nie berührt" auf an gestellt; ein Gerät, auf dem 3.2 schon „aus" gespeichert hatte (Fire TV, Abnahme K12), blieb aus.
 * Ob der Kunde das selbst ausgeschaltet hat, lässt sich nicht unterscheiden — deshalb genau einmal an, mit Merker.
 */
object MultiviewMigration {
    /**
     * Was die Migration tut: [schreibeAn] = gespeicherten Wert auf an setzen, [merke] = Merker „migriert“ setzen,
     * [merkeErstenStart] = German4K 3.2.1 (t-15): Merker „3.2.1 gesehen“ mit „war beim ersten Start erlaubt“ (null = steht schon).
     */
    data class Schritt(val schreibeAn: Boolean, val merke: Boolean, val merkeErstenStart: Boolean? = null)

    /**
     * German4K 3.2.1 (t-15, Review K13): [ersterStartErlaubt] = beim ersten 3.2.1-Start (mit frischer Panel-Antwort)
     * gemerkt, ob die Migration da erlaubt war; null = dies IST der erste Start. [geaendert] = der Kunde hat Multiview seit
     * 3.2.1 selbst umgeschaltet. War die Migration beim ersten Start aus, greift ein späteres „an“ nur noch, wenn der
     * Kunde seitdem nichts geändert hat — wer in 3.2.1 bewusst ausgeschaltet hat, bleibt aus.
     */
    fun schritt(
        gespeichert: Boolean?, schonMigriert: Boolean, multiviewAn: Boolean, migrationAn: Boolean,
        ersterStartErlaubt: Boolean? = null, geaendert: Boolean = false,
    ): Schritt {
        if (schonMigriert) return Schritt(schreibeAn = false, merke = false)
        // German4K 3.2.1 (t-16): Merker leer (erster Start offline, die Migration wartete auf die frische Antwort) und der
        // Kunde hat Multiview inzwischen selbst umgeschaltet → nur den Merker setzen, nie einschalten. Als „nicht erlaubt“
        // gemerkt, damit auch ein späterer Lauf seine eigene Wahl stehen lässt.
        if (ersterStartErlaubt == null && geaendert) return Schritt(schreibeAn = false, merke = false, merkeErstenStart = false)
        val erlaubtJetzt = multiviewAn && migrationAn
        val erster = if (ersterStartErlaubt == null) erlaubtJetzt else null
        val erlaubtBeimErsten = ersterStartErlaubt ?: erlaubtJetzt
        if (!erlaubtJetzt || (!erlaubtBeimErsten && geaendert)) return Schritt(schreibeAn = false, merke = false, merkeErstenStart = erster)
        return Schritt(schreibeAn = gespeichert == false, merke = true, merkeErstenStart = erster)
    }
}

/**
 * P18-6 (Schalter `ablauf_hinweis_ruhig`): „Später" bzw. ZURÜCK im Hinweis-Dialog merkt sich den Hinweis für den
 * Kalendertag wie „Verstanden". Nie für Wartung (muss immer kommen) und nie für „abgelaufen" (bleibt abgelaufen).
 * Die letzten Tage vor Ablauf kommt er dadurch weiter täglich — der Merker gilt nur für heute.
 */
object HinweisSpaeter {
    fun merken(noteTyp: String, schalterAn: Boolean): Boolean =
        schalterAn && noteTyp != "wartung" && noteTyp != "abgelaufen"
}

/**
 * P18-4 (Schalter `sport_rueck_fokus`): wo die Karte eines Spiels in der Fußball-Liste (Fernseher) steht — [zeile] =
 * Eintrag der LazyColumn, [spalte] = Platz in der Reihe (Live-Reihe bzw. Rasterzeile). Bildet die Reihenfolge von
 * `TvRegale` nach: Konferenzen (je ein Eintrag), „Jetzt live" (eine Reihe), „Heute" (Titel + Rasterzeilen),
 * „Morgen & später" (Kopf, offen: je Tag Titel + Rasterzeilen). null = nicht (sichtbar) in der Liste.
 */
object SportRueckFokus {
    data class Ziel(val zeile: Int, val spalte: Int)

    fun ziel(regale: SportRegale, spaeterOffen: Boolean, id: Long, spalten: Int = 4): Ziel? {
        var i = 0
        for (k in regale.konferenzen) { if (k.id == id) return Ziel(i, 0); i++ }
        val live = regale.live + regale.beendet
        if (live.isNotEmpty()) {
            val p = live.indexOfFirst { it.id == id }
            if (p >= 0) return Ziel(i, p)
            i++
        }
        fun raster(spiele: List<German4kSpiel>): Ziel? {
            val p = spiele.indexOfFirst { it.id == id }
            if (p >= 0) return Ziel(i + p / spalten, p % spalten)
            i += (spiele.size + spalten - 1) / spalten
            return null
        }
        if (regale.heute.isNotEmpty()) {
            i++ // Titel „Heute"
            raster(regale.heute)?.let { return it }
        }
        if (regale.spaeter.isNotEmpty()) {
            if (!spaeterOffen) return null
            i++ // Kopf „Morgen & später"
            for ((_, spiele) in nachTagen(regale.spaeter)) {
                i++ // Tagestitel
                raster(spiele)?.let { return it }
            }
        }
        return null
    }

    fun inSpaeter(regale: SportRegale, id: Long): Boolean = regale.spaeter.any { it.id == id }
}
