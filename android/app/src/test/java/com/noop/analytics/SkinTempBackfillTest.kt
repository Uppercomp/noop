package com.noop.analytics

import com.noop.data.HrSample
import com.noop.data.SkinTempSample
import com.noop.protocol.DeviceFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pure half of the skin-temp absolute backfill: the session-attribution rule (1), the anchor rule (3)
 * and the pagination rule (2). Mirrors `StrandTests/SkinTempBackfillTests.swift` case for case — that file
 * is the only place the two implementations are compared, and no Swift toolchain exists here to run it as an
 * oracle, so the same inputs and the same expectations are the strongest check available.
 *
 * Why these three rules are worth this much test: the backfill WRITES `skinTempC`, a stored absolute. Rule 1
 * broken averages two nights into one temperature; rule 3 broken puts backfilled nights on a different
 * offset from the stored ones, so the chart plots two scales as one line. Neither is visible at the call
 * site, and both are silent.
 */
class SkinTempBackfillTest {

    /** 2026-08-25 00:00 UTC, the value both test files use. */
    private val dayStart = 1_789_632_000L

    private fun session(start: Long, end: Long) = DetectedSleep(
        start = start,
        end = end,
        efficiency = 0.9,
        stages = emptyList(),
        restingHR = null,
        avgHRV = null,
    )

    private fun skinTemp(count: Int, firstTs: Long, raw: Int) =
        (0 until count).map { SkinTempSample(deviceId = "my-whoop", ts = firstTs + it, raw = raw) }

    private fun hr(count: Int, firstTs: Long) =
        (0 until count).map { HrSample(deviceId = "my-whoop", ts = firstTs + it, bpm = 60) }

    // MARK: Rule 1 — attribute sessions by END

    @Test
    fun sessionsForDay_matchesByEndNotStart() {
        val prevNightEnd = dayStart - 3_600L           // 23:00 the PREVIOUS day -> NOT matched
        val thisNightEnd = dayStart + 7 * 3_600L       // 07:00 this day -> matched
        val nextNightEnd = dayStart + 86_400L + 3_600L // 01:00 NEXT day -> NOT matched
        val matched = SkinTempBackfill.sessionsForDay(
            listOf(
                session(dayStart - 8 * 3_600L, prevNightEnd),
                session(dayStart - 3_600L, thisNightEnd),
                session(dayStart + 20 * 3_600L, nextNightEnd),
            ),
            dayStart,
        )
        assertEquals(1, matched.size)
        assertEquals(thisNightEnd, matched.first().end)
    }

    @Test
    fun sessionsForDay_inclusiveAtDayStartAndExclusiveAtDayEnd() {
        // Ending exactly at midnight IS this day's (`>= dayStart`).
        assertEquals(
            1,
            SkinTempBackfill.sessionsForDay(listOf(session(dayStart - 8 * 3_600L, dayStart)), dayStart).size,
        )
        // Ending exactly at the NEXT midnight is NOT (`< dayEnd`).
        assertEquals(
            0,
            SkinTempBackfill.sessionsForDay(
                listOf(session(dayStart + 12 * 3_600L, dayStart + 86_400L)),
                dayStart,
            ).size,
        )
    }

    @Test
    fun sessionsForDay_emptySessionsReturnsEmpty() {
        assertEquals(0, SkinTempBackfill.sessionsForDay(emptyList(), dayStart = 0L).size)
    }

    // MARK: Rule 3 — the WHOOP 4.0 anchor comes from the current window

    @Test
    fun resolveAnchor_whoop4UsesTheCurrentWindowsAnchor() {
        assertEquals(1290.0, SkinTempBackfill.resolveAnchor(DeviceFamily.WHOOP4, 1290.0))
    }

    @Test
    fun resolveAnchor_whoop4WithoutAnAnchorDeclinesRatherThanFallingBack() {
        assertNull(
            "rule 3: no anchor means decline, never the global default",
            SkinTempBackfill.resolveAnchor(DeviceFamily.WHOOP4, null),
        )
    }

    @Test
    fun resolveAnchor_whoop5IsAlwaysNilButDoesNotDeclineOnAnchorGrounds() {
        assertNull(SkinTempBackfill.resolveAnchor(DeviceFamily.WHOOP5, null))
        assertNull("a 5/MG ignores a window anchor entirely", SkinTempBackfill.resolveAnchor(DeviceFamily.WHOOP5, 1290.0))
    }

    // MARK: computeNight — the full per-night plan

    @Test
    fun computeNight_whoop5WithEnoughWornSamplesFills() {
        val candidate = SkinTempBackfill.NightCandidate(
            day = "2026-08-25",
            from = dayStart - 30 * 3_600L,
            to = dayStart + 24 * 3_600L,
            owner = "my-whoop",
        )
        val sessions = listOf(session(dayStart - 3_600L, dayStart + 7 * 3_600L))
        val firstTs = dayStart - 3_600L
        // 400 worn samples (3057 raw = 30.57 °C on the 5/MG centidegree path) at 1 Hz, with matching HR.
        val result = SkinTempBackfill.computeNight(
            candidate = candidate,
            sessions = sessions,
            hr = hr(400, firstTs),
            skinTemp = skinTemp(400, firstTs, 3057),
            family = DeviceFamily.WHOOP5,
            windowAnchorRaw = null,
            dayStart = dayStart,
        )
        assertNotNull(result.skinTempC)
        assertEquals(30.57, result.skinTempC!!, 0.01)
        assertNull(result.declineReason)
    }

