package com.noop.ui

import com.noop.R
import androidx.annotation.StringRes
import com.noop.data.WhoopRepository
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

// MARK: - The Explore window set and its captions (pure: no `uiString`, so a JVM test can pin them)
//
// Split out of TrendsExploreScreen.kt deliberately. That file holds top-level metric descriptors built with
// `uiString`, so loading ANY of its statics outside the app throws `NoopApplication is not attached` — the
// pure window/caption logic lives here instead, where a unit test can reach it.

// MARK: - Window range (W / 2W / 3W / M / 3M / 6M / 1Y / ALL)

/**
 * The eight windows the iOS twin offers (`MetricExplorerView.ExploreRange`), each carrying its own
 * localised pill label and its own localised NAME — the name is what the captions read ("90 readings ·
 * quarter"), so a hardcoded English name leaked English into an otherwise translated page.
 */
internal enum class ExploreRange(
    val days: Int?,
    @StringRes val labelRes: Int,
    @StringRes val nameRes: Int,
) {
    Week(7, R.string.l10n_lift_w_e2415cb7, R.string.l10n_apple_health_screen_week_c0ee32d8),
    TwoWeeks(14, R.string.l10n_trends_explore_screen_s_2w_5b6401e2, R.string.today_range_2_weeks),
    ThreeWeeks(21, R.string.l10n_trends_explore_screen_s_3w_501d3563, R.string.l10n_trends_explore_screen_s_3_weeks_4ec1986e),
    Month(30, R.string.l10n_apple_health_screen_m_c63ae6dd, R.string.l10n_apple_health_screen_month_021710fa),
    Quarter(90, R.string.l10n_apple_health_screen_s_3m_6dce1630, R.string.l10n_trends_explore_screen_quarter_7fa1f943),
    Half(180, R.string.l10n_apple_health_screen_s_6m_b78ebb44, R.string.l10n_apple_health_screen_s_6_months_cd75b86f),
    Year(365, R.string.l10n_apple_health_screen_s_1y_28234cf2, R.string.l10n_apple_health_screen_year_4ff0b153),
    All(null, R.string.l10n_apple_health_screen_all_6b42874e, R.string.l10n_trends_explore_screen_all_time_5ae5c422);

    /**
     * This range plus every larger range, ascending — the auto-widen search order when the selected window
     * holds zero points. Deliberately the iOS twin's own list, which omits the two short week windows: for
     * those it widens straight to ALL (`guard let i = order.firstIndex(of: self) else { return [.all] }`).
     * A window can only come back empty through an unreadable anchor day, so this is a defensive path on
     * both platforms, and matching it keeps the two captions word-for-word identical if it ever fires.
     */
    val widening: List<ExploreRange>
        get() {
            val order = listOf(Week, Month, Quarter, Half, Year, All)
            val index = order.indexOf(this)
            return if (index < 0) listOf(All) else order.subList(index, order.size)
        }

    /** True when this range can reach PAST the bounded dashboard cap (WhoopRepository.RECENT_DAYS_CAP),
     *  so the built-in series must come from the UNCAPPED full history rather than the bounded `recentDays`
     *  flow to avoid silently truncating a deep import (#797 Explore-'All' follow-up). Only the open-ended
     *  'All' range ([days] == null) does; every fixed range is ≤ the cap, so it keeps using the cheap flow. */
    val reachesDeep: Boolean get() = days == null || days > WhoopRepository.RECENT_DAYS_CAP
}

// MARK: - Window captions (the iOS twin's rangeCaption / heroChart subtitle / asOf clauses)

/** The iOS twin's `longDate(_:)`: "d MMM yyyy" in the ACTIVE app language, resolved in UTC. */
internal fun exploreLongDay(day: String): String =
    runCatching {
        java.time.LocalDate.parse(day)
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault()))
    }.getOrDefault(day)

/**
 * "N readings · <range>" under the pills, flagging an auto-widen — whole-phrase variants per count, so a
 * translator never sees a stitched plural (iOS `rangeCaption`). An empty window reads "—", not "0 readings".
 */
internal fun exploreRangeCaption(n: Int, fellBack: Boolean, range: ExploreRange, loaded: Boolean): DisplayText {
    if (!loaded || n == 0) return DisplayText.Dynamic("—")
    val id = when {
        fellBack && n == 1 -> R.string.l10n_apple_health_screen_s_1_reading_sparse_widened_to_c686d83b
        fellBack -> R.string.l10n_apple_health_screen_lld_readings_sparse_widened_to_a404132d
        n == 1 -> R.string.l10n_apple_health_screen_s_1_reading_f666b026
        else -> R.string.l10n_apple_health_screen_lld_readings_b7cee48b
    }
    return DisplayText.Resource(id, listOf(n, DisplayText.Resource(range.nameRes)))
}

/** The hero card's subtitle: "N readings · <range>", or the widened variant with the count trailing. */
internal fun exploreHeroSubtitle(n: Int, fellBack: Boolean, range: ExploreRange): DisplayText =
    if (fellBack) {
        DisplayText.Resource(
            R.string.l10n_trends_explore_screen_sparse_widened_to_lld_readings_22d2aee1,
            listOf(DisplayText.Resource(range.nameRes), n),
        )
    } else {
        DisplayText.Resource(
            if (n == 1) R.string.l10n_apple_health_screen_s_1_reading_f666b026
            else R.string.l10n_apple_health_screen_lld_readings_b7cee48b,
            listOf(n, DisplayText.Resource(range.nameRes)),
        )
    }

/** The hero card's read-out line: "as of 2 Oct 2026" — the day in the active language, or "—". */
internal fun exploreAsOfCaption(day: String?): DisplayText =
    if (day == null) DisplayText.Dynamic("—")
    else DisplayText.Resource(R.string.l10n_apple_health_screen_as_of_daedbd14, listOf(exploreLongDay(day)))
