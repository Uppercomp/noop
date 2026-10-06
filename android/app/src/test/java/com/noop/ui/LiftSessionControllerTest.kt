package com.noop.ui

import com.noop.R
import com.noop.data.LiftMuscle
import com.noop.data.LiftPlanItem
import com.noop.data.LiftProgramItemRow
import com.noop.data.LiftSessionEngine
import com.noop.data.LiftSetCarry
import com.noop.data.LiftSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [LiftSessionController], the Kotlin twin of `Strand/Data/LiftSessionController.swift`.
 *
 * The controller is constructed with its clock, buzz, strap-handler claim, log and snapshot writer
 * INJECTED — the same seams the Swift version has — so a whole session can be driven through a known
 * timeline here with no strap, no store and no Compose. The rest timers need a CoroutineScope, which is
 * deliberately left null: their scheduling is not what these pin, and a null scope proves the controller
 * is usable without one.
 */
class LiftSessionControllerTest {

    private var t = 1_000
    private val buzzes = mutableListOf<Int>()
    private val logs = mutableListOf<String>()
    private val snapshots = mutableListOf<LiftSessionPersistence.Snapshot>()
    private var strapHandler: (() -> Unit)? = null

    private fun controller(onChanged: () -> Unit = {}) = LiftSessionController(
        buzz = { buzzes += it },
        setStrapHandler = { strapHandler = it },
        log = { logs += it },
        persistSnapshot = { snapshots += it },
        clearSnapshot = { },
        now = { t },
        onChanged = onChanged,
    )

    private fun plan(vararg items: LiftPlanItem) = items.toList()

    private fun row(
        id: String,
        exercise: String = "Squat",
        ord: Int = 0,
        targetSets: Int? = null,
        targetWeightKg: Double? = 100.0,
        targetRepsLow: Int? = 5,
        targetRepsHigh: Int? = null,
        targetRpe: Double? = null,
    ) = LiftProgramItemRow(
        id = id, deviceId = "dev", programId = "p1", ord = ord, exercise = exercise,
        targetSets = targetSets, targetRepsLow = targetRepsLow, targetRepsHigh = targetRepsHigh,
        targetRpe = targetRpe, targetWeightKg = targetWeightKg, restSec = null, note = null,
    )

    // MARK: - The knock rule

    /**
     * Under the window counts as a knock: no set a lifter means to finish, and no rest a lifter means to
     * end, is that short. The strap's own log shows the arm going onto the bar registering at +3 s and
     * +4 s after a set started.
     */
    @Test
    fun `a double-tap inside the knock window is a knock`() {
        val working = LiftSessionEngine.Stage.Working(LiftSlot(0, 1))
        assertTrue(LiftSessionController.isKnock(0, working, now = 1_000))
        assertTrue(LiftSessionController.isKnock(3, working, now = 1_000))
        assertTrue(LiftSessionController.isKnock(4, working, now = 1_000))
    }

    @Test
    fun `a double-tap at or beyond the window is a deliberate tap`() {
        val working = LiftSessionEngine.Stage.Working(LiftSlot(0, 1))
        assertFalse(LiftSessionController.isKnock(5, working, now = 1_000))
        assertFalse(LiftSessionController.isKnock(30, working, now = 1_000))
    }

    /** "A knock does not press it": a nonsense negative gap is not a knock either. */
    @Test
    fun `a negative gap is not a knock`() {
        assertFalse(
            LiftSessionController.isKnock(-1, LiftSessionEngine.Stage.Working(LiftSlot(0, 1)), now = 1_000),
        )
    }

    /**
     * The exception: a rest that is ALREADY OVER — a line planned with no rest, or the one left once
     * every set is done — where going straight on is the plan.
     */
    @Test
    fun `a knock on an already-finished rest is not held back`() {
        val over = LiftSessionEngine.Stage.Resting(LiftSlot(0, 1), endsAt = 1_000)
        assertFalse(LiftSessionController.isKnock(2, over, now = 1_000))
        assertFalse(LiftSessionController.isKnock(2, over, now = 1_100))

        val running = LiftSessionEngine.Stage.Resting(LiftSlot(0, 1), endsAt = 1_060)
        assertTrue("a rest still running is protected like any other stage", LiftSessionController.isKnock(2, running, now = 1_000))
    }

