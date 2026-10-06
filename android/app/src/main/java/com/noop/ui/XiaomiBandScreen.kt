package com.noop.ui

import com.noop.R
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.noop.data.MetricSeriesRow
import com.noop.data.SleepSession
import com.noop.ingest.XiaomiBandImporter
import java.util.Locale
import kotlin.math.roundToInt

// MARK: - Mi Band (per-source page)
//
// Faithful port of `Strand/Screens/XiaomiBandView.swift`. It is the SAME instrument as the Apple Health
// page — one range control, a tile grid, then SectionHeader + ChartCard groups — reading the
// "xiaomi-band" source the Mi Fitness import writes (Data Sources → Mi Fitness export).
//
// Where it deliberately DIFFERS from the Apple Health page, because the iOS twin does:
//   * the header subtitle is just the date span of everything loaded ("14 Aug 2026 → 2 Oct 2026"), NOT
//     windowed by the range and with no day-count prefix;
//   * the window caption under the pills is the range's own name ("3 months"), not "90 days · 3 months";
//   * it carries the last imported night's hypnogram, because Mi sleep rows store per-epoch stages.
//
// COPY PARITY: labels, captions and range names resolve through resources whose English is the iOS String
// Catalog key verbatim, via the shared pieces in SourcePageCopy.kt / SourcePageUi.kt. The one overline
// Apple's catalog does not carry is "Body energy": iOS interpolates a `String` into a `LocalizedStringKey`
// there, so it renders English on the iPhone; Android keeps a real resource with the same English rather
// than a hardcoded literal, and localises the overlines Apple's catalog DOES carry ("Cardiac", "Rest",
// "Movement") where the iPhone accidentally falls back to English.

private const val XIAOMI_DEVICE = XiaomiBandImporter.DEFAULT_DEVICE_ID

/** The metricSeries keys the Mi Fitness import writes — the iOS twin's `seriesKeys`, in its order. */
internal val XIAOMI_SERIES_KEYS = listOf(
    "steps", "distance_m", "energy_kcal", "intensity_min",
    "rhr", "avg_hr", "max_hr", "spo2",
    "sleep_total_min", "sleep_deep_min", "sleep_rem_min", "sleep_light_min", "sleep_score",
    "stress", "vitality",
)

// MARK: - How each chart's value reads

internal enum class XiaomiValueFormat { Bpm, Spo2Percent, Duration, Whole, Kcal, Minutes, Distance }

/**
 * The per-chart formatters of the iOS twin (`chartCard(fmt:)`), in one place so tiles and cards agree.
 *
 * A [DisplayText], not a resolved String: the formatter is pure and pinned by a JVM test, and the catalog
 * translates the duration and the "min" unit (ru "…ч …м" / "мин", zh "…小时…分" / "分钟").
 */
internal fun xiaomiFormat(kind: XiaomiValueFormat, value: Double, unitSystem: UnitSystem): DisplayText = when (kind) {
    XiaomiValueFormat.Bpm -> DisplayText.Dynamic("${value.roundToInt()} bpm")
    XiaomiValueFormat.Spo2Percent -> DisplayText.Dynamic(String.format(Locale.US, "%.0f%%", value))
    XiaomiValueFormat.Duration -> durationMinutesText(value)
    XiaomiValueFormat.Whole -> DisplayText.Dynamic("${value.roundToInt()}")
    XiaomiValueFormat.Kcal -> DisplayText.Dynamic("${groupedIntString(value)} kcal")
    XiaomiValueFormat.Minutes -> DisplayText.Resource(R.string.l10n_xiaomi_band_screen_lld_min_b6190882, listOf(value.roundToInt()))
    XiaomiValueFormat.Distance -> DisplayText.Dynamic(UnitFormatter.distanceFromMeters(value, unitSystem))
}

// MARK: - The page as data (the iOS twin's `pageItems`), so its order is testable

internal sealed interface XiaomiPageItem {
    data class Header(@StringRes val titleRes: Int, @StringRes val overlineRes: Int) : XiaomiPageItem
    data class Chart(@StringRes val titleRes: Int, val key: String, val format: XiaomiValueFormat) : XiaomiPageItem

