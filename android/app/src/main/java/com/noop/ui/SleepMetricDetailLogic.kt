package com.noop.ui

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.Color
import com.noop.R
import com.noop.analytics.RestScorer
import com.noop.analytics.SleepDebt
import com.noop.data.DailyMetric
import java.time.LocalDate
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

internal enum class SleepMetricRange(@StringRes val labelRes: Int, val days: Long?) {
    WEEK(R.string.l10n_lift_w_e2415cb7, 7),
    MONTH(R.string.l10n_apple_health_screen_m_c63ae6dd, 30),
    THREE_MONTH(R.string.l10n_apple_health_screen_s_3m_6dce1630, 90),
    SIX_MONTH(R.string.l10n_apple_health_screen_s_6m_b78ebb44, 180),
    YEAR(R.string.l10n_apple_health_screen_s_1y_28234cf2, 365),
    ALL(R.string.l10n_apple_health_screen_all_6b42874e, null),
}

internal data class SleepMetricSpec(
    /** A [DisplayText], not a resolved label: [sleepMetricSpec] is pure and pinned by tests with no Application. */
    val title: DisplayText,
    val unit: String,
    val color: Color,
    val format: (Double) -> String,
)

internal fun sleepMetricSpec(key: String): SleepMetricSpec = when (key) {
    "performance"     -> SleepMetricSpec(DisplayText.Resource(R.string.trends_rest), "%", Palette.restColor) { "${it.roundToInt()}" }
    "efficiency"      -> SleepMetricSpec(DisplayText.Resource(R.string.l10n_trends_explore_screen_sleep_efficiency_b4b5c293), "%", Palette.statusPositive) { "${it.roundToInt()}" }
    "consistency"     -> SleepMetricSpec(DisplayText.Resource(R.string.l10n_sleep_screen_consistency_0ea7b95e), "%", Palette.metricCyan) { "${it.roundToInt()}" }
    "hours_vs_needed" -> SleepMetricSpec(DisplayText.Resource(R.string.l10n_sleep_screen_hours_vs_needed_500a0aca), "%", Palette.restColor) { "${it.roundToInt()}" }
    "restorative"     -> SleepMetricSpec(DisplayText.Resource(R.string.l10n_sleep_metric_cards_ui_restorative_adb13db6), "%", Palette.sleepREM) { "${it.roundToInt()}" }
    "respiratory"     -> SleepMetricSpec(DisplayText.Resource(R.string.l10n_health_screen_respiratory_rate_3fbb532f), "rpm", Palette.metricPurple) { String.format(Locale.US, "%.1f", it) }
    "sleep_debt"      -> SleepMetricSpec(DisplayText.Resource(R.string.l10n_sleep_screen_sleep_debt_3aec7d9c), "min", Palette.metricRose) { "${it.roundToInt()}" }   // #691: minutes, not decimal hours
    else              -> SleepMetricSpec(DisplayText.Dynamic(key), "", Palette.accent) { "${it.roundToInt()}" }
}

internal fun buildSleepMetricPoints(
    days: List<DailyMetric>,
    key: String,
    imported: ImportedSleepSeries = ImportedSleepSeries(),
    napSleepMinByDay: Map<String, Double> = emptyMap(),
): List<Pair<String, Double>> {
    val needMin = max(450.0, days.mapNotNull { it.totalSleepMin?.takeIf { m -> m > 0.0 } }.average().let { if (it.isNaN()) 480.0 else it })
    if (key == "sleep_debt") {
        val debtNeedMin = RestScorer.personalizedNeedHours(
            days.mapNotNull { it.totalSleepMin?.let { minutes -> minutes / 60.0 } }, null,
        ) * 60.0
        return SleepDebt.debtSeries(
            series = days.map { day ->
                day.day to SleepDebt.creditedSleepMin(
                    day.totalSleepMin,
                    napSleepMinByDay[day.day] ?: 0.0,
                )
            },
            needHours = debtNeedMin / 60.0,
            importedDebtMin = imported.debtMin,
        )
    }
    return days.mapNotNull { d ->
        val v: Double? = when (key) {
            // The Rest detail graph reads the REAL resolved Rest composite per day — the same single
            // source of truth the Today Rest score uses (RestScorer.restFromDaily, the composite the
            // sleep_performance series carries) — not a local hours-vs-need approximation. Keeps the
            // graph and the score in agreement. (#614 follow-up)
            "performance" -> RestScorer.restFromDaily(d)?.takeIf { it in 0.0..100.0 }
            "efficiency"  -> d.efficiency?.let { if (it <= 1.0) it * 100.0 else it }
            "consistency" -> {
                val idx = days.indexOf(d)
                val lo = max(0, idx - 13)
                val window = days.subList(lo, idx + 1).mapNotNull { it.totalSleepMin?.takeIf { m -> m > 0.0 } }
                if (window.size < 3) null else {
                    val m = window.average()
                    val sd = kotlin.math.sqrt(window.sumOf { (it - m) * (it - m) } / window.size)
                    (100.0 * (1.0 - sd / 90.0)).coerceIn(0.0, 100.0)
                }
            }
            "hours_vs_needed" -> d.totalSleepMin?.takeIf { it > 0.0 }?.let { minOf(100.0, it / needMin * 100.0) }
            "restorative" -> {
                val dp = d.deepMin ?: return@mapNotNull null
                val rm = d.remMin ?: return@mapNotNull null
                val sl = d.totalSleepMin ?: return@mapNotNull null
                if (sl > 0.0) (dp + rm) / sl * 100.0 else null
            }
            "respiratory" -> d.respRateBpm
            else          -> null
        }
        v?.takeIf { it.isFinite() }?.let { d.day to it }
    }
}

internal fun filterSleepMetricPoints(
    points: List<Pair<String, Double>>,
    range: SleepMetricRange,
): List<Pair<String, Double>> {
    val windowDays = range.days ?: return points
    val latestDate = points.lastOrNull()?.first?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        ?: return points.takeLast(windowDays.toInt())
    val cutoff = latestDate.minusDays(windowDays - 1)
    val filtered = points.filter { (day, _) ->
        runCatching { LocalDate.parse(day) }.getOrNull()?.let { !it.isBefore(cutoff) } ?: false
    }
    return filtered.ifEmpty { points.takeLast(windowDays.toInt()) }
}
