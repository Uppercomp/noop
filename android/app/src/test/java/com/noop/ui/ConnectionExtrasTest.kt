package com.noop.ui

import com.noop.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the four Connection rows Android's registry spec does not carry.
 *
 * Why these live outside `TestCentreLiveReadouts.rows`: the registry's `liveReadout` list is IDENTICAL in
 * `TestModeRegistry.kt` and its Swift twin — a tested contract, asserted by
 * `everyRegistryDeclaredIdHasExactlyOnePresentationMapping` — so the extra rows cannot be added as spec
 * ids without diverging the two registries. iOS's Connection panel hand-writes its seven rows
 * (`TestCentreView.swift:1084-1105`), so Android renders the same four as a bespoke addition for that one
 * domain.
 *
 * The parsers were already written and twin-tested; what was missing was any production caller, which this
 * suite now supplies. `Last frame` is deliberately absent (no `lastFrameAtUnix` on Android's LiveState), so
 * a reader comparing the two panels should see six rows here against iOS's seven.
 */
class ConnectionExtrasTest {

    private val saneClock = 1_767_225_600L          // 2026-01-01, comfortably past the 1972 RTC ceiling
    private val epochClock = 12_345L                // an RTC that never latched: counts up from 1970

    @Test
    fun rowsDrainedAreReadFromTheSessionTallyAndTheInstallLifetimeCounter() {
        val extras = TestCentreLiveReadouts.connectionExtras(
            tail = listOf("[connection] sessionRows=1200"),
            logLines = emptyList(),
            strapNewestUnix = null,
            batteryPct = null,
            cumulativeDrainedRows = 54_321L,
        )

        assertEquals(
            listOf(
                "rowsDrainedSession", "rowsDrainedAllTime", "clockLatched",
            ),
            extras.rows.map { it.id },
        )
        assertEquals(
            DisplayText.Resource(R.string.test_centre_rows_drained_session),
            extras.rows[0].label,
        )
        assertEquals(DisplayText.Dynamic("1200"), extras.rows[0].value)
        assertEquals(
            DisplayText.Resource(R.string.test_centre_rows_drained_all_time),
            extras.rows[1].label,
        )
        assertEquals(DisplayText.Dynamic("54321"), extras.rows[1].value)
        assertEquals(
            DisplayText.Resource(R.string.test_centre_clock_latched),
            extras.rows[2].label,
        )
    }

    @Test
    fun aSessionThatDrainedNothingSaysSoRatherThanShowingZero() {
        val extras = TestCentreLiveReadouts.connectionExtras(
            tail = listOf("[connection] connect up"),
            logLines = emptyList(),
            strapNewestUnix = null,
            batteryPct = null,
            cumulativeDrainedRows = 0L,
        )

        // The same words the Last-offload row uses, so the two cannot word "nothing happened" differently.
        assertEquals(
            DisplayText.Resource(R.string.l10n_test_centre_live_readouts_no_offload_yet_60e9e982),
            extras.rows[0].value,
        )
    }

    @Test
    fun aLatchIsClaimedOnlyFromASignalThatActuallyReadsPast1972() {
        val fromCorrelation = TestCentreLiveReadouts.connectionExtras(
            tail = emptyList(),
            logLines = listOf("[connection] Clock correlated: device=$saneClock wall=$saneClock"),
            strapNewestUnix = null,
            batteryPct = null,
            cumulativeDrainedRows = 0L,
        )
        assertEquals(DisplayText.Dynamic("yes"), fromCorrelation.rows[2].value)
        assertNull("a sane clock is not a fault", fromCorrelation.rtcWarning)

        // A 5/MG never populates the correlated device clock, so the ONLY evidence is how the strap dated
        // its records. That branch must still claim a latch rather than reporting "waiting" forever.
        val fromStrapRecords = TestCentreLiveReadouts.connectionExtras(
            tail = emptyList(),
            logLines = emptyList(),
            strapNewestUnix = saneClock,
            batteryPct = null,
            cumulativeDrainedRows = 0L,
        )
        assertEquals(DisplayText.Dynamic("yes"), fromStrapRecords.rows[2].value)

        val nothingYet = TestCentreLiveReadouts.connectionExtras(
            tail = emptyList(),
            logLines = emptyList(),
            strapNewestUnix = null,
            batteryPct = null,
            cumulativeDrainedRows = 0L,
        )
        assertEquals(DisplayText.Dynamic("no (waiting for the strap clock)"), nothingYet.rows[2].value)
        assertNull("no signal is not evidence of a fault", nothingYet.rtcWarning)
    }

    @Test
    fun anEpochEraClockRaisesThePlainWordsRemedyAndItsWordingTracksTheBattery() {
        val flat = TestCentreLiveReadouts.connectionExtras(
            tail = emptyList(),
            logLines = listOf("[connection] Clock correlated: device=$epochClock wall=$saneClock"),
            strapNewestUnix = null,
            batteryPct = null,
            cumulativeDrainedRows = 0L,
        )
        assertEquals(DisplayText.Dynamic("no (RTC reads 1970/71)"), flat.rows[2].value)
        val flatWarning = requireNotNull(flat.rtcWarning) as DisplayText.Dynamic
        assertTrue(flatWarning.value.startsWith("Strap clock reads 1970/71"))
        assertTrue("a flat strap is told to charge it", flatWarning.value.contains("Charge the strap to 100%"))

        val alreadyCharged = TestCentreLiveReadouts.connectionExtras(
            tail = emptyList(),
            logLines = listOf("[connection] Clock correlated: device=$epochClock wall=$saneClock"),
            strapNewestUnix = null,
            batteryPct = 100.0,
            cumulativeDrainedRows = 0L,
        )
        val chargedWarning = requireNotNull(alreadyCharged.rtcWarning) as DisplayText.Dynamic
        assertTrue(
            "charging an already-charged strap is not the remedy",
            chargedWarning.value.contains("already charged"),
        )
    }
}