    /** The imported night's stage breakdown, drawn just before the Sleep charts. */
    data object Hypnogram : XiaomiPageItem
}

/** The section headers and chart cards in the iOS twin's order. */
internal fun xiaomiPageItems(): List<XiaomiPageItem> = listOf(
    XiaomiPageItem.Header(R.string.l10n_apple_health_screen_heart_vitals_b789ad36, R.string.l10n_apple_health_screen_cardiac_a0148244),
    XiaomiPageItem.Chart(R.string.trends_resting_hr_full, "rhr", XiaomiValueFormat.Bpm),
    XiaomiPageItem.Chart(R.string.l10n_xiaomi_band_screen_average_heart_rate_89878f47, "avg_hr", XiaomiValueFormat.Bpm),
    XiaomiPageItem.Chart(R.string.l10n_xiaomi_band_screen_peak_heart_rate_fb14cf1d, "max_hr", XiaomiValueFormat.Bpm),
    XiaomiPageItem.Chart(R.string.today_card_blood_oxygen_subtitle, "spo2", XiaomiValueFormat.Spo2Percent),
    XiaomiPageItem.Hypnogram,
    XiaomiPageItem.Header(R.string.nav_sleep, R.string.trends_rest),
    XiaomiPageItem.Chart(R.string.l10n_xiaomi_band_screen_time_asleep_231fbf14, "sleep_total_min", XiaomiValueFormat.Duration),
    XiaomiPageItem.Chart(R.string.l10n_xiaomi_band_screen_deep_sleep_865e5887, "sleep_deep_min", XiaomiValueFormat.Duration),
    XiaomiPageItem.Chart(R.string.l10n_xiaomi_band_screen_rem_sleep_d0c12b6b, "sleep_rem_min", XiaomiValueFormat.Duration),
    XiaomiPageItem.Chart(R.string.l10n_xiaomi_band_screen_sleep_score_e2ce44da, "sleep_score", XiaomiValueFormat.Whole),
    XiaomiPageItem.Header(R.string.l10n_apple_health_screen_activity_energy_1c075b75, R.string.timeline_metric_movement),
    XiaomiPageItem.Chart(R.string.l10n_health_screen_steps_cdde4f20, "steps", XiaomiValueFormat.Whole),
    XiaomiPageItem.Chart(R.string.l10n_live_screen_distance_42320809, "distance_m", XiaomiValueFormat.Distance),
    XiaomiPageItem.Chart(R.string.today_card_calories_subtitle, "energy_kcal", XiaomiValueFormat.Kcal),
    XiaomiPageItem.Chart(R.string.l10n_xiaomi_band_screen_intensity_minutes_39bf8010, "intensity_min", XiaomiValueFormat.Minutes),
    XiaomiPageItem.Header(R.string.l10n_xiaomi_band_screen_wellbeing_a85b236d, R.string.l10n_xiaomi_band_screen_body_energy_64d0f810),
    XiaomiPageItem.Chart(R.string.nav_stress, "stress", XiaomiValueFormat.Whole),
    XiaomiPageItem.Chart(R.string.l10n_health_screen_vitality_be320b06, "vitality", XiaomiValueFormat.Whole),
)

/** The colour world of each metric, matching the iOS twin's five gradients by category. */
internal fun xiaomiAccent(key: String): Color = when (key) {
    "rhr", "avg_hr", "max_hr" -> Palette.metricRose
    "spo2", "steps", "distance_m" -> Palette.metricCyan
    "sleep_total_min", "sleep_deep_min", "sleep_rem_min" -> Palette.metricPurple
    "energy_kcal", "intensity_min", "stress" -> Palette.metricAmber
    else -> Palette.accent
}

// MARK: - The window-level caption (this page's shape, not Apple Health's)

/**
 * The caption under the pills: the range's own name, plus "· some sparse series widened" when any tracked
 * series had to widen. The Apple Health page prefixes a day count here instead — the two iOS twins differ,
 * so the two Android pages must too.
 */