    @Test
    fun computeNight_whoop4WithoutAnAnchorDeclinesEvenWithEnoughSamples() {
        val candidate = SkinTempBackfill.NightCandidate(
            day = "2026-08-25",
            from = dayStart - 30 * 3_600L,
            to = dayStart + 24 * 3_600L,
            owner = "my-whoop",
        )
        val sessions = listOf(session(dayStart - 3_600L, dayStart + 7 * 3_600L))
        val firstTs = dayStart - 3_600L
        // Enough samples to fill IF the anchor were allowed — rule 3 declines before the funnel runs.
        val result = SkinTempBackfill.computeNight(
            candidate = candidate,
            sessions = sessions,
            hr = hr(400, firstTs),
            skinTemp = skinTemp(400, firstTs, 826),
            family = DeviceFamily.WHOOP4,
            windowAnchorRaw = null,
            dayStart = dayStart,
        )
        assertNull("rule 3: no anchor means decline, never the global default", result.skinTempC)
        assertTrue("the decline reason names the anchor", result.declineReason!!.contains("anchor"))
    }

    @Test
    fun computeNight_noSessionEndingOnTheDayDeclines() {
        val candidate = SkinTempBackfill.NightCandidate("2026-08-25", 0L, 0L, "my-whoop")
        val result = SkinTempBackfill.computeNight(
            candidate = candidate,
            sessions = emptyList(),
            hr = emptyList(),
            skinTemp = emptyList(),
            family = DeviceFamily.WHOOP5,
            windowAnchorRaw = null,
            dayStart = dayStart,
        )
        assertNull(result.skinTempC)
        assertEquals("no sleep session ends on this day", result.declineReason)
    }

    @Test
    fun computeNight_tooFewWornSamplesDeclinesNamingTheFloor() {
        val candidate = SkinTempBackfill.NightCandidate("2026-08-25", 0L, 0L, "my-whoop")
        val sessions = listOf(session(dayStart - 3_600L, dayStart + 7 * 3_600L))
        val firstTs = dayStart - 3_600L
        val result = SkinTempBackfill.computeNight(
            candidate = candidate,
            sessions = sessions,
            hr = hr(100, firstTs),
            skinTemp = skinTemp(100, firstTs, 3057),
            family = DeviceFamily.WHOOP5,
            windowAnchorRaw = null,
            dayStart = dayStart,
        )
        assertNull(result.skinTempC)
        assertTrue(
            "the decline reason names the sample floor",
            result.declineReason!!.contains("worn samples"),
        )
    }

    @Test
    fun computeNight_aSessionEndingInThePreviousDayIsNotThisDays() {
        val candidate = SkinTempBackfill.NightCandidate("2026-08-25", 0L, 0L, "my-whoop")
        // Ends at 23:00 on Aug 24 but its span overlaps this day's window: without rule 1 it would be
        // matched and the night would fill with the WRONG night's temperature.
        val sessions = listOf(session(dayStart - 9 * 3_600L, dayStart - 3_600L))
        val firstTs = dayStart - 9 * 3_600L
        val result = SkinTempBackfill.computeNight(
            candidate = candidate,
            sessions = sessions,
            hr = hr(400, firstTs),
            skinTemp = skinTemp(400, firstTs, 3057),
            family = DeviceFamily.WHOOP5,
            windowAnchorRaw = null,
            dayStart = dayStart,
        )
        assertNull(result.skinTempC)
        assertEquals("no sleep session ends on this day", result.declineReason)
    }

    // MARK: Rule 2 — page the candidates

    @Test
    fun page_returnsOldestFirstWithinThePageAndStopsAtTheEnd() {
        val candidates = (0 until 120).map {
            SkinTempBackfill.NightCandidate("2026-08-%02d".format(it + 1), 0L, 0L, "my-whoop")
        }
        val page0 = SkinTempBackfill.page(candidates, page = 0, pageSize = 50)
        assertEquals(50, page0.size)
        assertEquals("2026-08-01", page0.first().day)
        assertEquals("2026-08-50", page0.last().day)

        val page2 = SkinTempBackfill.page(candidates, page = 2, pageSize = 50)
        assertEquals(20, page2.size)
        assertTrue(
            "page 3 is empty, which is where the walker stops",
            SkinTempBackfill.page(candidates, page = 3, pageSize = 50).isEmpty(),
        )
    }

    @Test
    fun page_partialLastPageAndEmptyCandidates() {
        val candidates = (0 until 75).map {
            SkinTempBackfill.NightCandidate("2026-08-%02d".format(it + 1), 0L, 0L, "my-whoop")
        }
        assertEquals(25, SkinTempBackfill.page(candidates, page = 1, pageSize = 50).size)
        assertTrue(SkinTempBackfill.page(emptyList(), page = 0).isEmpty())
    }
}
