package com.noop.ui

import com.noop.R
import androidx.annotation.StringRes
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// MARK: - The ONE range control the per-source pages share (W / M / 3M / 6M / 1Y / ALL)
//
// Apple Health and Mi Band each carry their own `RangeWindow` enum on the iOS side, but the two are
// identical: the same six windows, the same labels, the same captions and the same range names. Android
// keeps ONE, so a change to the range control cannot land on one page and miss the other.

internal enum class SourceRange(
    val days: Int?,
    @StringRes val labelRes: Int,
    @StringRes val captionRes: Int,
    @StringRes val nameRes: Int,
) {
    Week(7, R.string.l10n_lift_w_e2415cb7, R.string.l10n_apple_health_screen_s_7_days_ba6e98ec, R.string.l10n_apple_health_screen_week_c0ee32d8),
    Month(30, R.string.l10n_apple_health_screen_m_c63ae6dd, R.string.l10n_apple_health_screen_s_30_days_f1f09899, R.string.l10n_apple_health_screen_month_021710fa),
    Quarter(90, R.string.l10n_apple_health_screen_s_3m_6dce1630, R.string.l10n_apple_health_screen_s_90_days_52ad3549, R.string.l10n_apple_health_screen_s_3_months_b9e4e6ff),
    Half(180, R.string.l10n_apple_health_screen_s_6m_b78ebb44, R.string.l10n_apple_health_screen_s_180_days_c0af30e9, R.string.l10n_apple_health_screen_s_6_months_cd75b86f),
    Year(365, R.string.l10n_apple_health_screen_s_1y_28234cf2, R.string.l10n_apple_health_screen_s_365_days_acb50f1b, R.string.l10n_apple_health_screen_year_4ff0b153),
    All(null, R.string.l10n_apple_health_screen_all_6b42874e, R.string.l10n_apple_health_screen_all_time_85c78b60, R.string.explore_all_history);

    /** This range plus every larger range, ascending — the auto-widen search order. */
    val widening: List<SourceRange>
        get() = entries.dropWhile { it != this }
}

// MARK: - Value formatting (the two formatters every per-source page uses)

/**
 * The iOS twin's `intString(_:)`: a rounded integer with thousands grouping from 1,000 up ("6,073"),
 * grouped with Locale.US exactly as the twin's `NumberFormatter` does, so a translated app still shows
 * the same digits as the iPhone.
 */
internal fun groupedIntString(v: Double): String {
    val n = v.roundToInt()
    return if (abs(n) >= 1000) String.format(Locale.US, "%,d", n) else "$n"
}

/**
 * The iOS twin's `durationString(_:)`: whole minutes as "7h 12m", or "45m" under the hour.
 *
 * Returns a [DisplayText] rather than a resolved String: the caller decides where it is shown, so the
 * formatter stays pure (and testable) while the words stay translated — the catalog carries
 * "%lldh %lldm" and "%lldm" with real translations (ru "…ч …м", zh "…小时…分").
 */
internal fun durationMinutesText(minutes: Double): DisplayText {
    val total = minutes.roundToInt()
    val h = total / 60
    val m = total % 60
    return if (h > 0) {
        DisplayText.Resource(R.string.l10n_source_page_copy_lldh_lldm_05a73922, listOf(h, m))
    } else {
        DisplayText.Resource(R.string.l10n_source_page_copy_lldm_f8b297ab, listOf(m))
    }
}

// MARK: - Dates (the twin formats these with en_US_POSIX, so they read the same in every language)

/** The iOS twin's `spanFormatter` ("d MMM yyyy", en_US_POSIX): "14 Aug 2026" in every language. */
internal fun sourceSpanDay(day: String): String =
    runCatching { LocalDate.parse(day).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US)) }
        .getOrDefault(day)

/** The iOS twin's `asOfFormatter` ("d MMM", en_US_POSIX): "14 Aug" in every language. */
internal fun sourceShortDay(day: String): String =
    runCatching { LocalDate.parse(day).format(DateTimeFormatter.ofPattern("d MMM", Locale.US)) }
        .getOrDefault(day)

/**
 * The same "d MMM" for a stored epoch-second instant. The iOS twins' night stamp is a `Date` in the
 * DEVICE's timezone (unlike their day parsers, which are UTC), so this resolves in the local zone too.
 */
internal fun sourceShortDayFromEpochSeconds(epochSeconds: Long): String =
    Instant.ofEpochSecond(epochSeconds)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("d MMM", Locale.US))

// MARK: - The composed captions both per-source pages share

/** A tile's caption: "as of 14 Aug", the date formatted the way the iOS twins format it. */
internal fun sourceAsOfCaption(day: String): DisplayText =
    DisplayText.Resource(R.string.l10n_apple_health_screen_as_of_daedbd14, listOf(sourceShortDay(day)))

/**
 * The window-level caption: how many days the per-day rows span, flagging any auto-widen. Whole-phrase
 * variants per count, so a translator sees a complete sentence rather than a stitched plural.
 */
internal fun sourceRangeSummary(n: Int, widened: Boolean, range: SourceRange): DisplayText {
    val id = when {
        widened && n == 1 -> R.string.l10n_apple_health_screen_s_1_day_some_sparse_series_widened_005b7f59
        widened -> R.string.l10n_apple_health_screen_lld_days_some_sparse_series_widened_30e17ab4
        n == 1 -> R.string.l10n_apple_health_screen_s_1_day_dbc46f50
        else -> R.string.l10n_apple_health_screen_lld_days_5a3d1088
    }
    return DisplayText.Resource(id, listOf(n, DisplayText.Resource(range.nameRes)))
}

/** A chart card's subtitle: "N readings · <range>", flagging an auto-widen when it happened. */
internal fun sourceRangeNote(n: Int, widened: Boolean, range: SourceRange): DisplayText {
    val id = when {
        widened && n == 1 -> R.string.l10n_apple_health_screen_s_1_reading_sparse_widened_to_c686d83b
        widened -> R.string.l10n_apple_health_screen_lld_readings_sparse_widened_to_a404132d
        n == 1 -> R.string.l10n_apple_health_screen_s_1_reading_f666b026
        else -> R.string.l10n_apple_health_screen_lld_readings_b7cee48b
    }
    return DisplayText.Resource(id, listOf(n, DisplayText.Resource(range.nameRes)))
}

/**
 * The page header's subtitle: the windowed span of the per-day rows ("30 days · 5 Jul 2026 → 2 Oct
 * 2026"), or — with no per-day rows at all — [fallbackRes], the page's own sentence naming what it reads
 * and where it stays. iOS builds that sentence by interpolating its device-noun phrase into a `%@`;
 * Android has no such runtime phrase, so the noun is baked into each locale's value and no argument is
 * passed here.
 *
 * The count is iOS's, singular-branch-free for this one: `spanSubtitle` in both twins writes
 * `"\(rows.count) days · \(span)"` with no `1 day` variant, so one row reads "1 days".
 */
internal fun sourceSpanSubtitle(windowedDays: List<String>, @StringRes fallbackRes: Int): DisplayText {
    val first = windowedDays.firstOrNull()
    val last = windowedDays.lastOrNull()
    if (first == null || last == null) return DisplayText.Resource(fallbackRes)
    val lo = sourceSpanDay(first)
    val hi = sourceSpanDay(last)
    val span = if (lo == hi) lo else "$lo → $hi"
    return DisplayText.Resource(R.string.l10n_apple_health_screen_lld_days_5a3d1088, listOf(windowedDays.size, span))
}
