package com.noop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the behaviour of [LiftSessionEngine], the Kotlin twin of `Strand/Data/LiftSessionEngine.swift`.
 *
 * WHY THESE ASSERTIONS AND NOT AN ORACLE. `docs/CONTRIBUTING.md` asks for a port of this kind to be
 * pinned by compiling the Swift twin standalone and pasting its stdout as the expected literal here.
 * No Swift toolchain exists on the machine this was ported on, so that could not be done. What follows
 * instead asserts the INVARIANTS THE SWIFT SOURCE STATES EXPLICITLY in its own comments — the ones a
 * careless translation would break while still looking right. That is weaker than an oracle: it cannot
 * catch a rule the Swift comments describe imprecisely. Strengthen it to a real oracle on a machine
 * with Xcode.
 *
 * Every clock value is an explicit parameter, exactly as in the Swift engine, so each test drives a
 * known timeline rather than sleeping.
 */
class LiftSessionEngineTest {

    private fun engine(vararg items: LiftPlanItem, startTs: Int = 1_000) =
        LiftSessionEngine(items.toList(), startTs)

    // MARK: - The sheet

    @Test
    fun `advance from the warm-up starts the first pending slot`() {
        val e = engine(LiftPlanItem("Squat", plannedSets = 3))
        assertNull("a fresh session is in the warm-up", e.currentSlot)
        e.advance(now = 1_000)
        assertEquals(LiftSlot(0, 1), e.currentSlot)
    }

    @Test
    fun `every slot of every exercise is on the sheet in plan order`() {
        val e = engine(
            LiftPlanItem("Squat", plannedSets = 2),
            LiftPlanItem("Bench", plannedSets = 3),
        )
        assertEquals(
            listOf(
                LiftSlot(0, 1), LiftSlot(0, 2),
                LiftSlot(1, 1), LiftSlot(1, 2), LiftSlot(1, 3),
            ),
            e.allSlots,
        )
        assertEquals(5, e.plannedWorkingSets)
    }

    /**
     * The rule a real session taught the feature: plan order alone throws you back to the machine you
     * just walked away from, after every set. The Swift comment records the report verbatim — "when I
     * double-tap for the next set, it reverts to the first set of the exercise I couldn't do earlier."
     */
    @Test
    fun `after a set the session stays on the current exercise, not plan order`() {
        val e = engine(
            LiftPlanItem("Skipped", plannedSets = 1),
            LiftPlanItem("Current", plannedSets = 2),
        )
        // Start exercise 2 (index 1) deliberately, skipping index 0 because the machine is busy.
        e.start(LiftSlot(1, 1), now = 1_000)
        e.advance(now = 1_060)          // complete it → resting
        assertEquals(LiftSlot(1, 1), e.currentSlot)

        e.advance(now = 1_200)          // out of the rest
        assertEquals(
            "the next set must be the same exercise's, NOT the skipped exercise 0",
            LiftSlot(1, 2),
            e.currentSlot,
        )
    }

    @Test
    fun `once the current exercise is done the session falls back to the first pending slot`() {
        val e = engine(
            LiftPlanItem("Skipped", plannedSets = 1),
            LiftPlanItem("Current", plannedSets = 1),
        )
        e.start(LiftSlot(1, 1), now = 1_000)
        e.advance(now = 1_060)
        e.advance(now = 1_200)          // rest done → nothing left on exercise 1
        assertEquals("the skipped exercise is not forgotten, just deferred", LiftSlot(0, 1), e.currentSlot)
    }

    // MARK: - Timing and rest

    @Test
    fun `completing a set records its timing and nothing else`() {
        val e = engine(LiftPlanItem("Squat", plannedSets = 1))
        e.advance(now = 1_000)          // start set 1 at 1000
        e.advance(now = 1_060)          // finish it at 1060

        assertEquals(1, e.sets.size)
        val set = e.sets.single()
        assertEquals(1_000, set.startTs)
        assertEquals(1_060, set.endTs)
        // GREY STAYS GREY: a number becomes the set's own only when it is typed.
        assertNull(set.weightKg)
        assertNull(set.reps)
        assertNull(set.rpe)
        assertFalse(set.isWarmup)
        assertNull("the rest is still running, so none is recorded yet", set.restSec)
    }

