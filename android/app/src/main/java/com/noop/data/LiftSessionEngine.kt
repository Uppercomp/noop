package com.noop.data

// The session state machine: the heart of the Lift Log. Kotlin twin of
// `Strand/Data/LiftSessionEngine.swift`.
//
// A GYM IS NOT A QUEUE. The first version walked the plan strictly in order, one set at a time, and
// the first real session killed that: when a machine is occupied you move on and come back. So the
// plan is a SHEET of slots — every set of every exercise, all visible — and any pending slot can be
// started at any time. Order is a suggestion the session follows by default, not a rail.
//
// PURE (no timers, no store, no Compose): it is the piece most likely to be wrong in a way that costs
// someone a logged set, and the only part testable with no strap, no database, no emulator.
//
// TIME ENTERS ONLY AS A PARAMETER, so a test can drive a whole session through a known timeline. Rest
// is an ABSOLUTE end instant, never a decrementing counter — a phone that sleeps through a rest must
// wake up telling the truth.
//
// THERE IS NO COOL-DOWN STAGE. Warm-up is the time before the first set and cool-down the time after
// the last; both fall out of the timestamps, so neither needs a stage of its own. The session ends
// when the user says it does.
//
// NOTE ON VERIFICATION. `docs/CONTRIBUTING.md` asks for a port of this kind to be pinned by an oracle:
// compile the Swift twin standalone and paste its stdout as the expected literal here. No Swift
// toolchain exists on the machine this was ported on, so that was not possible. What stands in its
// place is `LiftSessionEngineTest`, which asserts the INVARIANTS the Swift doc comments state
// explicitly (stay-on-the-current-exercise, absolute rest, the carry preference order, RPE never
// carried, a completed set surviving a set-count change). That is weaker than an oracle and should be
// strengthened to one on a machine with Xcode.

/** One set of one exercise — a position on the sheet. [setIndex] is 1-based within its exercise. */
data class LiftSlot(val exerciseIndex: Int, val setIndex: Int)

/** One planned exercise line, flattened from a program for the session to run. */
data class LiftPlanItem(
    val exercise: String,
    val primaryMuscle: LiftMuscle? = null,
    val secondaryMuscles: List<LiftMuscle> = emptyList(),
    // The two clamped figures are stored RAW and exposed clamped. Kotlin's data class cannot transform
    // a constructor parameter into a differently-valued property (non-`val` params are rejected
    // outright), so `plannedSets`/`plannedRestSec` are the inputs and `targetSets`/`restSec` the
    // clamped reads. This keeps the Swift `init`'s guarantee — a line scheduling zero sets could not be
    // tapped through, and a negative rest is not a rest — at EVERY construction and every `copy`,
    // which a clamp applied only at the call site would not.
    internal val plannedSets: Int = 1,
    internal val plannedRestSec: Int = defaultRestSec,
    val targetRepsLow: Int? = null,
    val targetRepsHigh: Int? = null,
    val targetRpe: Double? = null,
    /** The weight the program plans, in kilograms. */
    val targetWeightKg: Double? = null,
    val note: String? = null,
    /**
     * The `liftProgramItem.id` this line was flattened from, so a set count changed during the
     * session can be offered back to the program when it is finished. Null for a line with no program
     * behind it, which is then never offered. A line added during the session carries the id its
     * program line will have if finishing adds it ([addedInSession]).
     */
    val programItemId: String? = null,
    /** Added while the session ran ([LiftSessionEngine.addExercise]); finishing offers to add a line. */
    val addedInSession: Boolean = false,
) {
    /** Planned working sets. Always >= 1 — a line scheduling zero sets could not be tapped through. */
    val targetSets: Int get() = maxOf(1, plannedSets)

    /** Intended rest after each set, in seconds. Always >= 0. */
    val restSec: Int get() = maxOf(0, plannedRestSec)

    companion object {
        /**
         * Rest used when a program line does not specify one. Two minutes sits in the middle of the
         * range the hypertrophy literature uses for compound work, and is only a starting value: what
         * is actually rested is measured from the taps.
         */
        const val defaultRestSec: Int = 120
    }
}

