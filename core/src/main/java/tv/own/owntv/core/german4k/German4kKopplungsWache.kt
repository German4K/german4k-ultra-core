package tv.own.owntv.core.german4k

import kotlinx.coroutines.delay

/**
 * German4K 3.2.1 (U4): Wer sein Gerät über die Website koppelt, schaut dabei auf den Anmeldebildschirm — die
 * App merkte es erst nach „Erneut prüfen". Solange der Bildschirm sichtbar ist, fragt sie still nach:
 * die ersten drei Minuten alle 5 s (da passiert die Kopplung fast immer), danach alle 15 s, nach zehn
 * Minuten gar nicht mehr (Panel-Last: ein vergessenes Gerät im Laden fragt sonst den ganzen Tag).
 * Sichtbar-Werden (App wieder vorn) startet die Frist neu.
 */
object German4kKopplungsWache {
    const val SCHNELL_MS = 5_000L
    const val SCHNELL_BIS_MS = 3L * 60_000
    const val LANGSAM_MS = 15_000L
    const val MAX_MS = 10L * 60_000

    /** true, sobald [pruefe] einmal true liefert; false nach [MAX_MS]. */
    suspend fun warte(
        pruefe: suspend () -> Boolean,
        schlafe: suspend (Long) -> Unit = { delay(it) },
    ): Boolean {
        var verstrichen = 0L
        while (verstrichen < MAX_MS) {
            val schritt = if (verstrichen < SCHNELL_BIS_MS) SCHNELL_MS else LANGSAM_MS
            schlafe(schritt)
            verstrichen += schritt
            if (pruefe()) return true
        }
        return false
    }
}