    @Test
    fun `rest is an absolute end instant, not a decrementing counter`() {
        val e = engine(LiftPlanItem("Squat", plannedSets = 2, plannedRestSec = 120))
        e.advance(now = 1_000)
        e.advance(now = 1_060)          // rest ends at 1060 + 120 = 1180
        assertEquals(120, e.restRemaining(now = 1_060))
        assertEquals(30, e.restRemaining(now = 1_150))
    }

    /**
     * "Floored rather than negative so an overrun rest reads 0:00 and waits, which is what it actually
     * means: the user has not moved on yet."
     */
    @Test
    fun `an overrun rest floors at zero rather than going negative`() {
        val e = engine(LiftPlanItem("Squat", plannedSets = 2))
        e.advance(now = 1_000)
        e.advance(now = 1_060)
        assertEquals(0, e.restRemaining(now = 9_999))
    }

    @Test
    fun `restRemaining is null when the session is not resting`() {
        val e = engine(LiftPlanItem("Squat", plannedSets = 2))
        assertNull(e.restRemaining(1_000))
        e.advance(now = 1_000)          // working
        assertNull(e.restRemaining(1_010))
    }

    /** "Record what was ACTUALLY rested — the one thing only the taps can know." */
    @Test
    fun `leaving a rest records the rest actually taken, not the planned rest`() {
        val e = engine(LiftPlanItem("Squat", plannedSets = 2, plannedRestSec = 120))
        e.advance(now = 1_000)
        e.advance(now = 1_060)          // planned rest ends at 1180
        e.advance(now = 1_300)          // the user actually waited 240s

        assertEquals(240, e.sets.single().restSec)
    }

    // MARK: - The grey numbers

    /**
     * The documented preference order: this exercise earlier in THIS session, then the same set number
     * LAST session, then the program's target.
     */
    @Test
    fun `carry prefers this session, then last session, then the program target`() {
        val e = engine(
            LiftPlanItem("Squat", plannedSets = 3, targetWeightKg = 100.0, targetRepsLow = 5),
        )
        val lastSession = mapOf(
            1 to LiftSetCarry(weightKg = 90.0, reps = 8),
            2 to LiftSetCarry(weightKg = 95.0, reps = 6),
        )

        assertEquals(
            "set 1 has no in-session history, so last session outranks the program target",
            LiftSetCarry(90.0, 8),
            e.carry(LiftSlot(0, 1), lastSession),
        )

        // Perform set 1 and type real numbers into it.
        e.advance(now = 1_000)
        e.advance(now = 1_060)
        e.updateSet(LiftSlot(0, 1), weightKg = 102.5, reps = 5, rpe = 8.0, isWarmup = false)

        assertEquals(
            "set 2 follows set 1 as performed, which outranks last session",
            LiftSetCarry(102.5, 5),
            e.carry(LiftSlot(0, 2), lastSession),
        )
        assertEquals(
            // The nearest EARLIER PERFORMED set — set 2 was never done, so this walks back to set 1.
            // A first draft of this test expected the program target here and failed, which is the
            // engine being right and the expectation being wrong: `previousSetInSession` searches
            // downward for the first recorded set, it does not require the immediately preceding one.
            "set 3 takes the nearest earlier PERFORMED set, which is set 1",
            LiftSetCarry(102.5, 5),
            e.carry(LiftSlot(0, 3), lastSession),
        )

        // With nothing performed and nothing known from last session, the program target is the floor.
        assertEquals(
            "the program target is the last layer of the preference order",
            LiftSetCarry(100.0, 5),
            engine(LiftPlanItem("Squat", targetWeightKg = 100.0, targetRepsLow = 5))
                .carry(LiftSlot(0, 1), emptyMap()),
        )
    }

    /**
     * "set 2 follows set 1 whether or not anything was typed there" — so a set completed without
     * numbers still carries its GREY values forward, rather than falling through to last session.
     */
    @Test
    fun `an untyped completed set still carries its grey numbers to the next set`() {
        val e = engine(LiftPlanItem("Squat", plannedSets = 2, targetWeightKg = 100.0, targetRepsLow = 5))
        val lastSession = mapOf(1 to LiftSetCarry(90.0, 8), 2 to LiftSetCarry(95.0, 6))
        e.advance(now = 1_000)
        e.advance(now = 1_060)          // set 1 done, nothing typed

        assertEquals(LiftSetCarry(90.0, 8), e.values(LiftSlot(0, 1), lastSession))
        assertEquals(LiftSetCarry(90.0, 8), e.carry(LiftSlot(0, 2), lastSession))
    }

