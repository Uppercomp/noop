package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * iOS parity (#198): re-tapping the active tab scrolls that tab's root to the top — and the token that
 * drives it is counted PER ROUTE.
 *
 * These pin the store rather than the Compose plumbing, because the store is where the two decisions live
 * that the shell and the scaffolds cannot check for themselves:
 *
 *  - one counter shared across tabs would scroll whichever tab happened to be on screen, so a re-tap of
 *    Trends would move Today;
 *  - a tab that has merely been switched TO must report its own stored count, and the scaffold's
 *    change-only reaction is what stops it acting on that count when it is read for the first time.
 *
 * The second half of that is deliberately NOT asserted here: it belongs to `ScrollToTopOnReselect`, which
 * needs a composition. What is asserted is the value it reads, so a regression in the store is attributed
 * here instead of surfacing as "the tab jumped" on a device.
 */
class ScrollToTopStoreTest {

    @Before
    fun reset() {
        // The store is an `object`, so state would otherwise outlive a case. JUnit4 does not promise method
        // order, which is what makes this necessary rather than tidy.
        ScrollToTopStore.resetForTest()
    }

    @Test
    fun `a tab reports no signal until it is reselected`() {
        ScrollToTopStore.observeRoute("today")
        assertEquals(0, ScrollToTopStore.signal)
    }

    @Test
    fun `a reselect counts up so two in a row both land`() {
        ScrollToTopStore.observeRoute("today")
        ScrollToTopStore.reselect("today")
        assertEquals(1, ScrollToTopStore.signal)
        // A Boolean would already be true here and the second tap would do nothing; the count is what
        // makes a repeated re-tap work.
        ScrollToTopStore.reselect("today")
        assertEquals(2, ScrollToTopStore.signal)
    }

    @Test
    fun `a reselect of another tab does not move the visible one`() {
        ScrollToTopStore.observeRoute("today")
        ScrollToTopStore.reselect("trends")
        assertEquals(0, ScrollToTopStore.signal)
    }

    @Test
    fun `switching to a reselected tab reports that tab's own count`() {
        ScrollToTopStore.observeRoute("trends")
        ScrollToTopStore.reselect("trends")

        // Today has never been reselected, so landing on it must report nothing.
        ScrollToTopStore.observeRoute("today")
        assertEquals(0, ScrollToTopStore.signal)

        // Trends remembers its own count, so the scaffold sees a value to compare against its seeded one.
        ScrollToTopStore.observeRoute("trends")
        assertEquals(1, ScrollToTopStore.signal)
    }

    @Test
    fun `an unknown route reports no signal`() {
        // The shell reports `null` before the first back-stack entry resolves, and any route it is asked
        // about that has never been reselected must be silent rather than throwing.
        ScrollToTopStore.observeRoute(null)
        assertEquals(0, ScrollToTopStore.signal)
    }
}
