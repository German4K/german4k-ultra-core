package tv.own.owntv.core.german4k

import android.content.Context

/**
 * German4K 3.3 (Schalter `profil_abgelaufen`, Kunde): Eine zusätzliche Quelle (zweites Profil, alter Testzugang,
 * fremde Playlist), die dauerhaft 401/403 liefert, wird nicht weiter im Hintergrund abgeglichen, und beim nächsten
 * Öffnen fragt die App „Zugang X ist abgelaufen — entfernen?“.
 *
 * „Dauerhaft“ = mindestens [MIN_FEHLER] Anmeldefehler beim Abgleich über mindestens [MIN_SPANNE_MS]. Ein einzelner
 * 403 (Panel kurz weg, Umzug) sperrt nichts; ein erfolgreicher Abgleich setzt den Zähler zurück. Unser Hauptzugang
 * (vom Panel gekoppelt, [German4kProvisioner.verwaltet]) ist ausgenommen — er wird vom Panel selbst verwaltet.
 */
object German4kAbgelaufen {
    const val MIN_FEHLER = 3
    const val MIN_SPANNE_MS = 30 * 60_000L

    data class Stand(val erste: Long, val letzte: Long, val anzahl: Int)

    // ---- reine Logik (Unit-Tests) ----

    fun nachFehler(alt: Stand?, jetzt: Long): Stand =
        alt?.copy(letzte = maxOf(alt.letzte, jetzt), anzahl = alt.anzahl + 1) ?: Stand(jetzt, jetzt, 1)

    fun dauerhaft(s: Stand?): Boolean = s != null && s.anzahl >= MIN_FEHLER && s.letzte - s.erste >= MIN_SPANNE_MS

    /** Abgleich-Fehler, der „Zugang abgelehnt“ heißt (dieselben Nadeln wie classifySyncFailure). */
    fun istAnmeldeFehler(raw: String?): Boolean =
        raw != null && (raw.contains(HTTP_401, ignoreCase = true) || raw.contains(HTTP_403, ignoreCase = true))

    /** Gesperrt für Hintergrund-Abfragen: Schalter an, nicht unser gekoppelter Zugang, dauerhaft abgelehnt. */
    fun gesperrt(stand: Stand?, sourceId: Long, verwaltet: Set<Long>, schalterAn: Boolean): Boolean =
        schalterAn && sourceId !in verwaltet && dauerhaft(stand)

    fun kodieren(s: Stand): String = "${s.erste},${s.letzte},${s.anzahl}"

    fun lesen(raw: String?): Stand? {
        val t = raw?.split(',') ?: return null
        if (t.size != 3) return null
        val e = t[0].toLongOrNull() ?: return null
        val l = t[1].toLongOrNull() ?: return null
        val n = t[2].toIntOrNull() ?: return null
        return Stand(e, l, n)
    }

    // ---- Speicher (SharedPreferences, überlebt Neustarts; keine Room-Migration) ----

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun key(sourceId: Long) = "s$sourceId"

    fun stand(context: Context, sourceId: Long): Stand? = lesen(prefs(context).getString(key(sourceId), null))

    fun melde(context: Context, sourceId: Long, jetzt: Long = System.currentTimeMillis()) {
        val neu = nachFehler(stand(context, sourceId), jetzt)
        prefs(context).edit().putString(key(sourceId), kodieren(neu)).apply()
    }

    fun entlaste(context: Context, sourceId: Long) {
        prefs(context).edit().remove(key(sourceId)).apply()
    }

    fun istGesperrt(context: Context, sourceId: Long, verwaltet: Set<Long>): Boolean =
        gesperrt(stand(context, sourceId), sourceId, verwaltet, German4kFeatures.an(German4kSchalter.PROFIL_ABGELAUFEN))

    private const val PREFS = "german4k_abgelaufen"
    private const val HTTP_401 = "HTTP 401"
    private const val HTTP_403 = "HTTP 403"
}
