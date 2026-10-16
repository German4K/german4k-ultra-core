package tv.own.owntv.core.content

import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.first
import tv.own.owntv.core.customize.CustomizationStore
import tv.own.owntv.core.customize.CustomizeKeys
import tv.own.owntv.core.customize.SectionCustomizations
import tv.own.owntv.core.database.dao.CategoryDao
import tv.own.owntv.core.database.dao.ChannelDao
import tv.own.owntv.core.database.dao.ChannelSearchResult
import tv.own.owntv.core.database.dao.MovieDao
import tv.own.owntv.core.database.dao.ProfileDao
import tv.own.owntv.core.database.dao.SeriesDao
import tv.own.owntv.core.database.entity.MovieEntity
import tv.own.owntv.core.database.entity.SeriesEntity
import tv.own.owntv.core.model.MediaType
import tv.own.owntv.core.repository.ActiveProfileSources

/** Combined results of a global query (each list bounded). */
@Immutable
data class SearchResults(
    val channels: List<ChannelSearchResult> = emptyList(),
    val movies: List<MovieEntity> = emptyList(),
    val series: List<SeriesEntity> = emptyList(),
) {
    val isEmpty: Boolean get() = channels.isEmpty() && movies.isEmpty() && series.isEmpty()
}

/** The curated lists offered when nothing has been typed yet. */
enum class SearchIntent {
    CONTINUE,
    UNWATCHED,
    CHANNELS,
}

/**
 * Searching a profile's channels, films and shows at once.
 *
 * The queries themselves are the DAOs'; what lives here is everything that decides *which* of their
 * rows a user may see — the FTS expression, the hidden items, the hidden categories, a kids
 * profile's adult filter, and the renames a user made in Customize. Two apps searching the same
 * database have to agree on all five, so it is written once rather than in each app's search screen.
 */
