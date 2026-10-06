package com.noop.ui

import com.noop.R
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

// MARK: - The per-source page components Apple Health and Mi Band both draw
//
// Both iOS pages (`AppleHealthView`, `XiaomiBandView`) are the same instrument: one range control, a grid
// of fixed-height StatTiles carrying a sparkline, then SectionHeader + ChartCard groups whose body is a
// trend line (or a lone reading, or "no readings") and whose footer is Avg / Min / Max / Points. These are
// the Android twins of those components, in ONE place so a fix to the shared chrome cannot land on one
// page and miss the other.

/** A tile's hero value: the LATEST reading in the window, or the window's mean. */
internal enum class SourceAggregate { Latest, Mean }

/**
 * The one range control: the W / M / 3M / 6M / 1Y / ALL pills, the range's own caption on the right, and
 * the window-level sentence underneath (which turns warning-coloured when a sparse series had to widen).
 */
@Composable
internal fun SourceRangeControl(
    range: SourceRange,
    windowedRowCount: Int,
    anyWidened: Boolean,
    onSelect: (SourceRange) -> Unit,
) {
    // The pill labels are resolved OUTSIDE the label lambda: `SegmentedPillControl` takes a `(T) -> String`,
    // which cannot be @Composable.
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
            sourceRangeSummary(windowedRowCount, anyWidened, range).text(),
            style = NoopType.footnote,
            color = if (anyWidened) Palette.statusWarning else Palette.textTertiary,
        )
    }
}

/** One row of the tile grid: two equal-width tiles, or a tile beside the gap that keeps the pair narrow. */
@Composable
internal fun SourceTileRow(content: @Composable () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) { content() }
}

/** The loading card both pages show before their first read lands. */
@Composable
internal fun SourceLoadingCard(@StringRes textRes: Int) {
    NoopCard(tint = Palette.metricCyan) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ConnectionDot(tone = StrandTone.Accent, pulsing = true)
            Text(
                uiString(textRes),
                style = NoopType.subhead,
                color = Palette.textSecondary,
            )
        }
    }
}

/** The empty state both pages show when nothing has been imported for their source yet. */
@Composable
internal fun SourceEmptyState(@StringRes titleRes: Int, @StringRes bodyRes: Int) {
    DataPendingNote(title = uiString(titleRes), body = uiString(bodyRes))
}

/**
 * One StatTile for a metric series. Sparse-safe: the window auto-widens to the smallest larger range that
 * holds data; the hero is the LATEST point ("as of <day>") unless a mean is requested; an empty series
 * renders a dash in tertiary ink rather than disappearing.
 *
 * The iOS twins also hand every tile a SPARKLINE over the resolved window whenever the series holds more
 * than one point (the last 40 samples), so a metric reads as a trend and not just a number.
 */
@Composable
internal fun SourceMetricTile(
    modifier: Modifier,
    points: List<HealthPoint>,
    range: SourceRange,
    @StringRes labelRes: Int,
    accent: Color,
    unit: String = "",
    aggregate: SourceAggregate = SourceAggregate.Latest,
    fmt: @Composable (Double) -> String,
) {
    val rows = resolve(points, range).rows
    val values = rows.map { it.value }
    val empty = values.isEmpty()

    val value: String
    val caption: String?
    when {
        empty -> {
            value = "—"
            caption = null
        }
        aggregate == SourceAggregate.Latest -> {
            value = if (unit.isEmpty()) fmt(values.last()) else "${fmt(values.last())} $unit"
            caption = rows.lastOrNull()?.let { sourceAsOfCaption(it.day).text() }
        }
        else -> {
            value = if (unit.isEmpty()) fmt(values.average()) else "${fmt(values.average())} $unit"
            caption = uiString(R.string.l10n_apple_health_screen_avg_lldd_b9e440fc, values.size)
        }
    }

    SparkStatTile(
        label = stringResource(labelRes),
        value = value,
        modifier = modifier,
        caption = caption,
        accent = if (empty) Palette.textTertiary else accent,
        spark = if (values.size > 1) values.takeLast(40) else emptyList(),
        sparkColor = accent,
    )
}

/**
 * One ChartCard for a metric series: an overline title with the sparse-aware window note and the window's
 * mean as a trailing readout, a fixed-height trend body, and an Avg / Min / Max / Points footer. A single
 * point shows a lone read-out instead of a flat line; an empty window says so.
 */