internal fun xiaomiRangeSummary(widened: Boolean, range: SourceRange): DisplayText =
    if (widened) {
        DisplayText.Resource(
            R.string.l10n_xiaomi_band_screen_some_sparse_series_widened_bcc732b8,
            listOf(DisplayText.Resource(range.nameRes)),
        )
    } else {
        DisplayText.Resource(range.nameRes)
    }

// MARK: - Header span (every loaded day, NOT windowed — the iOS twin's `spanSubtitle`)

/** The header subtitle: the span of every loaded day, or the sentence naming what this page reads. */
internal fun xiaomiSpanSubtitle(days: List<String>): DisplayText {
    val first = days.minOrNull()
    val last = days.maxOrNull()
    if (first == null || last == null) {
        return DisplayText.Resource(R.string.l10n_xiaomi_band_screen_steps_heart_rate_sleep_spo_and_stress_im_8df17cc1)
    }
    val lo = sourceSpanDay(first)
    val hi = sourceSpanDay(last)
    // iOS returns the span as a plain String here — no `String(localized:)` — so it is not translated.
    return DisplayText.Dynamic(if (lo == hi) lo else "$lo → $hi")
}

// MARK: - Imported Mi sleep: the last staged night's hypnogram

/** The stage minutes of one imported Mi night, in the importer's own stage vocabulary. */
internal data class XiaomiStages(
    val awakeMin: Double,
    val lightMin: Double,
    val deepMin: Double,
    val remMin: Double,
) {
    val asleepMin: Double get() = lightMin + deepMin + remMin

    /** The proportional strip weights `HypnogramWithAxis` draws, empty stages dropped. */
    val strip: List<Pair<String, Float>>
        get() = listOf(
            "wake" to awakeMin.toFloat(),
            "light" to lightMin.toFloat(),
            "deep" to deepMin.toFloat(),
            "rem" to remMin.toFloat(),
        ).filter { it.second > 0f }
}

/**
 * Decode one imported Mi night's per-epoch stages. The importer stores the verbatim `[{start,end,stage}]`
 * hypnogram, the same shape the on-device stager writes, so the shared parser reads it; "wake"/"awake"
 * fold into one bucket exactly as the iOS twin's `decodeStages` does. Null when the night carries no usable
 * stages or no asleep minutes — the card is then absent rather than drawn empty.
 */
internal fun xiaomiStages(json: String?): XiaomiStages? {
    val segments = parsePersistedSegments(json) ?: return null
    var awake = 0.0
    var light = 0.0
    var deep = 0.0
    var rem = 0.0
    for (segment in segments) {
        val minutes = (segment.end - segment.start) / 60.0
        when (segment.stage.lowercase()) {
            "wake", "awake" -> awake += minutes
            "light" -> light += minutes
            "deep" -> deep += minutes
            "rem" -> rem += minutes
            else -> continue
        }
    }
    val stages = XiaomiStages(awake, light, deep, rem)
    return if (stages.asleepMin > 0.0) stages else null
}

/** The most recent imported night that carries a usable hypnogram — iOS's `sleeps.last(where:)`. */
internal fun lastStagedNight(sessions: List<SleepSession>): SleepSession? =
    sessions.lastOrNull { (it.stagesJSON?.length ?: 0) > 2 && xiaomiStages(it.stagesJSON) != null }

// MARK: - Screen