class SearchReader(
    private val channelDao: ChannelDao,
    private val categoryDao: CategoryDao,
    private val movieDao: MovieDao,
    private val seriesDao: SeriesDao,
    private val profileDao: ProfileDao,
    private val customize: CustomizationStore,
) {

    /**
     * Results for a typed query, already filtered and renamed for [profileId].
     *
     * [sources] is the profile's active playlists, per section, so a section switched Off never
     * surfaces. Returns nothing at all when the profile has no sources — there is nothing to search.
     */
    suspend fun search(
        profileId: Long,
        sources: ActiveProfileSources,
        query: String,
        limit: Int = SEARCH_LIMIT,
    ): SearchResults {
        if (profileId < 0 || !sources.hasAny) return SearchResults()
        val fts = ftsQuery(query)
        val custLive = customize.observe(profileId, MediaType.LIVE).first()
        val custMovie = customize.observe(profileId, MediaType.MOVIE).first()
        val custSeries = customize.observe(profileId, MediaType.SERIES).first()
        val isKids = profileDao.getById(profileId)?.isKids == true
        val hiddenLiveCats = hiddenCategoryIds(sources.liveSourceIds, MediaType.LIVE, custLive, isKids)
        val hiddenMovieCats = hiddenCategoryIds(sources.movieSourceIds, MediaType.MOVIE, custMovie, isKids)
        val hiddenSeriesCats = hiddenCategoryIds(sources.seriesSourceIds, MediaType.SERIES, custSeries, isKids)
        return SearchResults(
            channels = if (sources.liveSourceIds.isEmpty()) emptyList() else
                (
                    if (fts != null) channelDao.searchListDetailedFts(fts, sources.liveSourceIds, limit)
                    else channelDao.searchListDetailed(query, sources.liveSourceIds, limit)
                    )
                    .filter {
                        CustomizeKeys.channel(it.channel) !in custLive.hiddenItems &&
                            (it.channel.categoryId == null || it.channel.categoryId !in hiddenLiveCats)
                    }
                    .map { row ->
                        custLive.itemNames[CustomizeKeys.channel(row.channel)]
                            ?.let { row.copy(channel = row.channel.copy(name = it)) } ?: row
                    },
            movies = if (sources.movieSourceIds.isEmpty()) emptyList() else
                (
                    if (fts != null) movieDao.searchListFts(fts, sources.movieSourceIds, limit)
                    else movieDao.searchList(query, sources.movieSourceIds, limit)
                    )
                    .filter {
                        CustomizeKeys.movie(it) !in custMovie.hiddenItems &&
                            (it.categoryId == null || it.categoryId !in hiddenMovieCats)
                    }
                    .map { m -> custMovie.itemNames[CustomizeKeys.movie(m)]?.let { m.copy(name = it) } ?: m },
            series = if (sources.seriesSourceIds.isEmpty()) emptyList() else
                (
                    if (fts != null) seriesDao.searchListFts(fts, sources.seriesSourceIds, limit)
                    else seriesDao.searchList(query, sources.seriesSourceIds, limit)
                    )
                    .filter {
                        CustomizeKeys.series(it) !in custSeries.hiddenItems &&
                            (it.categoryId == null || it.categoryId !in hiddenSeriesCats)
                    }
                    .map { s -> custSeries.itemNames[CustomizeKeys.series(s)]?.let { s.copy(name = it) } ?: s },
        )
    }

    /** One of the curated lists shown before anything is typed. Bounded, and already source-filtered. */
    suspend fun curated(
        profileId: Long,
        sources: ActiveProfileSources,
        intent: SearchIntent,
        limit: Int = LIMIT,
    ): SearchResults {
        if (profileId < 0 || !sources.hasAny) return SearchResults()
        return when (intent) {
            SearchIntent.CONTINUE -> SearchResults(
                channels = channelDao.recentlyWatched(profileId, limit).first()
                    .filter { it.sourceId in sources.liveSourceIds }
                    .map { ChannelSearchResult(it, null) },
                movies = if (sources.movieSourceIds.isEmpty()) emptyList()
                else movieDao.recentlyWatchedSnapshot(profileId, sources.movieSourceIds, limit),
                series = if (sources.seriesSourceIds.isEmpty()) emptyList()
                else seriesDao.recentlyWatchedSnapshot(profileId, sources.seriesSourceIds, limit),
            )
            SearchIntent.UNWATCHED -> SearchResults(
                movies = if (sources.movieSourceIds.isEmpty()) emptyList()
                else movieDao.unwatchedFavorites(profileId, sources.movieSourceIds, limit),
                series = if (sources.seriesSourceIds.isEmpty()) emptyList()
                else seriesDao.unwatchedFavorites(profileId, sources.seriesSourceIds, limit),
            )
            SearchIntent.CHANNELS -> SearchResults(
                channels = channelDao.favoritesListAlpha(profileId).first()
                    .filter { it.sourceId in sources.liveSourceIds }
                    .take(limit)
                    .map { ChannelSearchResult(it, null) },
            )
        }
    }

    /** DB ids of this profile's hidden categories for [type] (so hidden groups drop out of search too). */
    private suspend fun hiddenCategoryIds(
        sourceIds: List<Long>,
        type: MediaType,
        cust: SectionCustomizations,
        isKidsProfile: Boolean,
    ): Set<Long> {
        // German4K 3.2 (F1): Erwachsenes erscheint in der Suche erst nach einer PIN-Eingabe in dieser Sitzung.
        val erwachsenAus = isKidsProfile || !German4kErwachsenSitzung.frei
        if (cust.hiddenCategories.isEmpty() && !erwachsenAus) return emptySet()
        return AdultCategoryClassifier.hiddenCategoryIds(
            categoryDao.observe(sourceIds, type).first(),
            cust.hiddenCategories,
            isKidsProfile,
            hideAdult = erwachsenAus,
        )
    }

    companion object {
        /** How many rows of each kind a curated list returns. */
        const val LIMIT = 40

        /** German4K 3.1.1: How many rows of each kind a typed search returns (was 40 — „ORF" alone filled it). */
        const val SEARCH_LIMIT = 100

        private val WHITESPACE = Regex("\\s+")

        /** Everything the FTS4 "simple" tokenizer treats as a separator (it splits on ASCII punctuation). */
        private val NON_TOKEN = Regex("[^\\p{L}\\p{N}]+")

        /** A letter↔digit boundary inside one word ("orf|1", "sky|1", "s|01"). */
        private val LETTER_DIGIT = Regex("(?<=\\p{L})(?=\\p{N})|(?<=\\p{N})(?=\\p{L})")

        /**
         * A sanitized FTS4 MATCH expression. Each whitespace-separated word is split exactly like the FTS
         * "simple" tokenizer splits the indexed names — anything that is not a letter or digit separates —
         * and becomes prefix terms, implicit AND between words ("harry pot" → "harry* pot*").
         *
         * German4K 3.1.1:
         *  - A word the user wrote with punctuation inside ("O.C.", "SAT.1") becomes a PHRASE of its parts,
         *    `"o* c*"`: the index holds "The O.C." as the adjacent tokens "o" and "c". Separate terms
         *    (`o* c*`) would also hit every "Oct 10 … Colgate" event and bury the series (emulator, 10.10.).
         *  - A word that switches between letters and digits also matches the spaced form, so "ORF1"
         *    finds both "ORF1 HD" and "ORF 1 HD": `(orf1* OR "orf 1*")`. The parentheses make this safe
         *    on every Android: under the standard query syntax (Android's build) and the enhanced one
         *    alike, `(a OR b) c` means "(a or b) and c" — measured against SQLite 3.19 (Android 9).
         *  - Everything is lower-cased, so typed "or"/"and"/"not"/"near" never become operators.
         *
         * Null when nothing tokenizable remains (symbols-only input) — the caller then falls back to
         * the substring LIKE queries. Prefix terms match word starts rather than mid-word substrings,
         * which is the accepted trade-off for an index-served search over ~220k rows per keystroke.
         */
        fun ftsQuery(query: String): String? {
            val terms = query.lowercase(java.util.Locale.ROOT)
                .split(WHITESPACE)
                .mapNotNull { word ->
                    val parts = word.split(NON_TOKEN).filter { it.isNotEmpty() }
                    when {
                        parts.isEmpty() -> null
                        parts.size > 1 -> "\"${parts.joinToString(" ") { "$it*" }}\""
                        else -> {
                            val t = parts[0]
                            val split = t.split(LETTER_DIGIT).filter { it.isNotEmpty() }
                            if (split.size < 2) "$t*" else "($t* OR \"${split.joinToString(" ")}*\")"
                        }
                    }
                }
            return terms.takeIf { it.isNotEmpty() }?.joinToString(" ")
        }
    }
}

/** True when the profile has at least one playlist a search could look in. */
private val ActiveProfileSources.hasAny: Boolean
    get() = liveSourceIds.isNotEmpty() || movieSourceIds.isNotEmpty() || seriesSourceIds.isNotEmpty()
