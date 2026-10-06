package com.noop.ui

import com.noop.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.noop.data.AppleDaily
import com.noop.data.MetricSeriesRow
import java.util.Locale
import kotlin.math.roundToInt

// MARK: - Apple Health (per-source page)
//
// Faithful port of the macOS AppleHealthView. The macOS screen is a Vitaltrends-style,
// instrument-grade page driven by ONE range control (W / M / 3M / 6M / 1Y / ALL): a
// uniform grid of fixed-height StatTiles over every metric, then four ChartCard
// sections — Heart & Vitals, Activity & Energy, Body Composition, Sleep — each with an
// avg / min / max / points footer.
//
// All history is loaded once; the pill control windows it client-side, RELATIVE TO THE
// LATEST recorded day (not "now"). Per the data contract a series may be SPARSE
// (weight / body-fat are weekly): if the selected window holds >= 1 point we show THAT
// window; only when it holds ZERO do we auto-widen to the smallest larger range that
// does — so switching ranges stays visibly distinct and only sparse windows widen.
//
// Data sources on Android:
//   - The appleDaily table (steps, active_kcal via activeKcal, vo2max, weight) under
//     deviceId "apple-health".
//   - The generic metricSeries long-format table for everything Apple writes that isn't
//     a column on appleDaily (resting_hr, hrv, spo2, resp_rate, asleep_min, body_fat,
//     lean_mass, bmi), also under deviceId "apple-health".
//
// The Apple Health *import* runs on-device now: pick an Apple Health export .zip in
// Data Sources and it backfills these tables. This page just reads the resulting cache.
// When nothing has been imported we say so (no faked data, no generic "coming soon").
//
// COPY PARITY: every label, caption and range name resolves through a resource whose English is the iOS
// String Catalog key verbatim, so a translated iPhone and a translated Android show the same words. The
// composed sentences (range summary, card note, header span) go through [DisplayText] so a pure helper can
// choose one without an Application, and their arguments nest the range's own localised name. The dates
// these captions carry are formatted with Locale.US, matching the iOS twin's `en_US_POSIX` formatters —
// that page prints "14 Aug" in every language, so Android must not localise the month name here.
//
// The range control, tiles, chart cards and captions themselves are the SHARED components in
// SourcePageCopy.kt / SourcePageUi.kt, which the Mi Band page draws too — the iOS twins each carry their
// own copy of that chrome, and Android keeps one so the two pages cannot drift apart.

private const val APPLE_DEVICE = "apple-health"

/** The two Apple Health source spellings a workout row may carry (iOS `WorkoutSource.isAppleHealth`). */
private val APPLE_WORKOUT_SOURCES = setOf("apple-health", "apple_health")

// MARK: - A loaded series point (day string + value), oldest first.

internal data class HealthPoint(val day: String, val value: Double)

/**
 * Slice the (ascending-by-day) series to a window taken RELATIVE TO THE LATEST point — the shared
 * day-distance window ([windowByDays]), which is what the iOS twin's `slice(_:_:)` computes.
 */
internal fun List<HealthPoint>.windowFor(range: SourceRange): List<HealthPoint> =
    windowByDays(range.days) { it.day }

/**
 * AppleDaily rows trimmed to the active window, taken RELATIVE TO THE LATEST recorded day rather than
 * "now" (`computeWindowedRows` in the iOS twin). Drives both the header span and the range caption.
 */
internal fun appleHealthWindowedRows(rows: List<AppleDaily>, range: SourceRange): List<AppleDaily> =
    rows.windowByDays(range.days) { it.day }

/** The page header's subtitle: the windowed per-day span, or the sentence naming what this page reads. */
internal fun appleHealthSpanSubtitle(rows: List<AppleDaily>, range: SourceRange): DisplayText =
    sourceSpanSubtitle(
        appleHealthWindowedRows(rows, range).map { it.day },
        R.string.l10n_apple_health_screen_steps_heart_sleep_body_composition_and_v_672ccc32,
    )

// MARK: - Loaded model: every series this page pulls, keyed by metric.

