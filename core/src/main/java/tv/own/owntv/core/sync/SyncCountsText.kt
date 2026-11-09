package tv.own.owntv.core.sync

import android.content.res.Resources
import android.icu.text.CompactDecimalFormat
import tv.own.owntv.core.R

/**
 * The words that go with an import's numbers: how much arrived, what went wrong on the way, and what
 * is still queued. Both apps show the same sentences over the same [SyncCounts], so they are written
 * once here and each app decides only where to put them.
 */

/** "12K channels · 4.1K movies" — the parts a caller wants, joined. Empty when nothing arrived. */
fun SyncCounts.breakdownText(res: Resources, includeEpg: Boolean = false): String {
    val parts = buildList {
        if (channels > 0) add(res.getQuantityString(R.plurals.sync_count_channels, channels, zahlText(res, channels)))
        if (movies > 0) add(res.getQuantityString(R.plurals.sync_count_movies, movies, zahlText(res, movies)))
        if (series > 0) add(res.getQuantityString(R.plurals.sync_count_series, series, zahlText(res, series)))
        if (includeEpg && epg > 0) add(res.getQuantityString(R.plurals.sync_count_epg, epg, zahlText(res, epg)))
    }
    return parts.joinToString(res.getString(R.string.sync_counts_separator))
}

/** The one line under "All set!". A sync that reports no numbers still succeeded, and says so. */
fun SyncCounts.summaryText(res: Resources, includeEpg: Boolean = false): String {
    val breakdown = breakdownText(res, includeEpg)
    return if (breakdown.isBlank()) {
        res.getString(R.string.sync_counts_success)
    } else {
        res.getString(R.string.sync_counts_synced, breakdown)
    }
}

/** The live tally while the import runs — only the phases that are actually running. */
fun SyncProgressCounts.displayText(res: Resources): String {
    val parts = buildList {
        if (liveActive && live > 0) add(res.getQuantityString(R.plurals.sync_count_channels, live, zahlText(res, live)))
        if (moviesActive && movies > 0) add(res.getQuantityString(R.plurals.sync_count_movies, movies, zahlText(res, movies)))
        if (seriesActive && series > 0) add(res.getQuantityString(R.plurals.sync_count_series, series, zahlText(res, series)))
    }
    return parts.joinToString(res.getString(R.string.sync_counts_separator))
}

/**
 * German4K 3.2.1 (p-17, Schalter zahl_voll): Einrichtung/Import zeigen die volle Zahl mit Tausendertrennung („3.600 Filme“).
 * Die ICU-Kurzform hängt an der ICU-Version des Geräts: Android 9 schrieb „3,6 Tsd. Filme“, Android 16 „3600 Filme“.
 * Aus = Kurzform wie bisher. Die Sync-Pille der Shell bleibt kurz (eigener Aufruf von [compactCount]).
 */
fun zahlText(res: Resources, value: Int): String =
    if (tv.own.owntv.core.german4k.German4kFeatures.an(tv.own.owntv.core.german4k.German4kSchalter.ZAHL_VOLL)) {
        vollZahl(res.configuration.locales[0] ?: java.util.Locale.getDefault(), value)
    } else {
        compactCount(res, value)
    }

/** German4K 3.2.1 (p-17): volle Zahl mit Tausendertrennung der Sprache (de „3.600“, en „3,600“). */
fun vollZahl(locale: java.util.Locale, value: Int): String =
    java.text.NumberFormat.getIntegerInstance(locale).apply { isGroupingUsed = true }.format(value.toLong())

/** Android ICU compact notation keeps the pill readable without losing the raw plural quantity. */
fun compactCount(res: Resources, value: Int): String {
    val locale = res.configuration.locales[0] ?: java.util.Locale.getDefault()
    return CompactDecimalFormat.getInstance(locale, CompactDecimalFormat.CompactStyle.SHORT)
        .format(value.toLong())
}

fun SyncProgressDisplay.primaryText(res: Resources): String = when (phase) {
    SyncProgressPhase.PREPARING -> res.getString(R.string.sync_progress_preparing)
    SyncProgressPhase.CONNECTING -> res.getString(R.string.sync_progress_connecting)
    // Said plainly, with the position in it: this runs before a single row is written and can take
    // two minutes, and a silent spinner for that long reads as a hung app.
    SyncProgressPhase.MEASURING_CONNECTIONS -> res.getString(
        R.string.settings_sources_probe_running,
        counts?.measuringStream ?: 1,
        counts?.measuringOf ?: 1,
    )
    SyncProgressPhase.SYNCING ->
        counts?.displayText(res).orEmpty().ifBlank { res.getString(R.string.sync_progress_preparing) }
}

fun SyncProgressDisplay.detailText(res: Resources): String = when (phase) {
    SyncProgressPhase.SYNCING -> res.getString(R.string.sync_progress_syncing)
    SyncProgressPhase.MEASURING_CONNECTIONS, SyncProgressPhase.PREPARING, SyncProgressPhase.CONNECTING ->
        res.getString(R.string.sync_progress_connecting)
}

fun SyncWarning.labelText(res: Resources): String = when (phase.trim().uppercase()) {
    SyncPhase.LIVE.name -> res.getString(R.string.sync_phase_live)
    SyncPhase.MOVIES.name -> res.getString(R.string.sync_phase_movies)
    SyncPhase.SERIES.name -> res.getString(R.string.sync_phase_series)
    else -> phase.replaceFirstChar { it.uppercase() }
}

/** Everything the sync survived but wants to mention, as one sentence. Null when it was clean. */
fun List<SyncWarning>.warningText(res: Resources): String? {
    if (isEmpty()) return null
    val rendered = ArrayList<String>(size)
    for (warning in this) {
        rendered += when (val kind = warning.kind) {
            SyncWarningKind.PAGE_FAILURE ->
                res.getQuantityString(R.plurals.sync_warning_page_failures, warning.count, warning.count)
            SyncWarningKind.GENERIC -> if (warning.message.isBlank()) {
                warning.labelText(res)
            } else {
                res.getString(R.string.sync_warning_phase_error, warning.labelText(res), warning.message)
            }
            is SyncWarningKind.CATALOG_SHRINK -> res.getQuantityString(
                R.plurals.sync_warning_catalog_shrink,
                kind.stored,
                kind.stored,
                kind.percentFewer,
            )
        }
    }
    return res.getString(
        R.string.sync_import_warnings,
        rendered.joinToString(res.getString(R.string.sync_counts_separator)),
    )
}

fun SyncContentTypes.remainderText(res: Resources): String? =
    if (!hasAny) null else res.getString(R.string.sync_remainder_note)