    /** RPE is carried by neither layer: it is structurally absent from [LiftSetCarry]. */
    @Test
    fun `typing an RPE into a set never carries it to the next one`() {
        val e = engine(LiftPlanItem("Squat", plannedSets = 2, targetRpe = 8.0))
        e.advance(now = 1_000)
        e.advance(now = 1_060)
        e.updateSet(LiftSlot(0, 1), weightKg = 100.0, reps = 5, rpe = 9.5, isWarmup = false)
        // LiftSetCarry has no rpe field at all; the next set's grey numbers carry only weight and reps.
        assertEquals(LiftSetCarry(100.0, 5), e.carry(LiftSlot(0, 2), emptyMap()))
    }

    // MARK: - Changing the sheet

    @Test
    fun `a fifth set can be added to a line that planned four`() {
        val e = engine(LiftPlanItem("Squat", plannedSets = 4))
        assertTrue(e.addSet(toExercise = 0))
        assertEquals(5, e.planItem(LiftSlot(0, 1))!!.targetSets)
        assertEquals(5, e.allSlots.size)
    }

    @Test
    fun `adding sets stops at the bound`() {
        val e = engine(LiftPlanItem("Squat", plannedSets = LiftSessionEngine.maxSetsPerExercise))
        assertFalse(e.addSet(toExercise = 0))
        assertEquals(LiftSessionEngine.maxSetsPerExercise, e.planItem(LiftSlot(0, 1))!!.targetSets)
    }

    /**
     * A completed set is DATA. Dropping it from a button whose job is to edit a plan would delete a set
     * the user actually performed.
     */
    @Test
    fun `the last set cannot be dropped once it has been performed`() {
        val e = engine(LiftPlanItem("Squat", plannedSets = 2))
        assertTrue("nothing performed yet, so the pending last set is removable", e.canRemoveSet(fromExercise = 0))
        e.start(LiftSlot(0, 2), now = 1_000)
        e.advance(now = 1_060)
        assertFalse("set 2 is now recorded data", e.canRemoveSet(fromExercise = 0))
        assertFalse(e.removeSet(fromExercise = 0))
    }

    @Test
    fun `the set being stood on cannot be renumbered away`() {
        val e = engine(LiftPlanItem("Squat", plannedSets = 2))
        e.start(LiftSlot(0, 2), now = 1_000)     // working set 2, nothing recorded yet
        assertFalse("removing it here would renumber the set the session is standing on", e.canRemoveSet(fromExercise = 0))
    }

    @Test
    fun `a single-set line cannot be emptied`() {
        val e = engine(LiftPlanItem("Squat", plannedSets = 1))
        assertFalse(e.canRemoveSet(fromExercise = 0))
    }

    @Test
    fun `a line added during the session starts at one set and is undoable`() {
        val e = engine(LiftPlanItem("Squat", plannedSets = 3))
        assertTrue(e.addExercise(LiftPlanItem("Curl", plannedSets = 5, addedInSession = true)))
        val added = e.plan.last()
        assertEquals("Curl", added.exercise)
        assertEquals("any set count the caller gives is ignored", 1, added.targetSets)
        assertTrue("finishing offers to write it back to the program", added.addedInSession)
        assertTrue(e.canUndo)
        e.undo()
        assertEquals(1, e.plan.size)
    }

    // MARK: - Undo

    /**
     * The plan travels in the snapshot with the stage and the sets. Undoing back past a removed set
     * must not leave the session working a slot the sheet no longer draws.
     */
    @Test
    fun `undo restores the plan together with the stage`() {
        val e = engine(LiftPlanItem("Squat", plannedSets = 2))
        e.advance(now = 1_000)                  // start set 1
        e.addSet(toExercise = 0)                // line now holds 3
        assertEquals(3, e.planItem(LiftSlot(0, 1))!!.targetSets)
        assertEquals(3, e.allSlots.size)

        e.undo()
        assertEquals("the set count rolls back with the stage", 2, e.planItem(LiftSlot(0, 1))!!.targetSets)
        assertEquals(2, e.allSlots.size)
        assertEquals("and the working set survives that rollback", LiftSlot(0, 1), e.currentSlot)
    }

    @Test
    fun `undo on an empty history is a no-op`() {
        val e = engine(LiftPlanItem("Squat", plannedSets = 1))
        assertFalse(e.canUndo)
        e.undo()
        assertEquals(LiftSessionEngine.Stage.Warmup, e.stage)
        assertNull(e.currentSlot)
    }

    // MARK: - Re-opening and finishing