    /**
     * The knock window is measured from the last tap the session ACTED ON — so the setup has to reach
     * that tap through the strap, not through the on-screen button. A button press leaves
     * `lastStrapStepAt` alone ("a knock does not press it"), and the tap after it is the first one.
     */
    @Test
    fun `a held-back knock buzzes nothing, logs why, and leaves the session alone`() {
        val c = controller()
        c.start(plan(LiftPlanItem("Squat", plannedSets = 2)), programId = null, programName = null)
        c.advance()                                     // the warm-up starts set 1
        t = 1_010
        c.advance(fromStrap = true)                     // the strap tap that ends it and starts the rest
        buzzes.clear()
        val recorded = c.engine!!.sets.size

        t = 1_012
        c.advance(fromStrap = true)                     // 2 s later: the arm going onto the bar
        assertEquals("a held-back tap sends no confirming buzz", 0, buzzes.size)
        assertEquals("and it must account for itself in the strap log", 1, logs.size)
        assertEquals("nothing about the session may move", recorded, c.engine!!.sets.size)

        t = 1_040
        c.advance(fromStrap = true)                     // a real tap, well clear of the window
        assertEquals(1, buzzes.size)
        assertEquals(LiftSessionController.advanceConfirmBuzzes, buzzes.single())
        assertTrue("the set is now recorded", c.engine!!.sets.isNotEmpty())
    }

    /** The buzz is a latency signal, so it is sent before any state work. */
    @Test
    fun `an accepted strap tap buzzes once and records the set`() {
        val c = controller()
        c.start(plan(LiftPlanItem("Squat", plannedSets = 1)), programId = null, programName = null)
        c.advance()                                     // the warm-up starts the only set
        buzzes.clear()
        t = 1_010
        c.advance(fromStrap = true)
        assertEquals(listOf(LiftSessionController.advanceConfirmBuzzes), buzzes)
        assertEquals(1, c.engine!!.sets.size)
    }

    // MARK: - The rest's two moments

    @Test
    fun `the warning fires five seconds before the rest ends, and the end only for a live rest`() {
        val times = LiftSessionController.restEventTimes(endsAt = 1_120, now = 1_000)
        assertEquals(1_115, times.warning)
        assertEquals(1_120, times.end)
    }

    /**
     * A rest already inside its final seconds (a short rest, none at all, or the rest left once every set
     * is done) warns one second from now — clear of the confirming buzz the same tap just sent.
     */
    @Test
    fun `a rest already inside the warning window warns a second from now`() {
        assertEquals(1_001, LiftSessionController.restEventTimes(endsAt = 1_002, now = 1_000).warning)
        assertEquals(1_001, LiftSessionController.restEventTimes(endsAt = 1_000, now = 1_000).warning)
    }

    @Test
    fun `a rest that is already over has no end event`() {
        assertNull(LiftSessionController.restEventTimes(endsAt = 1_000, now = 1_000).end)
        assertNull(LiftSessionController.restEventTimes(endsAt = 900, now = 1_000).end)
    }

    // MARK: - Pending input

    /**
     * Reported from a real session: "when I type something during an active set to other sets it
     * refreshes to the empty". The value is held and applied the instant the set is recorded — where it
     * BEATS the carried plan, because what the user typed for this set is better evidence than what the
     * sheet guessed.
     */
    @Test
    fun `numbers typed into a pending set are applied when it is recorded, and consumed`() {
        val c = controller()
        c.start(plan(LiftPlanItem("Squat", plannedSets = 2, targetWeightKg = 100.0, targetRepsLow = 5)),
            programId = null, programName = null)
        c.advance()                                     // start set 1
        val slot1 = LiftSlot(0, 1)

        c.updateSet(slot1, weightKg = 102.5, reps = 5, rpe = 9.0, isWarmup = false)
        assertTrue("nothing recorded yet — the value is pending", c.engine!!.sets.isEmpty())
        assertEquals(LiftSessionController.PendingSetValues(102.5, 5, 9.0), c.enteredValues(slot1))

        t = 1_060
        c.advance()                                     // complete it
        val set = c.engine!!.sets.single()
        assertEquals(102.5, set.weightKg!!, 0.0)
        assertEquals(5, set.reps)
        assertEquals(9.0, set.rpe!!, 0.0)
        assertNull("the entry is consumed, so a redo shows the ghosts again", c.pendingValues[slot1])
    }