@Composable
internal fun SourceChartCard(
    points: List<HealthPoint>,
    range: SourceRange,
    @StringRes titleRes: Int,
    accent: Color,
    fmt: @Composable (Double) -> String,
) {
    val resolved = resolve(points, range)
    val values = resolved.rows.map { it.value }
    val n = values.size

    NoopCard(tint = accent) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            // iOS `ChartCard`: `HStack(alignment: .firstTextBaseline)` over `VStack(spacing: 2)`. Same two
            // defects the two header lines used to carry — tops-aligned, and no gap between the overline and
            // its subtitle — so they sat flush and the trailing readout was out by the size difference
            // between the 11pt overline and the 18pt number beside it.
            Row {
                Column(
                    modifier = Modifier.weight(1f).alignByBaseline(),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Overline(stringResource(titleRes))
                    Text(
                        sourceRangeNote(n, resolved.fellBack, range).text(),
                        style = NoopType.footnote,
                        color = if (resolved.fellBack) Palette.statusWarning else Palette.textTertiary,
                    )
                }
                Text(
                    if (n > 0) fmt(values.average()) else "—",
                    style = NoopType.number(18f),
                    color = if (n > 0) accent else Palette.textTertiary,
                    modifier = Modifier.alignByBaseline(),
                )
            }

            when {
                n >= 2 -> LineChart(
                    values = values,
                    modifier = Modifier.height(Metrics.chartHeight),
                    color = accent,
                    fill = true,
                )
                n == 1 -> SourceSinglePoint(values.first(), accent, fmt)
                else -> SourceEmptyChart()
            }

            val footer = if (n > 0) {
                listOf(
                    footerLabel(R.string.trends_avg) to fmt(values.average()),
                    footerLabel(R.string.trends_min) to fmt(values.min()),
                    footerLabel(R.string.trends_max) to fmt(values.max()),
                    footerLabel(R.string.explore_chart_points) to "$n",
                )
            } else {
                listOf(
                    footerLabel(R.string.trends_avg) to "—",
                    footerLabel(R.string.trends_min) to "—",
                    footerLabel(R.string.trends_max) to "—",
                    footerLabel(R.string.explore_chart_points) to "0",
                )
            }
            SourceFooterRow(footer)
        }
    }
}

/** The four footer captions with their values. */
@Composable
internal fun SourceFooterRow(items: List<Pair<String, String>>) {
    Row(horizontalArrangement = Arrangement.spacedBy(Metrics.sectionGap)) {
        items.forEach { (label, value) ->
            Column {
                Overline(label, color = Palette.textTertiary)
                Text(value, style = NoopType.captionNumber, color = Palette.textSecondary)
            }
        }
    }
}

/** A footer caption ("Avg" / "Min" / "Max" / "Points"), resolved where the composition can read it. */
@Composable
private fun footerLabel(@StringRes id: Int): String = stringResource(id)

/** Lone-reading body for a series with exactly one point in range. */
@Composable
private fun SourceSinglePoint(value: Double, accent: Color, fmt: @Composable (Double) -> String) {
    Box(
        modifier = Modifier.fillMaxWidth().height(Metrics.chartHeight),
        contentAlignment = Alignment.CenterStart,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Overline(uiString(R.string.l10n_apple_health_screen_latest_reading_98b87f72))
            Text(fmt(value), style = NoopType.number(34f), color = accent)
        }
    }
}

/** An empty window's card body. */
@Composable
private fun SourceEmptyChart() {
    Box(
        modifier = Modifier.fillMaxWidth().height(Metrics.chartHeight),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            uiString(R.string.l10n_apple_health_screen_no_readings_recorded_05018b26),
            style = NoopType.subhead,
            color = Palette.textTertiary,
        )
    }
}

/** A section's header: the iOS `SectionHeader(title, overline:, trailing: range.caption)`. */
@Composable
internal fun SourceSectionHeader(@StringRes titleRes: Int, @StringRes overlineRes: Int, range: SourceRange) {
    SectionHeader(
        stringResource(titleRes),
        overline = stringResource(overlineRes),
        trailing = stringResource(range.captionRes),
    )
}
