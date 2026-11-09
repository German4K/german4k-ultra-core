package tv.own.owntv.core.german4k

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * German4K 3.2.1 (U43, Schalter `datum_anzeige`): Datumsangaben vom Server („2026-10-27", auch mit Uhrzeit)
 * so zeigen, wie man sie im Land liest — deutsch „27.10.2026", englisch „Oct 27, 2026". Dieselbe Form wie die
 * Laufzeit der Playlist in den Quellen (DateFormat.MEDIUM). Was sich nicht lesen lässt, bleibt unverändert.
 */
object German4kDatum {
    private val ISO = Regex("""^(\d{4})-(\d{2})-(\d{2})""")

    fun anzeige(roh: String, locale: Locale): String {
        val t = roh.trim()
        val m = ISO.find(t) ?: return roh
        val d = runCatching {
            LocalDate.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt())
        }.getOrNull() ?: return roh
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(d)
    }

    // German4K 3.2.1 (p-18)
    private val ISO_IM_TEXT = Regex("""(?<![\d-])\d{4}-\d{2}-\d{2}(?![\d-])""")

    /**
     * German4K 3.2.1 (p-18/5): ISO-Daten mitten im Servertext („Dein Zugang läuft am 2026-10-27 ab.") in Landesform —
     * sonst stand im selben Ablauf-Dialog einmal „2026-10-27" und darunter „27.10.2026". Ungültiges bleibt stehen.
     */
    fun imText(text: String, locale: Locale): String =
        ISO_IM_TEXT.replace(text) { m -> anzeige(m.value, locale) }
}
