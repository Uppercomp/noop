package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * iOS parity: `RootTabView.tabSwipeGesture`'s decisions, ported to [tabSwipeTarget].
 *
 * The gesture itself needs a device, so what is pinned here is the pure decision — the thresholds, the two
 * carve-outs, and the clamp. A regression in any of them is otherwise only visible as "swiping felt wrong"
 * on hardware, which is exactly the class of bug this table exists to catch.
 */
class TabSwipeTargetTest {

    private val threshold = 60f

    /** Comfortably past the threshold and clearly horizontal. */
    private val flick = 200f

    @Test
    fun `dragging left moves to the next tab`() {
        // dx < 0 means the content moved left, which advances.
        assertEquals(2, tabSwipeTarget(index = 1, tabCount = 5, dx = -flick, dy = 10f, thresholdPx = threshold))
    }

    @Test
    fun `dragging right moves to the previous tab`() {
        assertEquals(1, tabSwipeTarget(index = 2, tabCount = 5, dx = flick, dy = 10f, thresholdPx = threshold))
    }

    @Test
    fun `Today is carved out because it swipes between days`() {
        // iOS: `guard selectedTab != 0 else { return }`.
        assertNull(tabSwipeTarget(index = 0, tabCount = 5, dx = -flick, dy = 0f, thresholdPx = threshold))
        assertNull(tabSwipeTarget(index = 0, tabCount = 5, dx = flick, dy = 0f, thresholdPx = threshold))
    }

    @Test
    fun `a drag under the threshold does not change tabs`() {
        assertNull(tabSwipeTarget(index = 2, tabCount = 5, dx = -59f, dy = 0f, thresholdPx = threshold))
    }

    @Test
    fun `a mostly-vertical drag is a scroll, not a tab change`() {
        // iOS: `guard abs(dx) > abs(dy) * 1.6`. 120 horizontal against 100 vertical is 1.2 — too vertical.
        assertNull(tabSwipeTarget(index = 2, tabCount = 5, dx = -120f, dy = 100f, thresholdPx = threshold))
        // Just past the ratio, so the same drag becomes a flick.
        assertEquals(3, tabSwipeTarget(index = 2, tabCount = 5, dx = -170f, dy = 100f, thresholdPx = threshold))
    }

    @Test
    fun `the ends of the tab order do not wrap`() {
        // Already on the last tab, flicking forward stays put (reported as "no move", not as a clamp to self).
        assertNull(tabSwipeTarget(index = 4, tabCount = 5, dx = -flick, dy = 0f, thresholdPx = threshold))
        // Index 1 is the first tab that CAN swipe, and flicking back targets index 0 — Today.
        assertEquals(0, tabSwipeTarget(index = 1, tabCount = 5, dx = flick, dy = 0f, thresholdPx = threshold))
    }

    @Test
    fun `the clamp follows the tabs that exist, not a fixed tag range`() {
        // Coach switched OFF: four tabs. iOS clamps the target to the fixed tag range 0...4, so a forward
        // flick from the third tab lands on unclaimed tag 3 and shows an empty tab. This must not.
        assertEquals(3, tabSwipeTarget(index = 2, tabCount = 4, dx = -flick, dy = 0f, thresholdPx = threshold))
        assertNull(tabSwipeTarget(index = 3, tabCount = 4, dx = -flick, dy = 0f, thresholdPx = threshold))
    }
}
