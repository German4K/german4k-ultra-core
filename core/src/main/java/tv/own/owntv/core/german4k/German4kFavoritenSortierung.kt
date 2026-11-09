package tv.own.owntv.core.german4k

import tv.own.owntv.core.settings.SettingsRepository.SortMode

/**
 * German4K 3.2.1 (A4): Welche Favoriten-Abfrage zu welcher Sortierung gehört (Filme und Serien).
 * Bis 3.2 lief der Favoriten-Ordner immer über die eigene Reihenfolge — „Sortieren nach“ wirkte nicht
 * (Kunde 18.10.). „Anbieter“ (PLAYLIST) bleibt die eigene Reihenfolge, weil Favoriten keine
 * Anbieter-Reihenfolge haben; jede andere Sortierung hat ihre eigene DAO-Abfrage.
 */
enum class FavoritenAbfrage { EIGENE, A_Z, BEWERTUNG, DATUM, JAHR, ANBIETER }

object German4kFavoritenSortierung {
    /**
     * German4K 3.2.1 (U52): „Anbieter" = echte Anbieter-/Playlist-Reihenfolge (eigene Reihenfolge vorn,
     * [FavoritenAbfrage.ANBIETER]). Schalter `favoriten_anbieter` aus = 3.2.1-Verhalten (eigene Reihenfolge,
     * dann zuletzt favorisiert).
     */
    fun abfrage(sort: SortMode): FavoritenAbfrage =
        abfrage(sort, German4kFeatures.an(German4kSchalter.FAVORITEN_ANBIETER))

    fun abfrage(sort: SortMode, anbieter: Boolean): FavoritenAbfrage = when (sort) {
        SortMode.PLAYLIST -> if (anbieter) FavoritenAbfrage.ANBIETER else FavoritenAbfrage.EIGENE
        SortMode.ALPHA -> FavoritenAbfrage.A_Z
        SortMode.RATING -> FavoritenAbfrage.BEWERTUNG
        SortMode.DATE_ADDED -> FavoritenAbfrage.DATUM
        SortMode.YEAR -> FavoritenAbfrage.JAHR
    }
}

/**
 * German4K 3.2.1 (U10): Live-Favoriten. Der Knopf heißt dort „Sortieren: Nummer" bzw. „A–Z", die Abfrage war aber
 * immer dieselbe (eigene Reihenfolge, dann neueste zuerst) — „Drei" stand vor „Eins". Jetzt: Nummer = Sendernummer,
 * A–Z = Name; eine selbst verschobene Reihenfolge steht in beiden vorn (wie in den Kategorie-Ordnern).
 * Schalter `favoriten_nummer` aus = altes Verhalten.
 */
object German4kFavoritenLive {
    enum class Abfrage { NUMMER, A_Z, HINZUGEFUEGT }

    fun abfrage(sort: SortMode, an: Boolean): Abfrage = when {
        !an -> Abfrage.HINZUGEFUEGT
        sort == SortMode.ALPHA -> Abfrage.A_Z
        else -> Abfrage.NUMMER
    }
}
