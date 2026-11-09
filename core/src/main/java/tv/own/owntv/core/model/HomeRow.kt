package tv.own.owntv.core.model

import androidx.compose.runtime.Immutable

import org.json.JSONArray
import org.json.JSONObject

enum class HomeRow {
    TRENDING,
    // German4K 3.2.1 (D2): eigene Startregale, ein-/ausblendbar und sortierbar (Schalter `startregale_v2`).
    // Ihre Stelle im Enum ist die Neuinstallations-Reihenfolge und entspricht 3.2: Fußball und Jetzt im TV
    // vor „Weiterschauen“ (HERO), Neu bei Filme direkt danach. Bestandskunden: siehe [mergeOrder].
    G4K_FUSSBALL,
    G4K_JETZT_TV,
    HERO,
    G4K_NEU_FILME,
    RECENT_CHANNELS,
    FAVORITE_CHANNELS,
    CONTINUE_MOVIES,
    CONTINUE_SERIES;

    val implemented: Boolean get() = true
}

enum class HomeLiveRowMode {
    CARDS,
    ON_NOW;

    fun toggled(): HomeLiveRowMode = when (this) {
        CARDS -> ON_NOW
        ON_NOW -> CARDS
    }
}

/**
 * How the Trending row is drawn: the full card with the artwork, badges and reasons, or the plain
 * strip of posters. [HERO] is the default, so nothing changes for anyone who never opens the setting.
 */
enum class HomeTrendingStyle {
    HERO,
    POSTERS,
}

enum class HeroKind {
    LIVE, MOVIES, SERIES,
}

@Immutable
data class HomeConfig(
    val order: List<HomeRow> = HomeRow.entries.toList(),
    val hidden: Set<HomeRow> = setOf(HomeRow.RECENT_CHANNELS),
    val heroIncludeLive: Boolean = true,
    val heroIncludeMovies: Boolean = true,
    val heroIncludeSeries: Boolean = true,
    val recentLiveMode: HomeLiveRowMode = HomeLiveRowMode.CARDS,
    val favoriteLiveMode: HomeLiveRowMode = HomeLiveRowMode.ON_NOW,
    val trendingStyle: HomeTrendingStyle = HomeTrendingStyle.HERO,
) {
    val visibleOrder: List<HomeRow>
        get() = buildList {
            // Trending is a fixed, optional Home feature rather than a reorderable content row.
            // When enabled it always owns the top position, regardless of any previously saved order.
            if (HomeRow.TRENDING !in hidden) add(HomeRow.TRENDING)
            addAll(order.filter { it != HomeRow.TRENDING && it.implemented && it !in hidden })
        }

    val settingsRows: List<HomeRow>
        get() = order.filter { it != HomeRow.TRENDING && it.implemented }

    fun toJson(): JSONObject = JSONObject().apply {
        put("order", JSONArray(order.map { it.name }))
        put("hidden", JSONArray(hidden.map { it.name }))
        put("heroLive", heroIncludeLive)
        put("heroMovies", heroIncludeMovies)
        put("heroSeries", heroIncludeSeries)
        put("recentLiveMode", recentLiveMode.name)
        put("favoriteLiveMode", favoriteLiveMode.name)
        put("trendingStyle", trendingStyle.name)
    }

    companion object {
        fun fromJson(raw: String?): HomeConfig {
            if (raw.isNullOrBlank()) return HomeConfig()
            val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return HomeConfig()
            val storedOrder = obj.optJSONArray("order").toHomeRows()
            val hidden = if (obj.has("hidden")) obj.optJSONArray("hidden").toHomeRows().toSet()
                else HomeConfig().hidden
            return HomeConfig(
                order = mergeOrder(storedOrder, hidden),
                hidden = hidden,
                heroIncludeLive = readBool(obj, "heroLive", "heroIncludeLive", default = true),
                heroIncludeMovies = readBool(obj, "heroMovies", "heroIncludeMovies", default = true),
                heroIncludeSeries = readBool(obj, "heroSeries", "heroIncludeSeries", default = true),
                recentLiveMode = readLiveMode(obj, "recentLiveMode", HomeLiveRowMode.CARDS),
                favoriteLiveMode = readLiveMode(obj, "favoriteLiveMode", HomeLiveRowMode.ON_NOW),
                // Absent in every config written before the setting existed, and in every backup
                // taken then — those all read as the hero, which is what they were showing.
                trendingStyle = runCatching { HomeTrendingStyle.valueOf(obj.optString("trendingStyle")) }
                    .getOrDefault(HomeTrendingStyle.HERO),
            )
        }

        private fun readBool(obj: JSONObject, primary: String, fallback: String, default: Boolean): Boolean =
            when {
                obj.has(primary) -> obj.optBoolean(primary, default)
                obj.has(fallback) -> obj.optBoolean(fallback, default)
                else -> default
            }

        private fun readLiveMode(obj: JSONObject, key: String, default: HomeLiveRowMode): HomeLiveRowMode =
            runCatching { HomeLiveRowMode.valueOf(obj.optString(key)) }.getOrDefault(default)
    }
}

fun mergeOrder(stored: List<HomeRow>, hidden: Set<HomeRow> = emptySet()): List<HomeRow> {
    val result = LinkedHashSet<HomeRow>()
    stored.forEach { result += it }
    // German4K 3.2.1 (D2): Eine Reihenfolge von vor 3.2.1 kennt die eigenen Regale nicht. Sie kommen genau
    // dorthin, wo 3.2 sie fest gezeigt hat — egal, wie der Kunde den Rest sortiert hat: Fußball und
    // Jetzt im TV ganz vorn, Neu bei Filme direkt hinter „Weiterschauen“ (HERO; ist das ausgeblendet,
    // ans Ende). Eine leere Liste ist eine Neuinstallation und nimmt unten die Enum-Reihenfolge.
    if (stored.isNotEmpty()) {
        val liste = result.toMutableList()
        if (HomeRow.G4K_FUSSBALL !in liste) liste.add(0, HomeRow.G4K_FUSSBALL)
        if (HomeRow.G4K_JETZT_TV !in liste) liste.add(liste.indexOf(HomeRow.G4K_FUSSBALL) + 1, HomeRow.G4K_JETZT_TV)
        if (HomeRow.G4K_NEU_FILME !in liste) {
            val hero = liste.indexOf(HomeRow.HERO)
            if (hero >= 0 && HomeRow.HERO !in hidden) liste.add(hero + 1, HomeRow.G4K_NEU_FILME) else liste.add(HomeRow.G4K_NEU_FILME)
        }
        result.clear()
        result += liste
    }
    HomeRow.entries.forEachIndexed { index, row ->
        if (row !in result) {
            val current = result.toMutableList()
            current.add(index.coerceAtMost(current.size), row)
            result.clear()
            result += current
        }
    }
    return result.toList()
}

private fun JSONArray?.toHomeRows(): List<HomeRow> {
    if (this == null) return emptyList()
    val out = ArrayList<HomeRow>(length())
    for (i in 0 until length()) {
        val row = runCatching { HomeRow.valueOf(optString(i)) }.getOrNull() ?: continue
        if (row !in out) out += row
    }
    return out
}
