package com.noop.ui

import com.noop.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/**
 * Per-weekday smart-alarm scheduling (PR #539, @hkuehl): the strap alarm only fires on selected
 * weekdays. Covers the pure date math ([nextSmartAlarmEpochSec]) and the picker's selection rules
 * ([toggledSmartAlarmWeekday] / [smartAlarmWeekdayIsSelected] / [smartAlarmWeekdaySummary]). Mirrors
 * the macOS SmartAlarmWeekdayTests.
 *
 * Calendar.DAY_OF_WEEK numbers: 1 = Sun … 7 = Sat. Empty set = every day (backward compatible).
 */
class SmartAlarmWeekdayTest {

    private val utc = TimeZone.getTimeZone("UTC")

    /** A UTC calendar factory so the math is deterministic regardless of the machine's zone. */
    private fun utcCalendar(): Calendar = Calendar.getInstance(utc)

    /** Epoch millis (UTC) for a given date + time. */
    private fun ms(year: Int, month1: Int, day: Int, hour: Int, minute: Int): Long =
        utcCalendar().apply {
            clear()
            set(year, month1 - 1, day, hour, minute, 0)
        }.timeInMillis

    private fun weekday(epochSec: Long): Int =
        utcCalendar().apply { timeInMillis = epochSec * 1000L }.get(Calendar.DAY_OF_WEEK)

    // 2026-06-17 is a Wednesday (DAY_OF_WEEK 4).
    private fun wedAt(hour: Int, minute: Int) = ms(2026, 6, 17, hour, minute)

    private fun next(minuteOfDay: Int, weekdays: Set<Int>, nowMs: Long): Long? =
        nextSmartAlarmEpochSec(minuteOfDay, weekdays, nowMs, ::utcCalendar)

    // MARK: nextSmartAlarmEpochSec

    @Test
    fun everyDay_emptySet_picksTodayWhenTimeStillAhead() {
        // now = Wed 06:00, wake 07:00 → today 07:00.
        val n = next(7 * 60, emptySet(), wedAt(6, 0))
        assertEquals(wedAt(7, 0) / 1000, n)
    }

    @Test
    fun everyDay_emptySet_rollsToTomorrowWhenTimePassed() {
        // now = Wed 08:00, wake 07:00 → Thu 07:00.
        val n = next(7 * 60, emptySet(), wedAt(8, 0))
        assertEquals(ms(2026, 6, 18, 7, 0) / 1000, n)
    }

    @Test
    fun singleWeekday_today_beforeTime_firesToday() {
        // now = Wed 06:00, Wednesdays only (4) → today 07:00.
        val n = next(7 * 60, setOf(4), wedAt(6, 0))
        assertEquals(wedAt(7, 0) / 1000, n)
        assertEquals(4, weekday(n!!))
    }

    @Test
    fun singleWeekday_today_afterTime_firesNextWeek() {
        // now = Wed 08:00, Wednesdays only → next Wednesday (7 days on).
        val n = next(7 * 60, setOf(4), wedAt(8, 0))
        assertEquals(ms(2026, 6, 24, 7, 0) / 1000, n)
        assertEquals(4, weekday(n!!))
    }

    @Test
    fun weekendsOnly_fromWednesday_firesSaturday() {
        // now = Wed 06:00, weekends [Sun=1, Sat=7] → Saturday (3 days on).
        val n = next(7 * 60, setOf(1, 7), wedAt(6, 0))
        assertEquals(7, weekday(n!!))
        assertEquals(ms(2026, 6, 20, 7, 0) / 1000, n)
    }

    @Test
    fun weekdaysOnly_fromSaturday_firesMonday() {
        // 2026-06-20 is Saturday; weekdays Mon–Fri [2..6] → Monday.
        val n = next(7 * 60, setOf(2, 3, 4, 5, 6), ms(2026, 6, 20, 6, 0))
        assertEquals(2, weekday(n!!))
    }

    @Test
    fun invalidWeekdayNumbersOnly_returnNull() {
        // Only out-of-range days → no valid day → null.
        assertNull(next(7 * 60, setOf(0, 8, 99), wedAt(6, 0)))
    }

    @Test
    fun invalidMixedWithValid_keepsOnlyValid() {
        val n = next(7 * 60, setOf(99, 4), wedAt(6, 0))
        assertEquals(4, weekday(n!!))
    }

    @Test
    fun fireIsAlwaysStrictlyInTheFuture() {
        // Exactly at the wake minute → must skip to the next occurrence.
        val n = next(7 * 60, emptySet(), wedAt(7, 0))
        assertTrue(n!! * 1000L > wedAt(7, 0))
    }

    // MARK: Picker selection rules

    @Test
    fun isSelected_emptyMeansEveryDay() {
        for (dow in 1..7) assertTrue(smartAlarmWeekdayIsSelected(dow, emptySet()))
    }

    @Test
    fun toggle_fromEveryDay_deselectsJustOne() {
        assertEquals(setOf(1, 2, 3, 5, 6, 7), toggledSmartAlarmWeekday(4, emptySet()))
    }

    @Test
    fun toggle_reselectingSeventh_collapsesBackToEveryDay() {
        assertTrue(toggledSmartAlarmWeekday(4, setOf(1, 2, 3, 5, 6, 7)).isEmpty())
    }

    @Test
    fun toggle_addAndRemoveWithinExplicitSet() {
        assertEquals(setOf(2, 3), toggledSmartAlarmWeekday(3, setOf(2)))
        assertEquals(setOf(3), toggledSmartAlarmWeekday(2, setOf(2, 3)))
    }

    @Test
    fun summary_labels() {
        // Resources, not words: the helper is pure and a JVM test has no Application to resolve one
        // (see DisplayText). The special cases are single parts; any other set is one part per day.
        assertEquals(
            listOf(DisplayText.Resource(R.string.l10n_automations_screen_every_day_3b2eb513)),
            smartAlarmWeekdaySummary(emptySet()),
        )
        assertEquals(
            listOf(DisplayText.Resource(R.string.l10n_automations_screen_every_day_3b2eb513)),
            smartAlarmWeekdaySummary(setOf(1, 2, 3, 4, 5, 6, 7)),
        )
        assertEquals(
            listOf(DisplayText.Resource(R.string.l10n_automations_screen_weekdays_4ffc67b6)),
            smartAlarmWeekdaySummary(setOf(2, 3, 4, 5, 6)),
        )
        assertEquals(
            listOf(DisplayText.Resource(R.string.l10n_automations_screen_weekends_2f92a0aa)),
            smartAlarmWeekdaySummary(setOf(1, 7)),
        )
        // Mon + Wed, in the picker's Monday-first order, as two named days.
        assertEquals(
            listOf(
                DisplayText.Resource(R.string.l10n_automations_screen_mon_24b2a099),
                DisplayText.Resource(R.string.l10n_automations_screen_wed_23408b19),
            ),
            smartAlarmWeekdaySummary(setOf(2, 4)),
        )
    }
}
