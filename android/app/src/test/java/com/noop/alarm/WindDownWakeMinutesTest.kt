package com.noop.alarm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the wind-down's wake-time precedence and the nudge it derives.
 *
 * Android resolves the wake time from TWO sources: the wind-down's own `WindDownNudge.wakeMinutes` (its
 * default 07:00, added this round because iOS keeps its own rather than reading the phone alarm) and the
 * phone alarm's per-day overrides, which still feed the per-day fan-out.
 * `AppViewModel.windDownNudgeMinuteOfDay` and `WindDownScheduler.schedule` both go through this one rule, so
 * the card's "You'll be reminded around …" line cannot describe a different time than the one that fires —
 * the "assert the single resolver, not the call sites" rule.
 *
 * Both helpers are exercised through their companion twins (production arithmetic, not a restatement of it),
 * for the same reason `nudgeDayShift` has one: the store is SharedPreferences-backed, so a plain JVM test
 * cannot construct it.
 */
class WindDownWakeMinutesTest {

    private val sunday = 1
    private val monday = 2

    // The store's documented defaults, which the card's sentence interpolates.
    private val sleepNeed = WindDownStore.DEFAULT_SLEEP_NEED   // 8 h
    private val lead = WindDownStore.DEFAULT_LEAD              // 30 m

    @Test
    fun aDayWithoutAnOverrideUsesTheWindDownsOwnWakeTime() {
        assertEquals(
            WindDownStore.DEFAULT_WAKE,
            WindDownStore.effectiveWakeMinutes(monday, WindDownStore.DEFAULT_WAKE, emptyMap()),
        )
    }

    @Test
    fun aDayWithAnOverrideUsesTheOverrideInstead() {
        val overrides = mapOf(monday to 330)   // 05:30 on Monday only
        assertEquals(330, WindDownStore.effectiveWakeMinutes(monday, WindDownStore.DEFAULT_WAKE, overrides))
        assertEquals(
            "every other day keeps the wind-down's own time",
            WindDownStore.DEFAULT_WAKE,
            WindDownStore.effectiveWakeMinutes(sunday, WindDownStore.DEFAULT_WAKE, overrides),
        )
    }

    @Test
    fun theNudgeFallsTheSleepNeedPlusLeadBeforeWhicheverWakeTimeApplies() {
        // 07:00 wake, 8 h need, 30 m lead -> 22:30 the evening before. (The store's own doc comment uses a
        // 06:30 wake for its 22:00 example; 07:00 lands half an hour later.)
        assertEquals(22 * 60 + 30, WindDownStore.nudgeMinuteOfDay(WindDownStore.DEFAULT_WAKE, sleepNeed, lead))

        // An override day moves the reminder with it: a 05:30 wake puts the nudge at 21:00 the evening before.
        val mondayWake = WindDownStore.effectiveWakeMinutes(monday, WindDownStore.DEFAULT_WAKE, mapOf(monday to 330))
        assertEquals(21 * 60, WindDownStore.nudgeMinuteOfDay(mondayWake, sleepNeed, lead))
    }

    @Test
    fun aWakeTimeThatPushesTheNudgePastMidnightWrapsIntoTheEvening() {
        // 03:30 wake with an 8 h need and 30 m lead lands at 19:00 the PREVIOUS evening, so the minute wraps
        // while nudgeDayShift is what says which day it belongs to. Both halves of that pair are pinned here
        // because either one alone would place the reminder on the wrong evening.
        assertEquals(19 * 60, WindDownStore.nudgeMinuteOfDay(3 * 60 + 30, sleepNeed, lead))
        assertEquals(-1, WindDownStore.nudgeDayShift(3 * 60 + 30, sleepNeed, lead))
    }
}