    @Test
    fun `clearing the last field clears the entry rather than leaving a blank`() {
        val c = controller()
        c.start(plan(LiftPlanItem("Squat", plannedSets = 1)), programId = null, programName = null)
        val slot = LiftSlot(0, 1)
        c.updateSet(slot, 100.0, 5, null, false)
        assertTrue(c.pendingValues.containsKey(slot))
        c.updateSet(slot, null, null, null, false)
        assertFalse("the row goes back to showing the plan's grey ghost", c.pendingValues.containsKey(slot))
    }

    /** An unperformed set cannot be edited in the engine — that would invent it. */
    @Test
    fun `editing an unperformed set never appends one`() {
        val c = controller()
        c.start(plan(LiftPlanItem("Squat", plannedSets = 1)), programId = null, programName = null)
        c.updateSet(LiftSlot(0, 5), 100.0, 5, null, false)   // a slot that does not exist
        assertTrue(c.engine!!.sets.isEmpty())
    }

    // MARK: - Finishing

    @Test
    fun `nothing performed means there is nothing to file`() {
        val c = controller()
        c.start(plan(LiftPlanItem("Squat", plannedSets = 2)), programId = null, programName = null)
        assertFalse(LiftSessionController.anyPerformed(c.setsToSave(completingUnfinished = false)))
        c.advance()
        t = 1_060
        c.advance()
        assertTrue(LiftSessionController.anyPerformed(c.setsToSave(completingUnfinished = false)))
    }

    /**
     * A done set saves what was typed into it, and a blank takes the grey value the sheet showed —
     * including RPE, where a set left unrated saves the program line's max RPE.
     */
    @Test
    fun `a done set saves its grey numbers and the program line's max RPE for blanks`() {
        val c = controller()
        c.start(
            plan(LiftPlanItem("Squat", plannedSets = 1, targetWeightKg = 100.0, targetRepsLow = 5, targetRpe = 8.0)),
            programId = null, programName = null,
        )
        c.advance()
        t = 1_060
        c.advance()

        val finished = c.setsToSave(completingUnfinished = false).single()
        assertEquals(100.0, finished.weightKg!!, 0.0)
        assertEquals(5, finished.reps)
        assertEquals("a blank RPE takes the plan's ceiling", 8.0, finished.rpe!!, 0.0)
        assertEquals(1_000, finished.startTs)
        assertEquals(1_060, finished.endTs)
    }

    /**
     * Sets never started save as 0 kg x 0 reps when discarded, which every figure leaves out and Edit sets
     * still shows — so a discard made by mistake can be filled back in. Completing them instead saves
     * their grey numbers.
     */
    @Test
    fun `unfinished sets save as zeros when discarded, or their grey numbers when completed`() {
        val c = controller()
        c.start(
            plan(LiftPlanItem("Squat", plannedSets = 2, targetWeightKg = 100.0, targetRepsLow = 5)),
            programId = null, programName = null,
        )
        c.advance()
        t = 1_060
        c.advance()                                     // set 1 done; set 2 never started

        val discarded = c.setsToSave(completingUnfinished = false).last()
        assertEquals(0.0, discarded.weightKg!!, 0.0)
        assertEquals(0, discarded.reps)
        assertNull(discarded.rpe)
        assertNull("there is no moment to record, and inventing one would give it a heart-rate window", discarded.startTs)

        val completed = c.setsToSave(completingUnfinished = true).last()
        assertEquals(100.0, completed.weightKg!!, 0.0)
        assertEquals(5, completed.reps)
        assertNull(completed.startTs)
    }

