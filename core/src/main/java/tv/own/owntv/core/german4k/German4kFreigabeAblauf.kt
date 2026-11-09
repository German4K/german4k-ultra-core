package tv.own.owntv.core.german4k

import kotlinx.coroutines.delay

/**
 * German4K 3.2.1 (B4): nach „Leitung freigeben“ abwarten, bis der Kick beim Anbieter durch ist, und dann
 * den Sender neu starten. Liefert der Server eine Vorgangskennung und kennt er die Statusabfrage, wird
 * gefragt; sonst (alter Server, keine Kennung, unbekannte Antwort) einfach [OHNE_STAND_MS] warten.
 */
object German4kFreigabeAblauf {
    const val OHNE_STAND_MS = 4_000L
    const val ABFRAGE_MS = 1_500L
    const val HOECHSTENS_MS = 20_000L

    /** true = jetzt neu starten, false = der Vorgang ist laut Server gescheitert. */
    suspend fun abwarten(
        vorgang: String?,
        stand: suspend (String) -> String?,
        warte: suspend (Long) -> Unit = { delay(it) },
    ): Boolean {
        if (vorgang == null) { warte(OHNE_STAND_MS); return true }
        var gewartet = 0L
        while (gewartet < HOECHSTENS_MS) {
            warte(ABFRAGE_MS); gewartet += ABFRAGE_MS
            when (stand(vorgang)) {
                "fertig" -> return true
                "fehler" -> return false
                "wartet" -> Unit
                else -> {
                    // Server kennt die Abfrage (noch) nicht: auf die feste Wartezeit auffüllen.
                    if (gewartet < OHNE_STAND_MS) warte(OHNE_STAND_MS - gewartet)
                    return true
                }
            }
        }
        return true // Frist um: trotzdem probieren — schlimmstenfalls kommt der Hinweis wieder
    }
}
