package tv.own.owntv.player

import java.util.Locale

/**
 * German4K 3.2 (F5/N6): „belegte Leitung“ beim Öffnen eines Films oder einer Folge erkennen.
 *
 * Ein Zugang mit einer Verbindung, der gerade auf einem zweiten Gerät läuft, wird vom Anbieter mit
 * `458`/`460` abgewiesen (manche Panels schreiben „max connections“ in den Text). Bisher ging die App
 * dann ihre ganze Leiter durch — ExoPlayer-Software, mpv, Copy-Modus, Software, anderer User-Agent —
 * und lud immer wieder neu (Anas605, 10.10.2026: 49 Versuche, parallel lief IBO auf dem iPhone).
 * Kein Player und kein Decoder kann das lösen, nur das andere Gerät oder „Verbindung freigeben“. Also
 * sofort aufhören und den Satz zeigen, der stimmt: „Dein Zugang wird gerade auf einem anderen Gerät
 * genutzt“ (German4kStoerung → Hinweis mit Knopf „Verbindung freigeben“).
 *
 * Bewusst NICHT 403: das heißt bei uns meistens „abgelaufen“ ([tv.own.owntv.core.german4k.German4kHealth]).
 * Und nur VOD — Live hat seine eigene Leiter in LiveViewModel, die einen 458 kurz abwartet, weil er beim
 * Senderwechsel auch vom eigenen, noch nicht freigegebenen Stream kommen kann.
 */
object German4kLeitung {

    fun belegtStatus(code: Int?): Boolean = code == 458 || code == 460

    /** mpv-Fehlerzeile oder Anbietertext. */
    fun belegtText(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        if (belegtStatus(STATUS.find(text)?.groupValues?.get(1)?.toIntOrNull())) return true
        val t = text.lowercase(Locale.ROOT)
        return WOERTER.any { it in t }
    }

    /** Der Grund in der Form, die [tv.own.owntv.core.german4k.German4kHealth.klassifiziere] als LEITUNG_BELEGT liest. */
    fun grund(code: Int?): String = if (code != null) "HTTP $code" else "max connections"

    /** Meldet die Lage an die Overlay-Schicht (Hinweis mit „Verbindung freigeben“). */
    fun melden(titel: String?, code: Int?) {
        tv.own.owntv.core.german4k.German4kStoerung.melde(titel?.takeIf { it.isNotBlank() } ?: "", grund(code))
    }

    private val STATUS = Regex("""(?:HTTP(?: error)?|response code:?|status(?: code)?:?)\s*(\d{3})\b""", RegexOption.IGNORE_CASE)
    private val WOERTER = listOf("max connections", "maximum connections", "connection limit", "too many connections", "max_connections")
}