internal class AppleData(
    val rows: List<AppleDaily>,
    val series: Map<String, List<HealthPoint>>,
    /** Apple-logged workouts, the count the iOS twin's own `Workouts` tile carries. */
    val workoutCount: Int,
) {
    fun raw(key: String): List<HealthPoint> = series[key] ?: emptyList()

    /** True if ANY per-day row or any series holds data (drives the empty state). */
    val hasAnyData: Boolean
        get() = rows.isNotEmpty() || series.values.any { it.isNotEmpty() }
}

/** A key's resolved (possibly auto-widened) window in the active range. */
internal data class Resolved(
    val requested: SourceRange,
    val effective: SourceRange,
    val rows: List<HealthPoint>,
) {
    val fellBack: Boolean get() = effective != requested
}

/**
 * The range actually shown for a key: the SELECTED range whenever its window holds
 * >= 1 point, otherwise the smallest LARGER range that does. Empty series resolve to
 * the requested range (so the card reads "0 readings · <range>", not a false widen).
 */
internal fun resolve(series: List<HealthPoint>, requested: SourceRange): Resolved {
    if (series.isEmpty()) return Resolved(requested, requested, emptyList())
    val eff = requested.widening.firstOrNull { series.windowFor(it).isNotEmpty() } ?: SourceRange.All
    return Resolved(requested, eff, series.windowFor(eff))
}

// MARK: - Screen

@Composable
fun AppleHealthScreen(vm: AppViewModel) {
    var loaded by remember { mutableStateOf(false) }
    var data by remember { mutableStateOf(AppleData(emptyList(), emptyMap(), 0)) }
    var range by remember { mutableStateOf(SourceRange.Quarter) }

    // Load ALL history once (range windowing is client-side). The appleDaily table
    // supplies steps / active_kcal / vo2max / weight directly; everything else comes
    // from the generic metricSeries long-format table under the apple-health device.
    LaunchedEffect(Unit) {
        val rows: List<AppleDaily> = runCatching {
            vm.repo.appleDaily(APPLE_DEVICE, "0000-00-00", "9999-99-99")
        }.getOrDefault(emptyList()).sortedBy { it.day }

        // Series we draw straight off appleDaily columns (no metricSeries round-trip).
        val series = linkedMapOf<String, List<HealthPoint>>()
        series["steps"] = rows.mapNotNull { r -> r.steps?.let { HealthPoint(r.day, it.toDouble()) } }
        series["active_kcal"] = rows.mapNotNull { r -> r.activeKcal?.let { HealthPoint(r.day, it) } }
        series["vo2max"] = rows.mapNotNull { r -> r.vo2max?.let { HealthPoint(r.day, it) } }
        series["weight"] = rows.mapNotNull { r -> r.weightKg?.let { HealthPoint(r.day, it) } }

        // Everything Apple writes that isn't an appleDaily column lives in metricSeries.
        val seriesKeys = listOf(
            "resting_hr", "hrv", "spo2", "resp_rate", "asleep_min",
            "body_fat", "lean_mass", "bmi",
        )
        for (key in seriesKeys) {
            val pts = runCatching {
                vm.repo.metricSeries(APPLE_DEVICE, key, "0000-00-00", "9999-99-99")
            }.getOrDefault(emptyList<MetricSeriesRow>())
                .sortedBy { it.day }
                .map { HealthPoint(it.day, it.value) }
            series[key] = pts
        }

        // The Workouts tile counts APPLE-LOGGED sessions, the way the iOS twin counts rows whose stored
        // source is Apple Health (`WorkoutSource.isAppleHealth`, both spellings).
        val workoutCount = runCatching {
            vm.repo.workouts(APPLE_DEVICE, 0L, System.currentTimeMillis() / 1000L)
        }.getOrDefault(emptyList()).count { it.source.lowercase() in APPLE_WORKOUT_SOURCES }

        data = AppleData(rows, series, workoutCount)
        loaded = true
    }

    val subtitle = appleHealthSpanSubtitle(if (loaded) data.rows else emptyList(), range).text()

    // PERF (#707): lazy scaffold — in the populated `else` branch each chart section is its own `item { }`,
    // so only on-screen sections compose + are accessibility-walked on scroll (this data view is the long,
    // chart-heavy one). The loading/empty branches stay single items. Order + spacing are unchanged
    // (LazyColumn reproduces the eager `spacedBy(20.dp)` between the six sections).
    LazyScreenScaffold(title = uiString(R.string.l10n_apple_health_screen_apple_health_b19b87da), subtitle = subtitle) {
        when {
            !loaded -> item { SourceLoadingCard(R.string.l10n_apple_health_screen_reading_your_apple_health_history_1a3bf76d) }
            !data.hasAnyData -> item {
                SourceEmptyState(
                    R.string.l10n_apple_health_screen_nothing_imported_yet_f457cdbe,
                    R.string.l10n_apple_health_screen_nothing_imported_yet_on_an_iphone_health_3bb3b8dc,
                )
            }
            else -> {
                // Count visible days off the per-day rows (iOS `rangeSummaryCaption` reads `windowedRows`),
                // and flag if any tracked series had to auto-widen because its selected window was empty.
                val windowedRows = appleHealthWindowedRows(data.rows, range).size
                val anyWidened = data.series.any { (_, s) -> s.isNotEmpty() && resolve(s, range).fellBack }
                item {
                    SourceRangeControl(range, windowedRows, anyWidened) { range = it }
                }
                item { TileGrid(data = data, range = range) }
                item { HeartSection(data = data, range = range) }
                item { ActivitySection(data = data, range = range) }
                item { BodySection(data = data, range = range) }
                item { SleepSection(data = data, range = range) }
            }
        }
    }
}

