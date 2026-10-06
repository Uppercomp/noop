package com.noop.ui

import com.noop.data.WorkoutRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the Current / Archived split to the same cases as its Swift twin.
 *
 * The oracle is `StrandTests/WorkoutScopeTests.swift`, which is the only place the two implementations are
 * compared: the same inputs and the same expectations appear on both sides, so a change to one that the
 * other does not follow fails here rather than silently showing a different ten sessions on each platform.
 *
 * Why the split is tested at all: it is a VIEW partition, never a delete. Auto-deleting real training
 * history would be irreversible on a device with no server and no cloud copy, so the invariant that
 * matters is that the two halves partition the input EXACTLY — a row the wearer cannot find in either tab
 * has effectively been deleted by the UI, which is the outcome this design exists to avoid.
 */
class WorkoutScopeTest {

    private fun row(startTs: Long, sport: String = "Running") = WorkoutRow(
        deviceId = "demo",
        startTs = startTs,
        endTs = startTs + 600,
        sport = sport,
        source = "manual",
        durationS = 600.0,
    )

    private fun ascending(count: Int, stepSeconds: Long = 3_600L) =
        (0 until count).map { row(1_700_000_000L + it * stepSeconds) }

    @Test
    fun fewerThanTheLimitPutsEverythingInCurrentAndNothingInArchived() {
        val rows = ascending(4)
        assertEquals(4, WorkoutScopeSplit.scopedRows(rows, WorkoutScope.Current, currentCount = 10).size)
        assertTrue(WorkoutScopeSplit.scopedRows(rows, WorkoutScope.Archived, currentCount = 10).isEmpty())
    }

    @Test
    fun currentHoldsTheMostRecentAndArchivedHoldsTheRest() {
        val rows = ascending(25)
        val current = WorkoutScopeSplit.scopedRows(rows, WorkoutScope.Current, currentCount = 10)
        val archived = WorkoutScopeSplit.scopedRows(rows, WorkoutScope.Archived, currentCount = 10)
        assertEquals(10, current.size)
        assertEquals(15, archived.size)
        val newestCurrent = current.minOf { it.startTs }
        val newestArchived = archived.maxOf { it.startTs }
        assertTrue(
            "every Current row is newer than every Archived one",
            newestCurrent > newestArchived,
        )
    }

    @Test
    fun theTwoScopesPartitionTheInputExactly() {
        val rows = ascending(23)
        val combined = WorkoutScopeSplit.scopedRows(rows, WorkoutScope.Current) +
            WorkoutScopeSplit.scopedRows(rows, WorkoutScope.Archived)
        assertEquals(rows.size, combined.size)
        assertEquals(rows.map { it.startTs }.toSet(), combined.map { it.startTs }.toSet())
    }

    @Test
    fun tiedStartTimesCannotOverfillCurrent() {
        // Ranking, not a cutoff timestamp: twelve sessions that all start in the same second must still
        // yield exactly ten in Current. A threshold comparison would hand back all twelve.
        val rows = (0 until 12).map { row(1_700_000_000L, sport = "Sport$it") }
        assertEquals(10, WorkoutScopeSplit.scopedRows(rows, WorkoutScope.Current, currentCount = 10).size)
        assertEquals(2, WorkoutScopeSplit.scopedRows(rows, WorkoutScope.Archived, currentCount = 10).size)
    }

    @Test
    fun orderIsPreserved() {
        // The caller's sort decides what the screen shows; the split only chooses membership.
        val rows = ascending(25).sortedByDescending { it.startTs }
        val current = WorkoutScopeSplit.scopedRows(rows, WorkoutScope.Current)
        assertEquals(current.map { it.startTs }, current.map { it.startTs }.sortedDescending())
    }
}