    @Test
    fun `done sets keep their order and unfinished ones follow in plan order`() {
        val c = controller()
        c.start(
            plan(LiftPlanItem("A", plannedSets = 1), LiftPlanItem("B", plannedSets = 1)),
            programId = null, programName = null,
        )
        // Do B first — the machine A was on was busy.
        c.start(LiftSlot(1, 1))
        t = 1_060
        c.advance()
        val order = c.setsToSave(completingUnfinished = false).map { it.slot }
        assertEquals("B as performed, then A pending in plan order", listOf(LiftSlot(1, 1), LiftSlot(0, 1)), order)
    }

    // MARK: - Program write-back

    @Test
    fun `a changed set count is reported, an unchanged one is not`() {
        val items = listOf(row("i1", targetSets = 4), row("i2", ord = 1, targetSets = 3))
        val plan = plan(
            LiftPlanItem("Squat", plannedSets = 5, programItemId = "i1"),
            LiftPlanItem("Bench", plannedSets = 3, programItemId = "i2"),
        )
        val changes = LiftSessionController.setCountChanges(plan, items)
        assertEquals(1, changes.size)
        assertEquals(LiftSessionController.SetCountChange("i1", "Squat", from = 4, to = 5), changes.single())
    }

    @Test
    fun `a line the program no longer has is skipped rather than resurrected`() {
        val items = listOf(row("i1", targetSets = 4))
        val plan = plan(
            LiftPlanItem("Squat", plannedSets = 5, programItemId = "i1"),
            LiftPlanItem("Deleted", plannedSets = 9, programItemId = "gone"),
            LiftPlanItem("Added mid-session", plannedSets = 1, programItemId = "new", addedInSession = true),
        )
        assertEquals(listOf("i1"), LiftSessionController.setCountChanges(plan, items).map { it.itemId })
    }

    /** A line with no count counts as one set, as it does when a session starts. */
    @Test
    fun `a null target on the program line counts as one set`() {
        val plan = plan(LiftPlanItem("Squat", plannedSets = 2, programItemId = "i1"))
        val changes = LiftSessionController.setCountChanges(plan, listOf(row("i1", targetSets = null)))
        assertEquals(LiftSessionController.SetCountChange("i1", "Squat", from = 1, to = 2), changes.single())
    }

    /**
     * Numbers typed during a session update the program without asking: a line holds one weight for all
     * its sets, so it takes the HEAVIEST — the working weight, which a lighter back-off set must not pull
     * down. Between sets of equal weight, the one with more reps.
     */
    @Test
    fun `the heaviest done set becomes the line's new weight and reps`() {
        val plan = plan(LiftPlanItem("Squat", plannedSets = 3, programItemId = "i1"))
        val items = listOf(row("i1", targetSets = 3, targetWeightKg = 100.0, targetRepsLow = 5))

        val sets = listOf(
            finished(LiftSlot(0, 1), weightKg = 100.0, reps = 5, startTs = 1_000),
            finished(LiftSlot(0, 2), weightKg = 110.0, reps = 3, startTs = 1_100),
            finished(LiftSlot(0, 3), weightKg = 90.0, reps = 8, startTs = 1_200),   // a back-off set
        )
        val after = LiftSessionController.applyingHeaviestSets(sets, plan, items).single()
        assertEquals(110.0, after.targetWeightKg!!, 0.0)
        assertEquals(3, after.targetRepsLow)
    }

    @Test
    fun `at equal weight the set with more reps wins`() {
        val plan = plan(LiftPlanItem("Squat", plannedSets = 2, programItemId = "i1"))
        val items = listOf(row("i1", targetSets = 2))
        val sets = listOf(
            finished(LiftSlot(0, 1), weightKg = 100.0, reps = 5, startTs = 1_000),
            finished(LiftSlot(0, 2), weightKg = 100.0, reps = 7, startTs = 1_100),
        )
        assertEquals(7, LiftSessionController.applyingHeaviestSets(sets, plan, items).single().targetRepsLow)
    }

