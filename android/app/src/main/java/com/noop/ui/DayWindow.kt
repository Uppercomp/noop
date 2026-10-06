package com.noop.ui

import java.time.LocalDate

/**
 * `yyyy-MM-dd` -> days since the epoch (a DAY number, not an instant), or null when the string is not a
 * calendar day. The unit matters: a window is `n - 1` DAYS back from the anchor, and mixing this with a
 * seconds-scale cutoff silently produced a negative bound that let every row through.
 */
internal fun dayNumber(day: String): Long? =
    runCatching { LocalDate.parse(day).toEpochDay() }.getOrNull()

/**
 * The rows inside a trailing-day window ANCHORED ON THE LAST ROW'S DAY — the shape both iOS per-metric
 * pages use: `AppleHealthView.slice(_:_:)`, `XiaomiBandView.slice(_:_:)` and
 * `MetricExplorerView.slice(for:)` each measure `n` days back from the latest point, never from "now".
 *
 * A ROW-COUNT window (`takeLast(n)`) is NOT the same thing and was the Android defect on both pages: on a
 * sparse series — the weekly weight / body-fat case the data contract calls out — it returned points from
 * months ago under a "week" caption, and because such a window can never come back empty, the auto-widen
 * path (`fellBack`, "sparse, widened to …") was unreachable dead code.
 *
 * An unparseable anchor day yields NO rows, matching iOS: `latestDate(key)` fails to parse, the guard
 * returns `[]`, and there is no window to show. `days == null` (the ALL range) keeps every row.
 */
internal fun <T> List<T>.windowByDays(days: Int?, dayOf: (T) -> String): List<T> {
    if (days == null) return this
    if (isEmpty()) return emptyList()
    val last = dayNumber(dayOf(last())) ?: return emptyList()
    val cutoff = last - (days - 1)
    return filter { row -> dayNumber(dayOf(row))?.let { it >= cutoff } == true }
}