/** One set as actually performed. */
data class LiftRecordedSet(
    val exerciseIndex: Int,
    val setIndex: Int,
    val weightKg: Double?,
    val reps: Int?,
    val rpe: Double?,
    val isWarmup: Boolean,
    val startTs: Int,
    val endTs: Int,
    /**
     * Rest actually taken after this set. Null while the rest is still running, or when the user
     * moved on without resting.
     */
    val restSec: Int?,
) {
    val slot: LiftSlot get() = LiftSlot(exerciseIndex, setIndex)
}

/**
 * The numbers a set shows in grey: what it would save if completed without anything typed.
 *
 * Only weight and reps. RPE is deliberately absent — see [LiftSessionEngine.carry].
 */
data class LiftSetCarry(val weightKg: Double?, val reps: Int?) {
    companion object {
        val none = LiftSetCarry(weightKg = null, reps = null)
    }
}

class LiftSessionEngine private constructor(
    plan: List<LiftPlanItem>,
    val startTs: Int,
    stage: Stage,
    sets: List<LiftRecordedSet>,
    stageStartedAt: Int,
) {

    sealed interface Stage {
        /** Before the first set — the warm-up. */
        object Warmup : Stage

        /** Performing a set. */
        data class Working(val slot: LiftSlot) : Stage

        /** Resting after a set. [endsAt] is an absolute unix second. */
        data class Resting(val slot: LiftSlot, val endsAt: Int) : Stage

        /** Ended; ready to save. */
        object Finished : Stage
    }

    /**
     * The lines being worked. MUTABLE in two ways only, both because a gym decides them as it goes:
     * how many sets a line holds — a fifth set on a line that planned four, or dropping the last one
     * when the tank is empty — and a line added at the end for an exercise the program does not have.
     * Nothing about a line already there changes mid-session, so the plan stays the snapshot it was at
     * start, plus whatever was added.
     */
    var plan: List<LiftPlanItem> = plan
        private set

    var stage: Stage = stage
        private set

    /**
     * Completed sets in COMPLETION order — which is the order they happened, not the plan's order,
     * and is what `ord` is written from.
     */
    var sets: List<LiftRecordedSet> = sets
        private set

    /** When the current stage began, so a set's duration is measurable. */
    var stageStartedAt: Int = stageStartedAt
        private set

    /**
     * Undo stack: whole-state snapshots rather than inverse operations. A gym is a bad place to be
     * one tap ahead of yourself, and restoring a snapshot cannot get the arithmetic wrong the way a
     * hand-written inverse can.
     */
    private val history = ArrayDeque<Snapshot>()

    /**
     * The plan travels in the snapshot with the stage and the sets, and is restored with them.
     *
     * It has to. Once the set count can move, a stage saved under one count is only meaningful under
     * that count: undoing back past a removed set would otherwise leave the session working a slot
     * the sheet no longer draws — and completing it would write a set nobody could see.
     */
    private data class Snapshot(
        val plan: List<LiftPlanItem>,
        val stage: Stage,
        val sets: List<LiftRecordedSet>,
        val stageStartedAt: Int,
    )

    constructor(plan: List<LiftPlanItem>, startTs: Int) :
        this(plan = plan, startTs = startTs, stage = Stage.Warmup, sets = emptyList(), stageStartedAt = startTs)

    /**
     * Rebuild an interrupted session — see `LiftSessionPersistence`.
     *
     * The undo history is deliberately NOT restored: it is a within-sitting convenience, and a stack
     * that survives a relaunch invites reaching back past a save boundary.
     */
    companion object {
        fun restoring(
            plan: List<LiftPlanItem>,
            startTs: Int,
            stage: Stage,
            sets: List<LiftRecordedSet>,
            stageStartedAt: Int,
        ): LiftSessionEngine = LiftSessionEngine(plan, startTs, stage, sets, stageStartedAt)

        /**
         * The most sets one line may hold. A bound against a stuck finger, not a recommendation:
         * every added set is a row on the sheet and a slot in the crash snapshot.
         */
        const val maxSetsPerExercise: Int = 20

        /**
         * The most lines one session may hold: the importer's cap on one program, so a session grown
         * during the workout stays inside the bound its crash snapshot (written on every change) was
         * sized for.
         */
        const val maxExercises: Int = 200
    }

    // MARK: - The sheet

    /** Every slot of one exercise, in order — the rows the sheet draws. */
    fun slotsForExercise(index: Int): List<LiftSlot> {
        val item = plan.getOrNull(index) ?: return emptyList()
        return (1..item.targetSets).map { LiftSlot(index, it) }
    }

    /** Every slot in the whole session, in plan order. */
    val allSlots: List<LiftSlot>
        get() = plan.indices.flatMap { slotsForExercise(it) }

    fun recordedSet(slot: LiftSlot): LiftRecordedSet? = sets.firstOrNull { it.slot == slot }

    fun isCompleted(slot: LiftSlot): Boolean = recordedSet(slot) != null

    /** The slot being worked or rested from. */
    val currentSlot: LiftSlot?
        get() = when (val s = stage) {
            is Stage.Working -> s.slot
            is Stage.Resting -> s.slot
            Stage.Warmup, Stage.Finished -> null
        }

    /**
     * The next slot the plan would suggest — the first uncompleted one in plan order. Null when the
     * whole sheet is done.
     *
     * This is the SHEET's answer, used to open a session and to know when everything is done. It is
     * deliberately NOT what [advance] follows after a set — see [slotAfter].
     */
    val nextPendingSlot: LiftSlot?
        get() = allSlots.firstOrNull { !isCompleted(it) }

    /**
     * Where the session goes after finishing [slot]: the next uncompleted set of the SAME exercise,
     * and only once that exercise is finished, the first uncompleted slot in plan order.
     *
     * Plan order alone is wrong, and wrong in a way that costs sets. A gym is not a queue — the whole
     * point of being able to start any pending set is that machines get occupied — so a user who
     * skips exercise 1 and starts exercise 3 has an EARLIER slot still uncompleted. Following plan
     * order then throws them back to the machine they just walked away from, mid-exercise, after
     * every single set. Reported from a real session: "when I double-tap for the next set, it reverts
     * to the first set of the exercise I couldn't do earlier."
     *
     * Staying on the current exercise until it is finished is also simply what lifting is: you do
     * your sets on the machine you are standing at. The skipped exercise is not forgotten — it is
     * still pending, and it is what you get once the current one is done.
     */
    fun slotAfter(slot: LiftSlot): LiftSlot? =
        slotsForExercise(slot.exerciseIndex).firstOrNull { !isCompleted(it) } ?: nextPendingSlot

    /**
     * The set the session is heading to after the current one — what the minimised bar names as
     * "next". During the warm-up it is the set [advance] starts; while a set is worked or rested
     * from, it is where [advance] goes once that set is done: [slotAfter], with the current set
     * counted as done. Never a rest, which sits between sets rather than being one of them. Null when
     * nothing is left to do after the current set.
     */
    val upcomingSlot: LiftSlot?
        get() = when (val s = stage) {
            Stage.Warmup -> nextPendingSlot
            is Stage.Working -> slotAfterExcluding(s.slot)
            is Stage.Resting -> slotAfterExcluding(s.slot)
            Stage.Finished -> null
        }

    private fun slotAfterExcluding(slot: LiftSlot): LiftSlot? =
        slotsForExercise(slot.exerciseIndex).firstOrNull { !isCompleted(it) && it != slot }
            ?: allSlots.firstOrNull { !isCompleted(it) && it != slot }

    val allCompleted: Boolean get() = nextPendingSlot == null

    val isFinished: Boolean get() = stage == Stage.Finished

    val canUndo: Boolean get() = history.isNotEmpty()

    val plannedWorkingSets: Int get() = plan.sumOf { it.targetSets }

    val completedWorkingSets: Int get() = sets.count { !it.isWarmup }

    fun planItem(slot: LiftSlot): LiftPlanItem? = plan.getOrNull(slot.exerciseIndex)

    /**
     * Seconds left in the current rest, floored at zero. Null when not resting.
     *
     * Floored rather than negative so an overrun rest reads "0:00" and waits, which is what it
     * actually means: the user has not moved on yet.
     */
    fun restRemaining(now: Int): Int? {
        val s = stage as? Stage.Resting ?: return null
        return maxOf(0, s.endsAt - now)
    }

    /**
     * The nearest earlier set of this exercise already performed in THIS session. Null for the first
     * set, or when nothing before it has been done yet.
     */
    fun previousSetInSession(slot: LiftSlot): LiftRecordedSet? {
        if (slot.setIndex <= 1) return null
        return (slot.setIndex - 1 downTo 1).firstNotNullOfOrNull {
            recordedSet(LiftSlot(slot.exerciseIndex, it))
        }
    }

    /**
     * The numbers a slot shows in grey, in order of preference: this exercise earlier in THIS
     * session, then the same set number LAST session, then the program's target.
     *
     * "Earlier in this session" means what that set COUNTS AS ([values]), so set 2 follows set 1
     * whether or not anything was typed there, and moves again if set 1 is corrected.
     *
     * GREY STAYS GREY. Finishing a set records its timing and nothing else; a number becomes the
     * set's own only when it is typed. What a set without typed numbers is worth is decided once,
     * when the session is finished: the user completes all of them with these numbers or discards
     * them (`LiftSessionController.setsToSave`).
     *
     * RPE is NOT carried. Weight and reps are a plan, knowable in advance; RPE is how hard a set
     * FELT, knowable only afterwards, and carrying one forward would invent the one figure nobody
     * can guess for you. It would also silently corrupt the coverage the RPE card reports — every
     * set would read as rated, and "14 of 19 sets unrated" could never be shown again.
     *
     * [lastSession] is this exercise's previous session by set number — the one layer the engine
     * cannot know. The caller supplies it; a set number it lacks falls through to the target.
     */
    fun carry(slot: LiftSlot, lastSession: Map<Int, LiftSetCarry>): LiftSetCarry {
        val previous = previousSetInSession(slot)?.let { values(it.slot, lastSession) }
        val last = lastSession[slot.setIndex]
        val item = planItem(slot)
        return LiftSetCarry(
            weightKg = previous?.weightKg ?: last?.weightKg ?: item?.targetWeightKg,
            reps = previous?.reps ?: last?.reps ?: item?.targetRepsLow,
        )
    }

    /** What a slot counts as: each number typed into it, else its grey number. */
    fun values(slot: LiftSlot, lastSession: Map<Int, LiftSetCarry>): LiftSetCarry {
        val grey = carry(slot, lastSession)
        val set = recordedSet(slot) ?: return grey
        return LiftSetCarry(weightKg = set.weightKg ?: grey.weightKg, reps = set.reps ?: grey.reps)
    }

    /**
     * Slots never performed. Finishing the session asks once what happens to all of them; a set that
     * was performed is complete whether or not anything was typed into it.
     */
    val unperformedSlots: List<LiftSlot> get() = allSlots.filter { !isCompleted(it) }

    // MARK: - Actions

    /**
     * Begin a specific set. Works from any stage, which is the whole point: a machine being busy must
     * not block the session.
     *
     * Starting a set while ANOTHER set is in progress abandons that one — nothing is recorded,
     * because nothing was finished. Starting an already-completed slot re-opens it for a redo,
     * dropping its previous record so the set is not counted twice.
     */
    fun start(slot: LiftSlot, now: Int) {
        if (planItem(slot) == null) return
        pushHistory()
        sets = sets.filterNot { it.slot == slot }
        stage = Stage.Working(slot)
        stageStartedAt = now
    }

    /** The big button. Context decides what it means. */
    fun advance(now: Int) {
        when (val s = stage) {
            Stage.Warmup -> {
                val next = nextPendingSlot ?: return
                start(next, now)
            }

            is Stage.Working -> {
                pushHistory()
                // Timing only: the numbers stay grey until typed (see [carry]).
                sets = sets + LiftRecordedSet(
                    exerciseIndex = s.slot.exerciseIndex,
                    setIndex = s.slot.setIndex,
                    weightKg = null, reps = null, rpe = null, isWarmup = false,
                    startTs = stageStartedAt, endTs = now, restSec = null,
                )
                val rest = planItem(s.slot)?.restSec ?: LiftPlanItem.defaultRestSec
                stage = Stage.Resting(s.slot, endsAt = now + rest)
                stageStartedAt = now
            }

            is Stage.Resting -> {
                pushHistory()
                // Record what was ACTUALLY rested — the figure each set row shows, and the one thing
                // only the taps can know.
                val i = sets.indexOfFirst { it.slot == s.slot }
                if (i >= 0) sets = sets.toMutableList().also { it[i] = it[i].copy(restSec = maxOf(0, now - stageStartedAt)) }
                val next = slotAfter(s.slot)
                if (next != null) {
                    stage = Stage.Working(next)
                    stageStartedAt = now
                } else {
                    // Sheet complete: stay put rather than inventing a stage. The user finishes when
                    // they are ready, and the time until then is the cool-down.
                    stage = Stage.Resting(s.slot, endsAt = now)
                    stageStartedAt = now
                }
            }

            Stage.Finished -> Unit
        }
    }

    /**
     * Fill in (or correct) what a set actually was. Editing, never appending — the set already exists,
     * so typing can never create a phantom. No-op for a slot that has not been completed.
     */
    fun updateSet(slot: LiftSlot, weightKg: Double?, reps: Int?, rpe: Double?, isWarmup: Boolean) {
        val i = sets.indexOfFirst { it.slot == slot }
        if (i < 0) return
        sets = sets.toMutableList().also {
            it[i] = it[i].copy(weightKg = weightKg, reps = reps, rpe = rpe, isWarmup = isWarmup)
        }
    }

    // MARK: - Changing how many sets a line holds
    //
    // A program is what you INTENDED, and a gym argues with it. Five sets when the program says four
    // is ordinary; so is stopping at three because the tank is empty. Until this existed the fifth
    // set simply could not be recorded — the sheet drew exactly 1...targetSets and there was no way
    // past it — so the set was done and then lost, which is the failure this feature exists to
    // prevent.

    /**
     * Append one set to an exercise's line. Returns false when the line is already at the bound, so a
     * caller can tell "did nothing" from "done" without re-deriving the rule.
     */
    fun addSet(toExercise: Int): Boolean {
        val item = plan.getOrNull(toExercise) ?: return false
        if (item.targetSets >= maxSetsPerExercise) return false
        pushHistory()
        plan = plan.toMutableList().also {
            it[toExercise] = it[toExercise].copy(plannedSets = item.targetSets + 1)
        }
        return true
    }

    /**
     * Whether the last set of a line can be dropped.
     *
     * Only the LAST one, and only while it is still pending. Set numbers are positions — the sheet
     * draws 1...targetSets — so removing from the middle would renumber every set after it and
     * silently re-label what was already recorded. And a completed set is DATA: dropping it here
     * would delete a set the user actually performed, from a button whose job is to edit a plan. The
     * set being worked or rested from is excluded for the same reason it cannot be renumbered: the
     * session is standing on it.
     */
    fun canRemoveSet(fromExercise: Int): Boolean {
        val item = plan.getOrNull(fromExercise) ?: return false
        if (item.targetSets <= 1) return false
        val last = LiftSlot(fromExercise, item.targetSets)
        return !isCompleted(last) && currentSlot != last
    }

    /** Drop the last (pending) set of an exercise's line. Returns false when the rule above says no. */
    fun removeSet(fromExercise: Int): Boolean {
        if (!canRemoveSet(fromExercise)) return false
        val item = plan[fromExercise]
        pushHistory()
        plan = plan.toMutableList().also {
            it[fromExercise] = item.copy(plannedSets = item.targetSets - 1)
        }
        return true
    }

    // MARK: - Adding an exercise

    /**
     * Append an exercise the program does not have, as ONE set at the end of the sheet. Any set count
     * the caller gives is ignored: a line starts with one set, and add/remove change it like any
     * other. Undoable, like adding a set. Returns false when nothing was added — the session is
     * finished, or already at [maxExercises].
     */
    fun addExercise(item: LiftPlanItem): Boolean {
        if (stage == Stage.Finished) return false
        if (plan.size >= maxExercises) return false
        pushHistory()
        plan = plan + item.copy(plannedSets = 1)
        return true
    }

    /**
     * End the session. The rest that was running is closed out first, so its measured duration is not
     * silently lost.
     */
    fun finish(now: Int) {
        if (stage == Stage.Finished) return
        pushHistory()
        val s = stage
        if (s is Stage.Resting) {
            val i = sets.indexOfFirst { it.slot == s.slot }
            if (i >= 0 && sets[i].restSec == null) {
                sets = sets.toMutableList().also { it[i] = it[i].copy(restSec = maxOf(0, now - stageStartedAt)) }
            }
        }
        stage = Stage.Finished
        stageStartedAt = now
    }

    fun undo() {
        val previous = history.removeLastOrNull() ?: return
        plan = previous.plan
        stage = previous.stage
        sets = previous.sets
        stageStartedAt = previous.stageStartedAt
    }

    private fun pushHistory() {
        history.addLast(Snapshot(plan, stage, sets, stageStartedAt))
    }
}