    /** Warm-ups, sets discarded to zeros and sets never started move nothing. */
    @Test
    fun `warm-ups and unperformed sets do not touch the program`() {
        val plan = plan(LiftPlanItem("Squat", plannedSets = 3, programItemId = "i1"))
        val items = listOf(row("i1", targetSets = 3, targetWeightKg = 100.0, targetRepsLow = 5))
        val sets = listOf(
            finished(LiftSlot(0, 1), weightKg = 200.0, reps = 5, startTs = 1_000, isWarmup = true),
            finished(LiftSlot(0, 2), weightKg = 0.0, reps = 0, startTs = 1_100),
            finished(LiftSlot(0, 3), weightKg = 999.0, reps = 5, startTs = null),
        )
        val after = LiftSessionController.applyingHeaviestSets(sets, plan, items).single()
        assertEquals("the line is left exactly as it was", 100.0, after.targetWeightKg!!, 0.0)
        assertEquals(5, after.targetRepsLow)
    }

    @Test
    fun `a number the heaviest set does not have is left as it was`() {
        val plan = plan(LiftPlanItem("Pull-up", plannedSets = 1, programItemId = "i1"))
        val items = listOf(row("i1", targetWeightKg = 0.0, targetRepsLow = 8))
        // A bodyweight line's set carries reps but no weight.
        val sets = listOf(finished(LiftSlot(0, 1), weightKg = null, reps = 12, startTs = 1_000))
        val after = LiftSessionController.applyingHeaviestSets(sets, plan, items).single()
        assertEquals(0.0, after.targetWeightKg!!, 0.0)
        assertEquals(12, after.targetRepsLow)
    }

    @Test
    fun `a leftover rep-range top below the new count is dropped`() {
        val plan = plan(LiftPlanItem("Squat", plannedSets = 1, programItemId = "i1"))
        val items = listOf(row("i1", targetRepsLow = 5, targetRepsHigh = 6))
        val sets = listOf(finished(LiftSlot(0, 1), weightKg = 100.0, reps = 9, startTs = 1_000))
        assertNull(LiftSessionController.applyingHeaviestSets(sets, plan, items).single().targetRepsHigh)
    }

    @Test
    fun `keeping changes also moves set counts and appends lines added mid-session`() {
        val plan = plan(
            LiftPlanItem("Squat", plannedSets = 5, programItemId = "i1"),
            LiftPlanItem("Curl", plannedSets = 2, programItemId = "i2", addedInSession = true),
        )
        val items = listOf(row("i1", targetSets = 4))
        // The in-session line i2 is not in the program yet, so only i1 is a count change.
        val sets = listOf(finished(LiftSlot(0, 1), weightKg = 120.0, reps = 3, startTs = 1_000))

        val withChanges = LiftSessionController.programAfterSession(
            sets, plan, items, keepingChanges = true, programId = "p1", deviceId = "dev",
        )
        assertEquals(5, withChanges.first { it.id == "i1" }.targetSets)
        assertEquals(120.0, withChanges.first { it.id == "i1" }.targetWeightKg!!, 0.0)
        val added = withChanges.first { it.id == "i2" }
        assertEquals("the appended line takes the session's set count, not a fresh one", 2, added.targetSets)

        val withoutChanges = LiftSessionController.programAfterSession(
            sets, plan, items, keepingChanges = false, programId = "p1", deviceId = "dev",
        )
        assertEquals("the count change is refused", 4, withoutChanges.first { it.id == "i1" }.targetSets)
        assertEquals("but the heaviest set still lands", 120.0, withoutChanges.first { it.id == "i1" }.targetWeightKg!!, 0.0)
        assertTrue("and no line is appended", withoutChanges.none { it.id == "i2" })
    }

    @Test
    fun `applying a count change moves only the set count`() {
        val items = listOf(row("i1", targetSets = 4, targetWeightKg = 100.0, targetRepsLow = 5))
        val after = LiftSessionController.applying(
            listOf(LiftSessionController.SetCountChange("i1", "Squat", from = 4, to = 6)),
            items,
        ).single()
        assertEquals(6, after.targetSets)
        assertEquals(100.0, after.targetWeightKg!!, 0.0)
        assertEquals(5, after.targetRepsLow)
    }