// MARK: - Metric tiles (uniform fixed-height StatTiles, two per row)

@Composable
private fun TileGrid(data: AppleData, range: SourceRange) {
    // Imperial/Metric display preference (D#103). Weight + lean mass (stored kg) re-label to lb; every
    // other Apple Health metric is unit-agnostic. Display-only.
    val unitSystem = UnitPrefs.system(LocalContext.current)
    // Two columns of equal-width fixed-height tiles, mirroring the macOS adaptive grid. The order is the
    // iOS twin's tile order, Workouts (a COUNT, not a series) last.
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SourceTileRow {
            SourceMetricTile(Modifier.weight(1f), data.raw("steps"), range, R.string.l10n_health_screen_steps_cdde4f20, Palette.metricCyan) { groupedIntString(it) }
            SourceMetricTile(Modifier.weight(1f), data.raw("resting_hr"), range, R.string.l10n_health_screen_resting_hr_26677094, Palette.metricRose, "bpm") {
                "${it.roundToInt()}"
            }
        }
        SourceTileRow {
            SourceMetricTile(Modifier.weight(1f), data.raw("hrv"), range, R.string.today_card_hrv, Palette.metricPurple, "ms") { "${it.roundToInt()}" }
            SourceMetricTile(Modifier.weight(1f), data.raw("vo2max"), range, R.string.l10n_health_screen_vo2max_21214fb6, Palette.accent, "ml/kg") {
                String.format(Locale.US, "%.1f", it)
            }
        }
        SourceTileRow {
            SourceMetricTile(Modifier.weight(1f), data.raw("weight"), range, R.string.l10n_onboarding_screen_weight_69c0b815, Palette.accent) {
                UnitFormatter.massFromKilograms(it, unitSystem)
            }
            SourceMetricTile(Modifier.weight(1f), data.raw("body_fat"), range, R.string.l10n_apple_health_screen_body_fat_13aeec7b, Palette.metricAmber, "%") {
                String.format(Locale.US, "%.1f", it)
            }
        }
        SourceTileRow {
            SourceMetricTile(Modifier.weight(1f), data.raw("lean_mass"), range, R.string.l10n_apple_health_screen_lean_mass_a7494b5d, Palette.accent) {
                UnitFormatter.massFromKilograms(it, unitSystem)
            }
            SourceMetricTile(
                Modifier.weight(1f), data.raw("asleep_min"), range, R.string.l10n_apple_health_screen_asleep_avg_52d49146,
                Palette.metricPurple, aggregate = SourceAggregate.Mean,
            ) { durationMinutesText(it).text() }
        }
        SourceTileRow {
            WorkoutsTile(data.workoutCount, Modifier.weight(1f))
            Spacer(Modifier.weight(1f))
        }
    }
}

