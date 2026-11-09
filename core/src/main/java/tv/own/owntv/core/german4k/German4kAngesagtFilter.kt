package tv.own.owntv.core.german4k

import android.content.Context

/**
 * German4K 3.2.1 (B3): Umschalter „Alle · Filme · Serien“ am Angesagt-Regal der Startseite.
 *
 * Der Hero bleibt immer die Gesamt-#1; gefiltert wird nur das Regal darunter. Standard kommt je Gerät
 * vom Server ([German4kKonfig.angesagtStandard]), eine Wahl am Gerät hat Vorrang und wird gemerkt.
 * Gibt es nur eine Art (nur Filme oder nur Serien), entfällt der Umschalter.
 */
object German4kAngesagtFilter {
    enum class Wahl(val schluessel: String) { ALLE("alle"), FILME("filme"), SERIEN("serien") }

    fun von(schluessel: String?): Wahl? = Wahl.entries.firstOrNull { it.schluessel == schluessel?.trim()?.lowercase() }

    /** Welche Wahlen sinnvoll sind; nur `[ALLE]` = keine Chips zeigen. */
    fun verfuegbar(karten: List<German4kAngesagtKarte>): List<Wahl> {
        val filme = karten.any { !it.serie }
        val serien = karten.any { it.serie }
        return if (filme && serien) listOf(Wahl.ALLE, Wahl.FILME, Wahl.SERIEN) else listOf(Wahl.ALLE)
    }

    /** Lokale Wahl vor Server-Standard; was es gerade nicht gibt, fällt auf „Alle“ zurück. */
    fun aktiv(lokal: String?, server: String?, verfuegbar: List<Wahl>): Wahl {
        val gewuenscht = von(lokal) ?: von(server) ?: Wahl.ALLE
        return if (gewuenscht in verfuegbar) gewuenscht else Wahl.ALLE
    }

    fun filtern(karten: List<German4kAngesagtKarte>, wahl: Wahl): List<German4kAngesagtKarte> = when (wahl) {
        Wahl.ALLE -> karten
        Wahl.FILME -> karten.filter { !it.serie }
        Wahl.SERIEN -> karten.filter { it.serie }
    }

    private const val PREFS = "german4k_angesagt"
    private const val KEY_WAHL = "filter_wahl"

    fun gespeichert(context: Context): String? =
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_WAHL, null) }.getOrNull()

    fun speichere(context: Context, wahl: Wahl) {
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_WAHL, wahl.schluessel).apply() }
    }
}