    // MARK: - Session lifecycle

    @Test
    fun `a session is active from the start and not after finishing`() {
        val c = controller()
        assertFalse(c.isActive)
        c.start(plan(LiftPlanItem("Squat", plannedSets = 1)), programId = "p1", programName = "Upper A")
        assertTrue(c.isActive)
        assertEquals("p1", c.programId)
        assertEquals("Upper A", c.programName)
        c.finish()
        assertFalse(c.isActive)
    }

    @Test
    fun `a snapshot is written on every change, none while finished, and undo puts it back in flight`() {
        val c = controller()
        c.start(plan(LiftPlanItem("Squat", plannedSets = 1)), programId = null, programName = null)
        val atStart = snapshots.size
        assertTrue(atStart >= 1)
        c.advance()
        assertTrue("an in-flight session is crash-safe", snapshots.size > atStart)

        val inFlight = snapshots.size
        c.finish()
        assertEquals("a finished session is not snapshotted — it is about to be saved", inFlight, snapshots.size)

        c.undo()
        assertEquals(
            "undoing the finish puts the session back in flight, so it is crash-safe again",
            inFlight + 1,
            snapshots.size,
        )
    }

    @Test
    fun `the strap handler is claimed for the session and released at teardown`() {
        val c = controller()
        c.start(plan(LiftPlanItem("Squat", plannedSets = 1)), programId = null, programName = null)
        assertTrue("claimed for the LIFETIME OF THE SESSION, not of a view", strapHandler != null)
        c.discard()
        assertNull(strapHandler)
        assertFalse(c.isActive)
    }

    /**
     * `presentation` takes the session label because Android resolves copy through a Context this class
     * does not own; the tests assert the RESOURCE the helper chose, which is the decision worth pinning —
     * the words themselves live in `strings.xml` and its eight translations.
     */
    @Test
    fun `presentation is null when there is no session and reports the warm-up before the first set`() {
        val c = controller()
        assertNull(c.presentation(UnitSystem.METRIC, "Session"))
        c.start(plan(LiftPlanItem("Squat", plannedSets = 1)), programId = null, programName = "Upper A")
        val warm = c.presentation(UnitSystem.METRIC, "Session")!!
        assertEquals("Upper A", warm.exercise)
        assertEquals(DisplayText.Resource(R.string.l10n_lift_warm_up_fb1869bf), warm.status)
        assertNull(warm.restEndsAt)
    }

    /**
     * The numbers a set shows, which the bar and the Lock Screen read from the SAME chain as the sheet
     * they are rendered beside.
     */
    @Test
    fun `presentation reports the working set, then the rest, then ready`() {
        val c = controller()
        c.start(
            plan(LiftPlanItem("Squat", plannedSets = 2, plannedRestSec = 120, targetWeightKg = 100.0, targetRepsLow = 5)),
            programId = null, programName = null,
        )
        c.advance()
        assertEquals(
            DisplayText.Resource(R.string.l10n_lift_set_lld_beffc9de, listOf(1)),
            c.presentation(UnitSystem.METRIC, "Session")!!.status,
        )
        assertEquals(DisplayText.Dynamic("5 x 100 kg"), c.presentation(UnitSystem.METRIC, "Session")!!.detail)
        assertEquals(
            DisplayText.Resource(R.string.l10n_lift_next_set_lld_7d9ca288, listOf(2, "Squat")),
            c.presentation(UnitSystem.METRIC, "Session")!!.next,
        )

        t = 1_060
        c.advance()
        val resting = c.presentation(UnitSystem.METRIC, "Session")!!
        assertTrue(resting.isResting)
        assertEquals(DisplayText.Resource(R.string.l10n_lift_resting_after_set_lld_7798216c, listOf(1)), resting.status)
        assertEquals(1_180, resting.restEndsAt)

        t = 1_180
        assertEquals(
            "the words change with time alone at the rest's end",
            DisplayText.Resource(R.string.l10n_lift_ready_for_the_next_set_c7acd2db),
            c.presentation(UnitSystem.METRIC, "Session")!!.status,
        )
    }