    /** "Starting an already-completed slot re-opens it for a redo, dropping its record." */
    @Test
    fun `re-opening a completed set does not count it twice`() {
        val e = engine(LiftPlanItem("Squat", plannedSets = 2))
        e.advance(now = 1_000)
        e.advance(now = 1_060)
        assertEquals(1, e.sets.size)

        e.start(LiftSlot(0, 1), now = 1_200)
        assertEquals("the previous record is dropped, not duplicated", 0, e.sets.size)
        e.advance(now = 1_260)
        assertEquals(1, e.sets.size)
        assertEquals(1_200, e.sets.single().startTs)
    }

    @Test
    fun `starting a set while another is in progress abandons it without recording anything`() {
        val e = engine(LiftPlanItem("Squat", plannedSets = 2), LiftPlanItem("Bench", plannedSets = 1))
        e.start(LiftSlot(0, 1), now = 1_000)
        e.start(LiftSlot(1, 1), now = 1_030)    // walked away mid-set
        assertEquals(0, e.sets.size)
        assertEquals(LiftSlot(1, 1), e.currentSlot)
    }

    /** The rest that was running is closed out first, so its measured duration is not silently lost. */
    @Test
    fun `finishing closes out a running rest`() {
        val e = engine(LiftPlanItem("Squat", plannedSets = 2, plannedRestSec = 120))
        e.advance(now = 1_000)
        e.advance(now = 1_060)          // resting from 1060
        e.finish(now = 1_200)

        assertEquals(140, e.sets.single().restSec)
        assertTrue(e.isFinished)
        assertNull(e.currentSlot)
    }

    /** Sheet complete → stay put rather than inventing a stage; the time until finish is the cool-down. */
    @Test
    fun `advancing past the last set stays in a rest rather than inventing a stage`() {
        val e = engine(LiftPlanItem("Squat", plannedSets = 1, plannedRestSec = 60))
        e.advance(now = 1_000)
        e.advance(now = 1_060)          // completes the only set → resting
        assertTrue(e.allCompleted)

        e.advance(now = 1_200)
        assertFalse("the session ends when the user says it does", e.isFinished)
        assertEquals(LiftSlot(0, 1), e.currentSlot)
        assertEquals(0, e.restRemaining(now = 1_200))
    }

    @Test
    fun `a finished session refuses further edits`() {
        val e = engine(LiftPlanItem("Squat", plannedSets = 2))
        e.finish(now = 1_000)
        assertFalse(e.addExercise(LiftPlanItem("Curl")))
        e.advance(now = 1_100)
        assertEquals("advance is inert once finished — the user has ended the session", 0, e.sets.size)
    }

    // MARK: - Counting

    @Test
    fun `warm-up sets are excluded from the completed working-set count`() {
        val e = engine(LiftPlanItem("Squat", plannedSets = 2))
        e.advance(now = 1_000)
        e.advance(now = 1_060)
        e.updateSet(LiftSlot(0, 1), weightKg = 60.0, reps = 8, rpe = null, isWarmup = true)
        assertEquals(0, e.completedWorkingSets)

        e.advance(now = 1_200)          // start set 2
        e.advance(now = 1_260)          // complete it as a working set
        assertEquals(1, e.completedWorkingSets)
    }

    @Test
    fun `unperformed slots are exactly the ones with no record`() {
        val e = engine(LiftPlanItem("Squat", plannedSets = 3))
        e.advance(now = 1_000)
        e.advance(now = 1_060)
        assertEquals(listOf(LiftSlot(0, 2), LiftSlot(0, 3)), e.unperformedSlots)
    }

    // MARK: - Rebuild

    @Test
    fun `restoring rebuilds an interrupted session without its undo history`() {
        val plan = listOf(LiftPlanItem("Squat", plannedSets = 2))
        val sets = listOf(
            LiftRecordedSet(0, 1, 100.0, 5, 8.0, false, 1_000, 1_060, 120),
        )
        val e = LiftSessionEngine.restoring(
            plan = plan,
            startTs = 1_000,
            stage = LiftSessionEngine.Stage.Working(LiftSlot(0, 2)),
            sets = sets,
            stageStartedAt = 1_180,
        )
        assertEquals(LiftSlot(0, 2), e.currentSlot)
        assertEquals(1, e.sets.size)
        assertEquals(100.0, e.sets.single().weightKg!!, 0.0)
        assertFalse("a stack that survives a relaunch invites reaching back past a save boundary", e.canUndo)
    }
}
