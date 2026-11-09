package tv.own.owntv.core.german4k

import java.time.Instant

/**
 * German4K 3.2.1 (U38): Wann der Spielplan neu geholt wird und welche Uhrzeit „Stand" zeigt.
 *
 * Befund Fire TV 22.10. (03:00 „Stand 19:47"): „Stand" war `stand` vom Server = letzte Änderung des
 * Spielplans in der Datenbank, nicht der Zeitpunkt, an dem die App ihn geholt hat. Nachts ändert sich der
 * Spielplan nicht — die App sah frisch geholte Daten aber trotzdem „7 h alt" aus. Zugleich lief der
 * Abruf der Startseite (alle 5 min) und des Fußball-Bereichs (alle 60 s) auch weiter, wenn die App gar
 * nicht sichtbar war. Jetzt: Startseite und Öffnen des Bereichs holen nur, wenn der letzte Abruf
 * mindestens [MIN_ABSTAND_MS] her ist, und nur solange sichtbar; „Stand" = Antwortzeit des Servers.
 */
object German4kSportFrische {
    /** Höchstens ein Abruf je zehn Minuten von der Startseite / beim Öffnen. */
    const val MIN_ABSTAND_MS = 10 * 60_000L

    /** Wie oft die Startseite prüft, ob ein Abruf fällig ist (kostet nichts, fragt den Server nicht). */
    const val PRUEF_TAKT_MS = 60_000L

    /**
     * Ist ein neuer Abruf fällig? Noch nie geholt → ja. Uhr zurückgestellt (letzter Abruf „in der Zukunft") →
     * ja, sonst bliebe der Plan bis zur alten Uhrzeit stehen.
     */
    fun faellig(letzterAbrufMs: Long?, jetztMs: Long, minAbstandMs: Long = MIN_ABSTAND_MS): Boolean {
        if (letzterAbrufMs == null) return true
        if (jetztMs < letzterAbrufMs) return true
        return jetztMs - letzterAbrufMs >= minAbstandMs
    }

    /** Zeitpunkt einer (auch zwischengespeicherten) Antwort: `server_time`, oder null wenn unlesbar. */
    fun abrufZeitMs(antwort: German4kSportAntwort?): Long? =
        antwort?.serverTime?.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    /**
     * ISO-Zeit für „Stand …": mit Schalter die Antwortzeit (wann die App zuletzt erfolgreich geholt hat),
     * fällt sie aus, der Spielplan-Stand; ohne Schalter wie bisher nur der Spielplan-Stand.
     */
    fun standIso(antwort: German4kSportAntwort?, schalterAn: Boolean): String? {
        if (antwort == null) return null
        if (!schalterAn) return antwort.stand
        return antwort.serverTime.takeIf { abrufZeitMs(antwort) != null } ?: antwort.stand
    }
}