@Composable
fun XiaomiBandScreen(vm: AppViewModel) {
    var loaded by remember { mutableStateOf(false) }
    var series by remember { mutableStateOf(emptyMap<String, List<HealthPoint>>()) }
    var nights by remember { mutableStateOf(emptyList<SleepSession>()) }
    var range by remember { mutableStateOf(SourceRange.Quarter) }

    // ALL history is loaded once and the range control windows it client-side, anchored to the latest data
    // point — the iOS twin's load(), including the 4000-sleep cap.
    LaunchedEffect(Unit) {
        val fetched = linkedMapOf<String, List<HealthPoint>>()
        for (key in XIAOMI_SERIES_KEYS) {
            val points = runCatching {
                vm.repo.metricSeries(XIAOMI_DEVICE, key, "0000-00-00", "9999-99-99")
            }.getOrDefault(emptyList<MetricSeriesRow>())
                .sortedBy { it.day }
                .map { HealthPoint(it.day, it.value) }
            fetched[key] = points
        }
        val sleeps = runCatching {
            vm.repo.sleepSessionsForDevice(XIAOMI_DEVICE, 0L, Long.MAX_VALUE, 4000)
        }.getOrDefault(emptyList())
        series = fetched
        nights = sleeps
        loaded = true
    }

    val hasAnyData = series.values.any { it.isNotEmpty() }
    val subtitle = xiaomiSpanSubtitle(series.values.flatten().map { it.day }).text()
    val night = remember(nights) { lastStagedNight(nights) }

    LazyScreenScaffold(title = uiString(R.string.today_source_mi_band), subtitle = subtitle) {
        when {
            !loaded -> item { SourceLoadingCard(R.string.l10n_xiaomi_band_screen_reading_your_mi_band_history_0b581b69) }
            !hasAnyData -> item {
                SourceEmptyState(
                    R.string.l10n_apple_health_screen_nothing_imported_yet_f457cdbe,
                    R.string.l10n_xiaomi_band_screen_nothing_imported_yet_in_data_sources_cho_2f050312,
                )
            }
            else -> {
                val anyWidened = XIAOMI_SERIES_KEYS.any { key ->
                    val points = series[key] ?: emptyList()
                    points.isNotEmpty() && resolve(points, range).fellBack
                }
                item { RangeControl(anyWidened = anyWidened, range = range, onSelect = { range = it }) }
                item { TileGrid(series = series, range = range) }
                items(xiaomiPageItems()) { pageItem ->
                    when (pageItem) {
                        is XiaomiPageItem.Header -> SourceSectionHeader(pageItem.titleRes, pageItem.overlineRes, range)
                        is XiaomiPageItem.Chart -> XiaomiChartCard(pageItem, series[pageItem.key] ?: emptyList(), range)
                        XiaomiPageItem.Hypnogram -> night?.let { LastSleepSection(it) }
                    }
                }
            }
        }
    }
}

/** The pills plus THIS page's window caption (the range name, not the Apple Health day count). */
@Composable
private fun RangeControl(anyWidened: Boolean, range: SourceRange, onSelect: (SourceRange) -> Unit) {
    val labels = SourceRange.entries.map { stringResource(it.labelRes) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SegmentedPillControl(
                items = SourceRange.entries.toList(),
                selection = range,
                label = { labels[it.ordinal] },
                onSelect = onSelect,
            )
            Spacer(Modifier.weight(1f))
            Overline(stringResource(range.captionRes), color = Palette.textTertiary)
        }
        Text(
            xiaomiRangeSummary(anyWidened, range).text(),
            style = NoopType.footnote,
            color = if (anyWidened) Palette.statusWarning else Palette.textTertiary,
        )
    }
}

// MARK: - Tiles (the iOS twin's eight, in its order)

