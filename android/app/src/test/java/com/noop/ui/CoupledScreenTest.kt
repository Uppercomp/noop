package com.noop.ui

import com.noop.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the pure display-only helpers behind the Coupled view (task #43) so they stay byte-identical to the
 * Swift [CoupledView] twin:
 *  - the OPTIMAL recovery->strain band mapping (green >= 67 -> 14-18, yellow 34-66 -> 10-14, red < 34 -> 4-10),
 *  - the strain band word thresholds (Light / Moderate / Strenuous / High at 6/10/14 of 21, the four
 *    bands and the words the iOS `CoupledView.strainBandWord` uses),
 *  - the hours:minutes formatter.
 * These are display-only reads (never fed back into scoring), so the values here ARE the contract.
 */
class CoupledScreenTest {

    @Test fun optimalRange_greenDay_suggests14to18() {
        assertEquals("14 to 18", optimalStrainRangeText(90.0))
        assertEquals("14 to 18", optimalStrainRangeText(67.0)) // lower boundary of green is inclusive
    }

    @Test fun optimalRange_yellowDay_suggests10to14() {
        assertEquals("10 to 14", optimalStrainRangeText(66.9))
        assertEquals("10 to 14", optimalStrainRangeText(50.0))
        assertEquals("10 to 14", optimalStrainRangeText(34.0)) // lower boundary of yellow is inclusive
    }

    @Test fun optimalRange_redDay_suggests4to10() {
        assertEquals("4 to 10", optimalStrainRangeText(33.9))
        assertEquals("4 to 10", optimalStrainRangeText(10.0))
        assertEquals("4 to 10", optimalStrainRangeText(0.0))
    }

    @Test fun optimalRange_noRecovery_isNoData() {
        assertNull(optimalStrainRange(null))
        assertEquals("No Data", optimalStrainRangeText(null))
    }

    @Test fun optimalRange_bands_matchTheStruct() {
        assertEquals(OptimalStrainRange(14, 18), optimalStrainRange(80.0))
        assertEquals(OptimalStrainRange(10, 14), optimalStrainRange(40.0))
        assertEquals(OptimalStrainRange(4, 10), optimalStrainRange(5.0))
    }

    @Test fun strainBandWord_thresholds_matchTheGauge() {
        // The FOUR bands of the iOS `CoupledView.strainBandWord`, at 6 and 10 and 14 of 21 — not the five
        // uppercase bands Android used to invent (which put a day at 16/21 in a band iOS does not have).
        assertEquals(DisplayText.Resource(R.string.l10n_coupled_screen_light_a36ef8ab), strainBandWord(4.0 / 21))
        assertEquals(DisplayText.Resource(R.string.l10n_coupled_screen_moderate_ea8b09c2), strainBandWord(8.0 / 21))
        assertEquals(DisplayText.Dynamic("Strenuous"), strainBandWord(12.0 / 21))
        assertEquals(DisplayText.Resource(R.string.l10n_coupled_screen_high_b1a5954a), strainBandWord(16.0 / 21))
        assertEquals(DisplayText.Resource(R.string.l10n_coupled_screen_high_b1a5954a), strainBandWord(20.0 / 21))
        // Boundaries are exclusive-below: exactly 6/21 rolls into the next band.
        assertEquals(DisplayText.Resource(R.string.l10n_coupled_screen_moderate_ea8b09c2), strainBandWord(6.0 / 21))
        assertEquals(DisplayText.Dynamic("Strenuous"), strainBandWord(10.0 / 21))
        assertEquals(DisplayText.Resource(R.string.l10n_coupled_screen_high_b1a5954a), strainBandWord(14.0 / 21))
        assertEquals(DisplayText.Resource(R.string.l10n_coupled_screen_high_b1a5954a), strainBandWord(18.0 / 21))
    }

    @Test fun hoursMinutes_formatsAsHandM() {
        assertEquals("6h 42m", hoursMinutes(402.0))
        assertEquals("7h 50m", hoursMinutes(470.0))
        assertEquals("0h 5m", hoursMinutes(5.0))
        assertEquals("8h 0m", hoursMinutes(480.0))
    }

    /**
     * The tube fill beside the OPTIMAL range (#43). Twin of Swift CoupledView.optimalUpperFraction, which
     * is `max(0, min(1, Double(band.upperBound) / 21))` - so these expectations are the Swift expression
     * evaluated on the approved bands, not values read back off the Kotlin.
     */
    @Test
    fun optimalUpperFractionMatchesTheSwiftTwin() {
        assertEquals(18.0 / 21.0, optimalUpperFraction(90.0), 1e-12)
        assertEquals(18.0 / 21.0, optimalUpperFraction(67.0), 1e-12)   // green, inclusive lower edge
        assertEquals(14.0 / 21.0, optimalUpperFraction(66.9), 1e-12)
        assertEquals(14.0 / 21.0, optimalUpperFraction(34.0), 1e-12)   // yellow, inclusive lower edge
        assertEquals(10.0 / 21.0, optimalUpperFraction(33.9), 1e-12)
        assertEquals(10.0 / 21.0, optimalUpperFraction(0.0), 1e-12)
    }

    @Test
    fun optimalUpperFractionIsEmptyWhenRecoveryIsUnknown() {
        // A calibrating / unscored day prints the no-data token, so the tube must be EMPTY rather than
        // fabricate a band the text alongside it is refusing to name.
        assertEquals(0.0, optimalUpperFraction(null), 1e-12)
    }

    @Test
    fun optimalUpperFractionNeverLeavesTheAxis() {
        // Every approved band is inside 0..21, so the clamp is defensive - but it is the reason a future
        // band change cannot push the tube past full.
        for (r in listOf(null, -50.0, 0.0, 33.9, 34.0, 66.9, 67.0, 100.0, 1000.0)) {
            val f = optimalUpperFraction(r)
            assertTrue("fraction out of range for recovery=$r: $f", f in 0.0..1.0)
        }
    }

    /**
     * The GlowRing target arc range. Sibling of [optimalUpperFraction], returning the full band as a
     * 0..1 range instead of just the upper bound.
     */
    @Test
    fun optimalFractionRange_greenDay_returns14to18() {
        val range = optimalFractionRange(80.0)
        assertEquals(14.0f / 21.0f, range!!.start, 1e-6f)
        assertEquals(18.0f / 21.0f, range.endInclusive, 1e-6f)
    }

    @Test
    fun optimalFractionRange_yellowDay_returns10to14() {
        val range = optimalFractionRange(50.0)
        assertEquals(10.0f / 21.0f, range!!.start, 1e-6f)
        assertEquals(14.0f / 21.0f, range.endInclusive, 1e-6f)
    }

    @Test
    fun optimalFractionRange_redDay_returns4to10() {
        val range = optimalFractionRange(20.0)
        assertEquals(4.0f / 21.0f, range!!.start, 1e-6f)
        assertEquals(10.0f / 21.0f, range.endInclusive, 1e-6f)
    }

    @Test
    fun optimalFractionRange_noRecovery_returnsNull() {
        assertNull(optimalFractionRange(null))
    }

    @Test
    fun optimalFractionRange_neverLeavesAxis() {
        for (r in listOf(null, -50.0, 0.0, 33.9, 34.0, 66.9, 67.0, 100.0, 1000.0)) {
            val range = optimalFractionRange(r)
            if (range != null) {
                assertTrue("start out of range for recovery=$r: ${range.start}", range.start in 0f..1f)
                assertTrue("end out of range for recovery=$r: ${range.endInclusive}", range.endInclusive in 0f..1f)
                assertTrue("start > end for recovery=$r", range.start <= range.endInclusive)
            }
        }
    }
}
