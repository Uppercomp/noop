package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the ONE day-distance window every per-metric page slices with — the Android twin of iOS's
 * `slice(_:_:)` (`AppleHealthView`, `XiaomiBandView`, `MetricExplorerView` all use that shape).
 *
 * The rule is deliberately narrow and worth a test of its own: the window is measured in DAYS back from
 * the row set's OWN latest day, never from the clock and never as a row count. Both mistakes were live on
 * Android, and each one is silent — a row-count window shows stale readings under a short range, and a
 * seconds-scale cutoff (days against `n * 86_400`) comes out negative and lets the whole series through.
 */
class DayWindowTest {

    private fun point(day: String, value: Double = 1.0) = HealthPoint(day, value)

    private fun days(rows: List<HealthPoint>) = rows.map { it.day }

    @Test
    fun windowIsMeasuredInDaysBackFromTheLatestRow() {
        val series = listOf(point("2026-06-01")) + (14..20).map { point("2026-08-%02d".format(it)) }
        assertEquals(
            (14..20).map { "2026-08-%02d".format(it) },
            days(series.windowByDays(7) { it.day }),
        )
    }

    @Test
    fun anchorIsTheLatestRowNotTheClock() {
        val series = listOf(point("2020-01-01"), point("2020-01-03"))
        assertEquals(listOf("2020-01-01", "2020-01-03"), days(series.windowByDays(7) { it.day }))
    }

    @Test
    fun allKeepsEveryRowAndAnEmptySeriesKeepsNothing() {
        val series = listOf(point("2026-08-14"))
        assertEquals(1, series.windowByDays(null) { it.day }.size)
        assertEquals(emptyList<HealthPoint>(), emptyList<HealthPoint>().windowByDays(7) { it.day })
    }

    @Test
    fun anUnparseableAnchorLeavesNoWindow() {
        val series = listOf(point("2026-08-14"), point("not-a-day"))
        assertEquals(emptyList<HealthPoint>(), series.windowByDays(7) { it.day })
    }

    @Test
    fun theDayNumberHelperRejectsNonDays() {
        assertEquals(20_679L, dayNumber("2026-08-14"))
        assertEquals(null, dayNumber("not-a-day"))
        assertEquals(null, dayNumber("2026-13-01"))
    }
}