/** The iOS twin's own tile: how many Apple-logged workouts the source holds. */
@Composable
private fun WorkoutsTile(count: Int, modifier: Modifier) {
    StatTile(
        label = stringResource(R.string.nav_workouts),
        value = "$count",
        modifier = modifier,
        caption = if (count > 0) stringResource(R.string.l10n_apple_health_screen_apple_logged_cf92d8c4) else null,
        accent = if (count > 0) Palette.strainColor(57.0) else Palette.textTertiary,
    )
}

// MARK: - Chart sections (uniform ChartCard, same height per card)

@Composable
private fun HeartSection(data: AppleData, range: SourceRange) {
    ChartSection(R.string.l10n_apple_health_screen_heart_vitals_b789ad36, R.string.l10n_apple_health_screen_cardiac_a0148244, range) {
        SourceChartCard(data.raw("resting_hr"), range, R.string.trends_resting_hr_full, Palette.metricRose) {
            uiString(R.string.today_driver_value_bpm, it.roundToInt())
        }
        SourceChartCard(data.raw("hrv"), range, R.string.trends_hrv_full, Palette.metricPurple) {
            uiString(R.string.l10n_apple_health_screen_lld_ms_1fe6bb9c, it.roundToInt())
        }
        SourceChartCard(data.raw("spo2"), range, R.string.today_card_blood_oxygen_subtitle, Palette.metricCyan) {
            String.format(Locale.US, "%.1f%%", it)
        }
        SourceChartCard(data.raw("resp_rate"), range, R.string.today_driver_respiratory, Palette.accent) {
            String.format(Locale.US, "%.1f rpm", it)
        }
    }
}

@Composable
private fun ActivitySection(data: AppleData, range: SourceRange) {
    ChartSection(R.string.l10n_apple_health_screen_activity_energy_1c075b75, R.string.timeline_metric_movement, range) {
        SourceChartCard(data.raw("steps"), range, R.string.l10n_health_screen_steps_cdde4f20, Palette.metricCyan) { groupedIntString(it) }
        SourceChartCard(data.raw("active_kcal"), range, R.string.today_card_calories_subtitle, Palette.metricAmber) {
            "${groupedIntString(it)} kcal"
        }
    }
}

@Composable
private fun BodySection(data: AppleData, range: SourceRange) {
    // Weight + lean mass (stored kg) re-label to lb under the imperial preference.
    val unitSystem = UnitPrefs.system(LocalContext.current)
    ChartSection(R.string.l10n_apple_health_screen_body_composition_82c09fee, R.string.l10n_apple_health_screen_slow_threads_388dd4b2, range) {
        SourceChartCard(data.raw("weight"), range, R.string.l10n_onboarding_screen_weight_69c0b815, Palette.accent) {
            UnitFormatter.massFromKilograms(it, unitSystem)
        }
        SourceChartCard(data.raw("body_fat"), range, R.string.l10n_apple_health_screen_body_fat_8ce14357, Palette.metricAmber) {
            String.format(Locale.US, "%.1f%%", it)
        }
        SourceChartCard(data.raw("lean_mass"), range, R.string.l10n_apple_health_screen_lean_body_mass_df160660, Palette.accent) {
            UnitFormatter.massFromKilograms(it, unitSystem)
        }
        SourceChartCard(data.raw("bmi"), range, R.string.l10n_apple_health_screen_bmi_aa26664f, Palette.metricPurple) {
            String.format(Locale.US, "%.1f", it)
        }
    }
}

@Composable
private fun SleepSection(data: AppleData, range: SourceRange) {
    ChartSection(R.string.nav_sleep, R.string.trends_rest, range) {
        SourceChartCard(data.raw("asleep_min"), range, R.string.l10n_sleep_screen_asleep_b9692bbe, Palette.metricPurple) { durationMinutesText(it).text() }
    }
}

/** One section: the shared header over its stack of chart cards. */
@Composable
private fun ChartSection(
    titleRes: Int,
    overlineRes: Int,
    range: SourceRange,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SourceSectionHeader(titleRes, overlineRes, range)
        content()
    }
}
