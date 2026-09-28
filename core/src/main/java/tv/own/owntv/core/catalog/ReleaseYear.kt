package tv.own.owntv.core.catalog

import java.util.Calendar

/**
 * German4K: Erscheinungsjahr fuer die Sortierung „Erscheinungsjahr" (Kundenwunsch Aleks959, 28.09.2026).
 * Anbieterjahr zuerst, sonst `releaseDate`, sonst ein „(YYYY)" im Titel ("DE - Apex (2026)").
 * Bewusst ohne Regex: laeuft beim Import fuer ~70k Filme auf dem Fire TV.
 */
object ReleaseYear {
    private const val MIN_YEAR = 1900
    private val maxYear: Int = Calendar.getInstance().get(Calendar.YEAR) + 1

    fun resolve(providerYear: Int?, releaseDate: String?, name: String): Int? =
        providerYear?.takeIf(::plausible)
            ?: leadingYear(releaseDate)
            ?: fromTitle(name)

    /** The last "(YYYY)" in [name], or null. */
    fun fromTitle(name: String): Int? {
        var i = name.lastIndexOf('(')
        while (i >= 0) {
            if (i + 5 < name.length && name[i + 5] == ')') {
                val y = digits4(name, i + 1)
                if (y != null && plausible(y)) return y
            }
            i = if (i == 0) -1 else name.lastIndexOf('(', i - 1)
        }
        return null
    }

    /** "2024-05-01" / "2024" → 2024. */
    fun leadingYear(date: String?): Int? {
        if (date == null) return null
        val s = date.trimStart()
        if (s.length < 4 || (s.length > 4 && s[4].isDigit())) return null
        return digits4(s, 0)?.takeIf(::plausible)
    }

    private fun plausible(y: Int): Boolean = y in MIN_YEAR..maxYear

    private fun digits4(s: String, from: Int): Int? {
        var v = 0
        for (k in from until from + 4) {
            val c = s[k]
            if (c !in '0'..'9') return null
            v = v * 10 + (c - '0')
        }
        return v
    }
}
