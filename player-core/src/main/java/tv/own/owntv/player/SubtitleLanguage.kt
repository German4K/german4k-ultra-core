package tv.own.owntv.player

import java.util.Locale

/** Matches ISO-639-1/2 and regional tags (for example eng, en, and en-US). */
internal fun subtitleLanguageMatches(preferred: String, actual: String?): Boolean {
    if (preferred.isBlank() || actual.isNullOrBlank()) return false
    return languageIdentity(preferred) == languageIdentity(actual)
}

/**
 * German4K 3.2.1 (P7): ISO-639-2/B ("bibliographic") codes that Java's Locale does not fold onto the
 * terminology code. Matroska and mpv report `ger`, ExoPlayer normalises the same track to `de` (→ `deu`),
 * so without this the engine-switch carry-over found no German track at all.
 */
private val ISO639_B_ZU_T = mapOf(
    "ger" to "deu", "fre" to "fra", "dut" to "nld", "chi" to "zho", "cze" to "ces", "gre" to "ell",
    "per" to "fas", "rum" to "ron", "slo" to "slk", "alb" to "sqi", "arm" to "hye", "baq" to "eus",
    "bur" to "mya", "geo" to "kat", "ice" to "isl", "mac" to "mkd", "mao" to "mri", "may" to "msa",
    "tib" to "bod", "wel" to "cym", "scc" to "srp", "scr" to "hrv",
)

internal fun languageIdentity(code: String): String {
    val normalized = code.trim().replace('_', '-').lowercase(Locale.ROOT)
    if (UntertitelWechsel.aktiv()) ISO639_B_ZU_T[normalized.substringBefore('-')]?.let { return it }
    val locale = Locale.forLanguageTag(normalized)
    return runCatching { locale.isO3Language.lowercase(Locale.ROOT) }
        .getOrNull()
        .takeUnless { it.isNullOrBlank() }
        ?: normalized.substringBefore('-')
}