    @Test
    fun `the next line names the last set and then all sets done`() {
        val c = controller()
        c.start(plan(LiftPlanItem("Squat", plannedSets = 1)), programId = null, programName = null)
        c.advance()                                     // working the only set
        assertEquals(DisplayText.Resource(R.string.l10n_lift_last_set_f3eaef5d), LiftSessionController.nextLine(c.engine!!))
        t = 1_060
        c.advance()                                     // set done
        assertEquals(
            DisplayText.Resource(R.string.l10n_lift_all_sets_done_615a1502),
            LiftSessionController.nextLine(c.engine!!),
        )
    }

    /** The grey chain the sheet and the bar share, with last session's numbers in the middle. */
    @Test
    fun `carry falls through to the program target until the last session is handed over`() {
        val c = controller()
        c.start(
            plan(LiftPlanItem("Squat", plannedSets = 2, targetWeightKg = 100.0, targetRepsLow = 5)),
            programId = null, programName = null,
        )
        assertEquals(LiftSetCarry(100.0, 5), c.carry(LiftSlot(0, 1)))

        c.setLastSession(mapOf("Squat" to mapOf(1 to LiftSetCarry(90.0, 8))))
        assertEquals(LiftSetCarry(90.0, 8), c.carry(LiftSlot(0, 1)))
    }

    @Test
    fun `an exercise added mid-session is one set with zeros, and undoable`() {
        val c = controller()
        c.start(plan(LiftPlanItem("Squat", plannedSets = 1)), programId = null, programName = null)
        assertTrue(c.addExercise("Curl", primaryMuscle = LiftMuscle.biceps, secondaryMuscles = listOf(LiftMuscle.biceps, LiftMuscle.forearms)))
        val added = c.engine!!.plan.last()
        assertEquals("Curl", added.exercise)
        assertEquals(1, added.targetSets)
        assertEquals(0.0, added.targetWeightKg!!, 0.0)
        assertTrue(added.addedInSession)
        assertEquals("the primary is stripped from the secondaries", listOf(LiftMuscle.forearms), added.secondaryMuscles)
        c.undo()
        assertEquals(1, c.engine!!.plan.size)
    }

    @Test
    fun `removing a set also drops its pending warm-up mark and typed numbers`() {
        val c = controller()
        c.start(plan(LiftPlanItem("Squat", plannedSets = 2)), programId = null, programName = null)
        val last = LiftSlot(0, 2)
        c.setWarmup(last, isWarmup = true)
        c.updateSet(last, 100.0, 5, null, false)
        assertTrue(c.removeSet(fromExercise = 0))
        assertFalse("a slot that no longer exists must not keep them", c.pendingWarmups.containsKey(last))
        assertFalse(c.pendingValues.containsKey(last))
    }

    /**
     * The platform's readout of a running session — on Android the Lock Screen notification, on iOS the Live
     * Activity — hangs off [LiftSessionController.onChanged]. It has to fire in BOTH directions: on a change
     * that alters the words, and on the teardown that means there is nothing left to show. A callback that
     * only fired on mutations would leave a notification on the Lock Screen advertising a session the user
     * had already ended.
     */
    @Test
    fun `every change tells the platform, and so does ending the session`() {
        var changes = 0
        val c = controller(onChanged = { changes++ })

        c.start(plan(LiftPlanItem("Squat", plannedSets = 1)), programId = null, programName = null)
        val afterStart = changes
        assertTrue("starting a session is a change", afterStart >= 1)

        c.advance()
        assertTrue("and so is every tap", changes > afterStart)

        val beforeEnd = changes
        c.discard()
        assertTrue("ending it must tell the platform too", changes > beforeEnd)
    }

    private fun finished(
        slot: LiftSlot,
        weightKg: Double?,
        reps: Int?,
        startTs: Int? = 1_000,
        isWarmup: Boolean = false,
    ) = LiftSessionController.FinishedSet(
        slot = slot, weightKg = weightKg, reps = reps, rpe = null, isWarmup = isWarmup,
        startTs = startTs, endTs = startTs?.let { it + 45 }, restSec = null,
    )
}
