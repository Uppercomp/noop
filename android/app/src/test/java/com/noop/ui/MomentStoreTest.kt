package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the moments list's shape.
 *
 * The double-tap card shows the newest few markers and a Clear action. Two things could silently go wrong
 * and neither is visible in a screenshot of an empty list: the CAP (an unbounded string rewritten on every
 * double-tap is a slow leak in SharedPreferences) and the parse (a corrupt preference must degrade to an
 * empty list rather than taking the Automations screen down).
 *
 * The display order is pinned too: iOS prints `moments.suffix(5).reversed()`, so the newest marker is the
 * FIRST line. Getting that backwards would look plausible and be wrong.
 */
class MomentStoreTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun theNewestMarkerIsListedFirst() {
        val moments = listOf(t0, t0 + 1_000, t0 + 2_000)
        assertEquals(listOf(t0 + 2_000, t0 + 1_000), MomentStore.recent(moments, show = 2))
    }

    @Test
    fun theCapKeepsTheNewestAndDropsTheOldest() {
        val full = (1..MomentStore.MAX).map { t0 + it }
        val next = MomentStore.append(full, t0 + 1_000, cap = MomentStore.MAX)
        assertEquals(MomentStore.MAX, next.size)
        assertEquals(t0 + 1_000, next.last())
        assertTrue("the oldest marker fell off", next.first() > t0 + 1)
    }

    @Test
    fun aRoundTripSurvivesEncodeAndDecode() {
        val moments = listOf(t0, t0 + 3_600_000)
        assertEquals(moments, MomentStore.decode(MomentStore.encode(moments)))
    }

    @Test
    fun aCorruptOrAbsentPreferenceDegradesToAnEmptyList() {
        assertEquals(emptyList<Long>(), MomentStore.decode(null))
        assertEquals(emptyList<Long>(), MomentStore.decode(""))
        assertEquals(emptyList<Long>(), MomentStore.decode("not-a-number"))
        // A partially corrupt value keeps what it can rather than discarding every marker.
        assertEquals(listOf(t0), MomentStore.decode("$t0,oops"))
    }
}
