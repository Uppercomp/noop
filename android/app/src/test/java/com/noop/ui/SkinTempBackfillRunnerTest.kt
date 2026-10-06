package com.noop.ui

import com.noop.analytics.StreamReadCap
import com.noop.data.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the backfill WALKER's pure parts: the local-midnight maths, which rows become candidates, the read
 * window each candidate gets, and the report line the user and the strap log both see.
 *
 * The store I/O and the per-night value are not here — the value comes from the shared funnel (pinned by
 * `SkinTempBackfillTest`) and the I/O needs a device. What CAN go wrong silently and is cheap to pin is the
 * day arithmetic: an off-by-one tz shift would read the wrong 54 h window for every night, decline them all
 * as "no sleep session ends on this day", and look like a strap with no data rather than a bug.
 */
class SkinTempBackfillRunnerTest {

    /**
     * 2026-08-25 00:00 UTC = 1_787_616_000 — a REAL midnight, unlike the opaque anchor
     * `SkinTempBackfillTest` copies from the Swift twin (1_789_632_000, which is 2026-09-17 16:00 UTC and is
     * only ever used with relative offsets there). This test needs a true midnight because it is pinning the
     * day arithmetic itself. The first run of this file failed on exactly that conflation.
     */
    private val utcMidnight = 1_787_616_000L

    private fun row(day: String, dev: Double?, abs: Double?) = DailyMetric(
        deviceId = "my-whoop-noop",
        day = day,
        skinTempDevC = dev,
        skinTempC = abs,
    )

    @Test
    fun localMidnightMatchesUtcAtZeroOffsetAndShiftsWithTheZone() {
        assertEquals(utcMidnight, SkinTempBackfillRunner.localMidnightUnix("2026-08-25", 0L))
        // An hour EAST of UTC means local midnight is an hour EARLIER in unix seconds.
        assertEquals(utcMidnight - 3_600L, SkinTempBackfillRunner.localMidnightUnix("2026-08-25", 3_600L))
        assertEquals(utcMidnight + 3_600L, SkinTempBackfillRunner.localMidnightUnix("2026-08-25", -3_600L))
    }

    @Test
    fun anUnparseableDayKeyIsZeroRatherThanAThrow() {
        // The candidate query only ever supplies keys the store wrote, so this is belt-and-braces: a corrupt
        // key must not take the Test Centre down mid-pass.
        assertEquals(0L, SkinTempBackfillRunner.localMidnightUnix("not-a-day", 0L))
        assertEquals(0L, SkinTempBackfillRunner.localMidnightUnix("2026-13-45", 0L))
    }

    @Test
    fun dayKeyIsTheLocalDayNotTheUtcOne() {
        // 2026-08-25 23:30 UTC is already the 26th two hours east of UTC.
        val ts = utcMidnight + 23 * 3_600L + 1_800L
        assertEquals("2026-08-25", SkinTempBackfillRunner.dayKey(ts, 0L))
        assertEquals("2026-08-26", SkinTempBackfillRunner.dayKey(ts, 2 * 3_600L))
    }

    @Test
    fun onlyNightsWithADeviationAndNoAbsoluteBecomeCandidates() {
        val rows = listOf(
            row("2026-08-26", dev = 0.3, abs = null),   // scored, absolute missing -> candidate
            row("2026-08-25", dev = 0.1, abs = null),   // candidate, and OLDER
            row("2026-08-27", dev = 0.2, abs = 36.4),   // already has an absolute -> not a candidate
            row("2026-08-24", dev = null, abs = null),  // never scored -> not a candidate
        )
        val candidates = SkinTempBackfillRunner.candidatesFrom(rows, 0L, utcMidnight, "my-whoop")
        assertEquals(listOf("2026-08-25", "2026-08-26"), candidates.map { it.day })
        assertEquals("my-whoop", candidates.first().owner)
    }

    @Test
    fun theReadWindowIsTheLookbackToTheEndOfTheDayButNeverPastNow() {
        val now = utcMidnight + 10 * 3_600L   // mid-morning on the 25th
        val candidates = SkinTempBackfillRunner.candidatesFrom(
            listOf(row("2026-08-25", dev = 0.1, abs = null), row("2026-08-20", dev = 0.1, abs = null)),
            0L,
            now,
            "my-whoop",
        )
        val today = candidates.first { it.day == "2026-08-25" }
        assertEquals("the window opens a lookback before local midnight", utcMidnight - StreamReadCap.LOOKBACK_SECONDS, today.from)
        assertEquals("today's partial night stops at now", now, today.to)

        val past = candidates.first { it.day == "2026-08-20" }
        // Each candidate's `to` is ITS OWN day's end — five days before the 25th, not the 25th's.
        assertEquals(utcMidnight - 4 * 86_400L, past.to)
        assertEquals(utcMidnight - 5 * 86_400L - StreamReadCap.LOOKBACK_SECONDS, past.from)
        assertTrue("a past night reads a full day", past.to - past.from > StreamReadCap.LOOKBACK_SECONDS)
    }

    @Test
    fun theReportLineNamesTheAnchorAndWhetherTheSweepFinished() {
        val withAnchor = SkinTempBackfillRunner.BackfillResult(
            filled = listOf(SkinTempBackfillRunner.FilledNight("2026-08-25", 36.4)),
            declined = listOf(SkinTempBackfillRunner.DeclinedNight("2026-08-24", "no sleep session ends on this day")),
            noRawData = listOf("2026-08-23"),
            windowAnchorRaw = 1290.0,
            reachedEnd = true,
        )
        val line = SkinTempBackfillRunner.reportLine(withAnchor)
        assertEquals(
            "skin-temp backfill (#1853): filled 1, declined 1, no raw data 1 " +
                "(anchor from current window, reached end)",
            line,
        )

        // No anchor + a budget stop reads differently, and both halves matter to a reader diagnosing a
        // 4.0 install that filled nothing.
        val noAnchor = withAnchor.copy(filled = emptyList(), windowAnchorRaw = null, reachedEnd = false)
        assertTrue(SkinTempBackfillRunner.reportLine(noAnchor).contains("no 4.0 anchor"))
        assertTrue(SkinTempBackfillRunner.reportLine(noAnchor).contains("hit page budget"))
    }
}