@Composable
private fun TileGrid(series: Map<String, List<HealthPoint>>, range: SourceRange) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SourceTileRow {
            SourceMetricTile(Modifier.weight(1f), series["steps"] ?: emptyList(), range, R.string.l10n_health_screen_steps_cdde4f20, Palette.metricCyan) {
                groupedIntString(it)
            }
            SourceMetricTile(Modifier.weight(1f), series["rhr"] ?: emptyList(), range, R.string.l10n_health_screen_resting_hr_26677094, Palette.metricRose, "bpm") {
                "${it.roundToInt()}"
            }
        }
        SourceTileRow {
            SourceMetricTile(
                Modifier.weight(1f), series["sleep_total_min"] ?: emptyList(), range,
                R.string.l10n_xiaomi_band_screen_sleep_avg_aa710fc6, Palette.metricPurple,
                aggregate = SourceAggregate.Mean,
            ) { durationMinutesText(it).text() }
            SourceMetricTile(Modifier.weight(1f), series["sleep_score"] ?: emptyList(), range, R.string.l10n_xiaomi_band_screen_sleep_score_e2ce44da, Palette.accent) {
                "${it.roundToInt()}"
            }
        }
        SourceTileRow {
            SourceMetricTile(Modifier.weight(1f), series["spo2"] ?: emptyList(), range, R.string.today_card_blood_oxygen_subtitle, Palette.metricCyan, "%") {
                String.format(Locale.US, "%.0f", it)
            }
            SourceMetricTile(
                Modifier.weight(1f), series["stress"] ?: emptyList(), range,
                R.string.l10n_xiaomi_band_screen_stress_avg_411539a0, Palette.metricAmber,
                aggregate = SourceAggregate.Mean,
            ) { "${it.roundToInt()}" }
        }
        SourceTileRow {
            SourceMetricTile(
                Modifier.weight(1f), series["avg_hr"] ?: emptyList(), range,
                R.string.l10n_xiaomi_band_screen_avg_hr_40a8c2fa, Palette.metricRose, "bpm",
                aggregate = SourceAggregate.Mean,
            ) { "${it.roundToInt()}" }
            SourceMetricTile(Modifier.weight(1f), series["vitality"] ?: emptyList(), range, R.string.l10n_health_screen_vitality_be320b06, Palette.accent) {
                "${it.roundToInt()}"
            }
        }
        // The iOS twin's eight tiles fill four rows exactly, so nothing follows them.
    }
}

// MARK: - Chart cards

@Composable
private fun XiaomiChartCard(item: XiaomiPageItem.Chart, points: List<HealthPoint>, range: SourceRange) {
    val distanceSystem = UnitPrefs.distanceSystem(LocalContext.current)
    SourceChartCard(points, range, item.titleRes, xiaomiAccent(item.key)) {
        xiaomiFormat(item.format, it, distanceSystem).text()
    }
}

// MARK: - Last imported night (the iOS twin's `sleepDetailSection`)

@Composable
private fun LastSleepSection(night: SleepSession) {
    val stages = remember(night.stagesJSON) { xiaomiStages(night.stagesJSON) } ?: return
    val inBedMin = (night.endTs - night.startTs) / 60.0
    val efficiency = night.efficiency
    val subtitle = if (efficiency != null) {
        uiString(
            R.string.l10n_xiaomi_band_screen_in_bed_lld_efficiency_d03e15c2,
            durationMinutesText(inBedMin).text(),
            efficiency.roundToInt(),
        )
    } else {
        uiString(R.string.l10n_xiaomi_band_screen_in_bed_ae91887c, durationMinutesText(inBedMin).text())
    }

    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(
            uiString(R.string.l10n_xiaomi_band_screen_last_sleep_68209098),
            overline = uiString(R.string.l10n_xiaomi_band_screen_hypnogram_9d3101c0),
            trailing = sourceShortDayFromEpochSeconds(night.effectiveStartTs),
        )
        NoopCard(tint = Palette.restColor) {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Overline(uiString(R.string.l10n_sleep_screen_stage_breakdown_e9b714f9))
                    Text(subtitle, style = NoopType.footnote, color = Palette.textTertiary)
                }
                HypnogramWithAxis(
                    stages = stages.strip,
                    onsetTs = night.effectiveStartTs,
                    wakeTs = night.endTs,
                )
                SourceFooterRow(
                    listOf(
                        uiString(R.string.l10n_xiaomi_band_screen_rem_e56eb975) to durationMinutesText(stages.remMin).text(),
                        uiString(R.string.l10n_xiaomi_band_screen_deep_be7a6a37) to durationMinutesText(stages.deepMin).text(),
                        uiString(R.string.l10n_onboarding_screen_light_a36ef8ab) to durationMinutesText(stages.lightMin).text(),
                        uiString(R.string.l10n_xiaomi_band_screen_awake_2b453e3e) to durationMinutesText(stages.awakeMin).text(),
                    ),
                )
            }
        }
    }
}
