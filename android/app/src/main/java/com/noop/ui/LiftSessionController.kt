package com.noop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.noop.R
import com.noop.analytics.LiftMetrics
import com.noop.data.LiftMuscle
import com.noop.data.LiftPlanItem
import com.noop.data.LiftProgramItemRow
import com.noop.data.LiftSessionEngine
import com.noop.data.LiftSetCarry
import com.noop.data.LiftSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// The live session, owned ABOVE any screen. Kotlin twin of `Strand/Data/LiftSessionController.swift`.
//
// WHY THIS EXISTS. The session used to live inside the sheet that displayed it. Swiping that sheet down
// dismissed it, which tore down the view — and with it the strap's double-tap handler and the rest-timer
// tick. The session looked alive (it was still on disk, and "Resume" brought it back) but was deaf: taps
// did nothing, no buzz arrived, in the app or out of it.
//
// A workout outlives the screen you happen to be looking at, so the session has to as well. This
// controller owns the engine, the rest's timers, the buzz gating and the persistence. The sheet is a
// rendering of it; the bottom bar is another. Dismissing either changes nothing about the session.
//
// LIVES IN `ui` ON ANDROID. `Presentation` and `setNumbers` need [UnitSystem] and [LiftFormat], which
// live here; the engine and the DAO stay in `data` and this reads them `ui`→`data`, the correct
// direction. iOS keeps it in `Strand/Data/`, which its own unit layer does not contradict.
//
// OBSERVABILITY is a revision counter, the house idiom for a class whose internals mutate in place
// (`LiftSessionEngine` is a mutable class, so its reference never changes and `mutableStateOf` would
// never fire). Every action bumps it through [mutate].
class LiftSessionController(
    /** Fires the strap buzz. Injected so the controller has no opinion about BLE and stays testable. */
    private val buzz: (Int) -> Unit,
    /** Claims/releases the strap's double-tap for the session's lifetime. */
    private val setStrapHandler: ((() -> Unit)?) -> Unit,
    /** Writes a line to the strap log — how a double-tap the session holds back is accounted for. */
    private val log: (String) -> Unit = {},
    /** Writes the crash snapshot. Injected so the controller is testable without a store. */
    private val persistSnapshot: (LiftSessionPersistence.Snapshot) -> Unit = {},
    /** Clears the crash snapshot once the session is saved or discarded. */
    private val clearSnapshot: () -> Unit = {},
    /** Current unix seconds. Injected so a test can drive a whole session through a known timeline. */
    private val now: () -> Int = { (System.currentTimeMillis() / 1000L).toInt() },
    private val scope: CoroutineScope? = null,
    /**
     * Fired after every change to the session, including its teardown.
     *
     * The platform's rendering of a running session hangs off this — on Android the Lock Screen
     * notification, on iOS the Live Activity — so it must see BOTH directions: a mutation that changes the
     * words, and the teardown that means there is nothing left to show. One callback rather than a show and
     * a hide that can drift apart.
     */
    private val onChanged: () -> Unit = {},
) {

    var engine: LiftSessionEngine? = null
        private set

    var programId: String? = null
        private set

    var programName: String? = null
        private set

    /** True while the full sheet is presented; false when minimised to the bottom bar. */
    var isPresented by mutableStateOf(false)

    /** Bumped each time a finished session is written, so the listing screen reloads. */
    var savedSessions by mutableIntStateOf(0)
        private set

    /** Bumped on every change to the session, so a Compose surface reading it recomposes. */
    var revision by mutableIntStateOf(0)
        private set

    val isActive: Boolean get() = engine != null && engine?.isFinished == false

    /**
     * Slots the user marked as a warm-up BEFORE performing them. You know a set is a warm-up on the way
     * in, not afterwards, but an unperformed set has no record to carry the flag — and inventing one
     * would create a set nobody did. So the mark is held here and applied the instant the set is
     * recorded.
     */
    val pendingWarmups = mutableStateMapOf<LiftSlot, Boolean>()

    /**
     * Numbers typed into a set BEFORE it was performed, held exactly the way a warm-up mark is.
     *
     * Reported from a real session: "when I type something during an active set to other sets it
     * refreshes to the empty". The engine invariant it ran into is real and stays — typing must never
     * append a set, or a set nobody performed becomes data — so the value is held here, shown back on
     * the row, and applied the instant the set is recorded, where it BEATS the carried plan because what
     * the user typed for this set is better evidence than what the sheet guessed.
     */
    val pendingValues = mutableStateMapOf<LiftSlot, PendingSetValues>()

    /** What a user typed into a set that has not happened yet. All optional. */
    data class PendingSetValues(val weightKg: Double?, val reps: Int?, val rpe: Double?) {
        val isEmpty: Boolean get() = weightKg == null && reps == null && rpe == null
    }

    /**
     * What the store holds for each exercise LAST session, keyed by exercise name then set number — the
     * middle layer of [LiftSessionEngine.carry]. It lives here rather than in the sheet because the
     * minimised bar shows a set's numbers too, and they must be the same grey numbers.
     */
    private var lastSession: Map<String, Map<Int, LiftSetCarry>> = emptyMap()

    /** Rest period the five-second warning has already fired for. Lives HERE, not in a view. */
    private var warnedFor: Int? = null

    /** When the session last acted on a strap double-tap. Null until it has, and after a relaunch. */
    private var lastStrapStepAt: Int? = null

    private var restJobs: List<Job> = emptyList()
    private var scheduledRestEnd: Int? = null

    private fun mutate(block: () -> Unit) {
        block()
        revision++
        // Re-arm the rest's timers HERE rather than from a property observer on `engine`. The Swift
        // controller gets this for free because `LiftSessionEngine` is a STRUCT there, so every mutation
        // reassigns the property and fires its `didSet`. This engine is a mutable class whose reference
        // never changes — an in-place `advance` moving working → resting would never trip an observer, and
        // the rest would silently have no timers. Every action that can move the stage goes through here.
        scheduleRestTimers()
        // ...and so does the platform's readout of the session, for the same reason: the reference never
        // changes, so nothing outside can observe a mutation without being told. Fires on teardown too, so
        // the Lock Screen notification follows the session's existence rather than a show/hide pair that
        // could drift apart.
        onChanged()
    }

    // MARK: - Lifecycle

    fun start(plan: List<LiftPlanItem>, programId: String?, programName: String?) {
        val stamp = now()
        mutate {
            engine = LiftSessionEngine(plan, stamp)
            this.programId = programId
            this.programName = programName
            warnedFor = null
            isPresented = true
        }
        claimStrap()
        persist()
    }

    /**
     * Pick up the session a previous run of NOOP left going, as the app process starts.
     *
     * Android kills the app in the background and relaunches it when the user next opens it. The session
     * used to come back only when the session screen appeared, and until then the running-session bar
     * showed nothing and a strap tap did nothing. Resumed here, before any screen exists, the session is
     * back before anything asks about it. The line it logs is how a later strap log shows a restart in
     * the middle of a session.
     */
    fun resumeSaved(store: LiftSnapshotStore) {
        if (isActive) return
        val snapshot = LiftSessionPersistence.load(store) ?: return
        resume(snapshot)
        log("Lift Log: session picked up again after NOOP restarted")
    }

    /**
     * Rehydrate an interrupted session found on disk. Does NOT present the sheet: the session comes back
     * as the bottom bar, and the user opens it if they want to.
     */
    fun resume(snapshot: LiftSessionPersistence.Snapshot, present: Boolean = false) {
        mutate {
            engine = LiftSessionPersistence.engine(snapshot)
            programId = snapshot.programId
            programName = snapshot.programName
            // Numbers typed for sets not yet performed, and warm-ups marked in advance, come back too:
            // they are intent the user already expressed, and losing them is the bug this pair exists to
            // prevent, whether it is lost to a blur or to a relaunch.
            pendingValues.clear()
            pendingValues.putAll(LiftSessionPersistence.pendingValues(snapshot))
            pendingWarmups.clear()
            LiftSessionPersistence.pendingWarmups(snapshot).forEach { pendingWarmups[it] = true }
            // Suppress the warning for a rest that is ALREADY inside its final seconds. Without this,
            // reopening a session mid-rest greets the user with three buzzes for a rest they have been
            // watching count down all along.
            val resting = engine?.stage as? LiftSessionEngine.Stage.Resting
            warnedFor = if (resting != null && resting.endsAt - now() <= restWarningLeadSec) resting.endsAt else null
            isPresented = present
        }
        claimStrap()
    }

    /** Give up the session without saving. */
    fun discard() {
        teardown()
        clearSnapshot()
    }

    /** Called once the session has been written to the store. */
    fun finishedSaving() {
        teardown()
        clearSnapshot()
        savedSessions++
    }

    private fun teardown() {
        mutate {
            engine = null
            programId = null
            programName = null
            warnedFor = null
            lastStrapStepAt = null
            pendingWarmups.clear()
            pendingValues.clear()
            isPresented = false
        }
        restJobs.forEach { it.cancel() }
        restJobs = emptyList()
        scheduledRestEnd = null
        setStrapHandler(null)
    }

    /**
     * The handler runs SYNCHRONOUSLY, inside the frame handling that delivered the tap. It used to hop
     * through a task, which let the sync request the same strap event triggers reach the strap first; the
     * strap then started a history transfer before playing the confirming buzz, and those buzzes came
     * 1–2.8 s after the tap where most others came in under one.
     */
    private fun claimStrap() {
        setStrapHandler { advance(fromStrap = true) }
    }

    // MARK: - Actions

    /** The one action. [fromStrap] earns a single confirming buzz, unless the tap reads as a knock. */
    fun advance(fromStrap: Boolean = false) {
        val current = engine ?: return
        val stamp = now()
        if (fromStrap) {
            val last = lastStrapStepAt
            if (last != null && isKnock(stamp - last, current.stage, stamp)) {
                // No buzz: the missing confirmation is the lifter's cue to tap again.
                log(
                    "Lift Log: that double-tap was not acted on — ${stamp - last} s after the last one it " +
                        "acted on (under $strapKnockWindowSec s is taken as a knock)",
                )
                return
            }
            lastStrapStepAt = stamp
            // BUZZ FIRST, before any state work. The confirmation is a latency signal — its whole job is
            // to say "that registered" — so it must not queue behind a JSON encode and a defaults write.
            buzz(advanceConfirmBuzzes)
        }

        mutate {
            engine?.advance(stamp)
            applyPendingInput()
            warnedFor = null
        }
        persist()
    }

    /** Begin a specific set — the out-of-order path, for when a machine is occupied. */
    fun start(slot: LiftSlot, fromStrap: Boolean = false) {
        if (engine == null) return
        if (fromStrap) buzz(advanceConfirmBuzzes)
        mutate {
            engine?.start(slot, now())
            // Starting a slot drops any record it had, so its warm-up mark reverts to pending — which is
            // where it already lives.
            warnedFor = null
        }
        persist()
    }

    /**
     * Add one set to an exercise — the unplanned fifth set. Returns whether anything changed, which is
     * what tells the caller whether the program behind the session needs rewriting.
     */
    fun addSet(toExercise: Int): Boolean {
        val changed = engine?.addSet(toExercise) == true
        if (changed) {
            revision++
            persist()
        }
        return changed
    }

    /**
     * Add an exercise the program does not have, at the end of the sheet: one set, planned as 0 kg x 0
     * reps with no max RPE and the default rest, so its row shows zeros until numbers are typed — or last
     * session's numbers, when the exercise has been done before.
     */
    fun addExercise(name: String, primaryMuscle: LiftMuscle?, secondaryMuscles: List<LiftMuscle>): Boolean {
        val line = LiftPlanItem(
            exercise = name,
            primaryMuscle = primaryMuscle,
            secondaryMuscles = secondaryMuscles.filter { it != primaryMuscle },
            plannedSets = 1,
            targetRepsLow = 0,
            targetWeightKg = 0.0,
            programItemId = newId(),
            addedInSession = true,
        )
        val added = engine?.addExercise(line) == true
        if (added) {
            revision++
            persist()
        }
        return added
    }

    /**
     * Drop the last pending set of an exercise. See [LiftSessionEngine.canRemoveSet] for what "can" means
     * — a completed set is never removed this way.
     */
    fun removeSet(fromExercise: Int): Boolean {
        val e = engine ?: return false
        if (!e.canRemoveSet(fromExercise)) return false
        val dropped = LiftSlot(fromExercise, e.plan[fromExercise].targetSets)
        mutate { e.removeSet(fromExercise) }
        // A slot that no longer exists must not keep a warm-up mark or typed numbers: adding the set back
        // would return them silently, from input the user gave a set they then removed.
        pendingWarmups.remove(dropped)
        pendingValues.remove(dropped)
        persist()
        return true
    }

    /**
     * Fill in or correct a set's numbers — **any** set, at any time.
     *
     * A set that has been performed is edited in the engine. A set that has NOT been performed cannot be
     * (that would invent it), so its numbers are held in [pendingValues] until it is. From the screen the
     * two are indistinguishable, which is the point.
     */
    fun updateSet(slot: LiftSlot, weightKg: Double?, reps: Int?, rpe: Double?, isWarmup: Boolean) {
        val e = engine ?: return
        if (e.recordedSet(slot) != null) {
            mutate { e.updateSet(slot, weightKg, reps, rpe, isWarmup) }
        } else if (e.planItem(slot) != null) {
            val values = PendingSetValues(weightKg, reps, rpe)
            // Clearing the last field clears the entry rather than leaving an empty one behind, so the row
            // goes back to showing the plan's grey ghost instead of a blank it has to keep.
            if (values.isEmpty) pendingValues.remove(slot) else pendingValues[slot] = values
            revision++
        }
        persist()
    }

    /** Mark a slot as a warm-up (or not). Applies immediately when the set already exists. */
    fun setWarmup(slot: LiftSlot, isWarmup: Boolean) {
        if (isWarmup) pendingWarmups[slot] = true else pendingWarmups.remove(slot)
        val row = engine?.recordedSet(slot)
        if (row != null) {
            mutate { engine?.updateSet(slot, row.weightKg, row.reps, row.rpe, isWarmup) }
        } else {
            revision++
        }
        persist()
    }

    fun isWarmup(slot: LiftSlot): Boolean =
        engine?.recordedSet(slot)?.isWarmup ?: pendingWarmups.containsKey(slot)

    /** What a slot is currently showing: what it recorded, or what was typed into it in advance. */
    fun enteredValues(slot: LiftSlot): PendingSetValues {
        val row = engine?.recordedSet(slot)
        if (row != null) return PendingSetValues(row.weightKg, row.reps, row.rpe)
        return pendingValues[slot] ?: PendingSetValues(null, null, null)
    }

    fun undo() {
        mutate { engine?.undo() }
        persist()
    }

    fun finish() {
        mutate { engine?.finish(now()) }
        persist()
    }

    /**
     * Carry a pre-marked warm-up, and any numbers typed in advance, onto the set just recorded.
     *
     * The set is recorded with its timing only, so typed numbers become its own and a field left untouched
     * stays grey — typing only the weight does not blank the reps. The entry is CONSUMED: a redo drops the
     * record and should show the ghosts again, and leaving the entry behind would resurrect numbers the
     * user is in the middle of redoing.
     */
    private fun applyPendingInput() {
        val e = engine ?: return
        val last = e.sets.lastOrNull() ?: return
        val typed = pendingValues.remove(last.slot)
        val warmup = last.isWarmup || pendingWarmups.containsKey(last.slot)
        if (typed == null && warmup == last.isWarmup) return
        e.updateSet(
            last.slot,
            weightKg = typed?.weightKg ?: last.weightKg,
            reps = typed?.reps ?: last.reps,
            rpe = typed?.rpe ?: last.rpe,
            isWarmup = warmup,
        )
    }

    // MARK: - Presentation
    //
    // What a running session looks like, resolved ONCE here rather than in each surface that shows it.
    // The minimised bar and the full sheet display the same four things — state, exercise, numbers,
    // clock — and they must agree, including the wording. Two copies of this drifted the moment one was
    // edited.

    internal data class Presentation(
        val isResting: Boolean,
        /** The exercise being worked or rested from; the program's name during the warm-up. */
        val exercise: String,
        /**
         * "Set 2", "Resting after set 2", "Ready for the next set", "Warm-up" — the DECISION, with the
         * words resolved at the surface that draws it (see [DisplayText]).
         */
        val status: DisplayText,
        /** "8 x 30 kg", already unit-converted. Null when neither reps nor weight is known. */
        val detail: DisplayText?,
        /** "Next: Set 3 · Bench press" — see [nextLine]. */
        val next: DisplayText,
        val stageStartedAt: Int,
        /** When the running rest is due to end. Null while working. */
        val restEndsAt: Int?,
    )

    /**
     * [sessionLabel] is the fallback name for a session with no program: iOS writes
     * `programName ?? String(localized: "Session")`, and Android has to be handed the words because
     * resolving a resource needs a Context this class does not own.
     */
    internal fun presentation(system: UnitSystem, sessionLabel: String): Presentation? {
        val e = engine ?: return null
        if (e.isFinished) return null
        val next = nextLine(e)
        val slot = e.currentSlot
        val item = slot?.let { e.planItem(it) }
        if (slot == null || item == null) {
            return Presentation(
                isResting = false,
                exercise = programName ?: sessionLabel,
                status = DisplayText.Resource(R.string.l10n_lift_warm_up_fb1869bf),
                detail = null, next = next,
                stageStartedAt = e.stageStartedAt, restEndsAt = null,
            )
        }
        val detail = setNumbers(slot, system)
        val stage = e.stage
        return if (stage is LiftSessionEngine.Stage.Resting) {
            val ready = stage.endsAt <= now()
            Presentation(
                isResting = true, exercise = item.exercise,
                status = if (ready) {
                    DisplayText.Resource(R.string.l10n_lift_ready_for_the_next_set_c7acd2db)
                } else {
                    DisplayText.Resource(R.string.l10n_lift_resting_after_set_lld_7798216c, listOf(slot.setIndex))
                },
                detail = detail, next = next,
                stageStartedAt = e.stageStartedAt, restEndsAt = stage.endsAt,
            )
        } else {
            Presentation(
                isResting = false, exercise = item.exercise,
                status = DisplayText.Resource(R.string.l10n_lift_set_lld_beffc9de, listOf(slot.setIndex)),
                detail = detail, next = next,
                stageStartedAt = e.stageStartedAt, restEndsAt = null,
            )
        }
    }

    /**
     * Reps x weight for a slot, as "8 x 30 kg": what the set's row on the sheet shows — typed numbers,
     * else the grey ones. That includes numbers typed into a set BEFORE it is recorded, which the row
     * shows black. Without them the bar showed the grey plan for the set being lifted while its row showed
     * what was typed.
     */
    internal fun setNumbers(slot: LiftSlot, system: UnitSystem): DisplayText? {
        if (engine == null) return null
        val grey = values(slot)
        val typed = pendingValues[slot]
        val shown = LiftSetCarry(
            weightKg = typed?.weightKg ?: grey.weightKg,
            reps = typed?.reps ?: grey.reps,
        )
        val weight = shown.weightKg?.let {
            "${LiftFormat.trim(LiftFormat.display(it, system))} ${LiftFormat.weightUnit(system)}"
        }
        return when {
            // "8 x 30 kg" is plain interpolation on the Apple side and is not in its String Catalog, so
            // it stays unlocalised here too; "8 reps" IS localised there, so it is copy here.
            shown.reps != null && weight != null -> DisplayText.Dynamic("${shown.reps} x $weight")
            shown.reps != null ->
                DisplayText.Resource(R.string.l10n_lift_reps_515a3a8f, listOf(shown.reps))
            weight != null -> DisplayText.Dynamic(weight)
            else -> null
        }
    }

    /** Hand over what the store knows about previous sessions. Safe to call again if it reloads. */
    fun setLastSession(values: Map<String, Map<Int, LiftSetCarry>>) {
        lastSession = values
        revision++
    }

    /**
     * The grey numbers a slot shows — the ONE chain every surface reads, so the sheet and the minimised
     * bar cannot disagree about them.
     */
    fun carry(slot: LiftSlot): LiftSetCarry =
        engine?.carry(slot, lastSessionSets(slot)) ?: LiftSetCarry.none

    /** What a slot counts as: typed numbers, else its grey ones. */
    fun values(slot: LiftSlot): LiftSetCarry =
        engine?.values(slot, lastSessionSets(slot)) ?: LiftSetCarry.none

    private fun lastSessionSets(slot: LiftSlot): Map<Int, LiftSetCarry> =
        engine?.planItem(slot)?.let { lastSession[it.exercise] } ?: emptyMap()

    // MARK: - Finishing

    /** Sets never started. Finishing asks once whether to complete them or discard them. */
    val unfinishedSlots: List<LiftSlot> get() = engine?.unperformedSlots ?: emptyList()

    /**
     * One set as the finished session saves it. Timing is null for a set completed at finish without ever
     * being started: there is no moment to record, and inventing one would give it a rest and a
     * heart-rate window it never had.
     */
    data class FinishedSet(
        val slot: LiftSlot,
        val weightKg: Double?,
        val reps: Int?,
        val rpe: Double?,
        val isWarmup: Boolean,
        val startTs: Int?,
        val endTs: Int?,
        val restSec: Int?,
    )

    /** A program line whose set count this session changed. */
    data class SetCountChange(
        val itemId: String,
        val exercise: String,
        val from: Int,
        val to: Int,
    )

    /**
     * The sets the session saves — every slot on the sheet.
     *
     * A set that was DONE is complete, with no question at finish: it saves what was typed into it, and a
     * number left blank takes the grey value the sheet showed. That includes RPE — a set left unrated
     * saves the program line's max RPE. A rating typed for the set always wins, and a previous set's
     * rating is never copied onto another; only the plan's own number fills a blank. Sets never started
     * are the only unfinished ones: they save with their grey numbers (and anything typed in advance)
     * when [completingUnfinished]; otherwise as 0 kg x 0 reps, which every figure leaves out
     * ([LiftMetrics.isPerformed]) and Edit sets still shows, so a discard made by mistake can be filled
     * back in. Done sets keep the order they happened in and their timing; sets never started follow in
     * plan order, with no timing.
     */
    fun setsToSave(completingUnfinished: Boolean): List<FinishedSet> {
        val e = engine ?: return emptyList()
        fun planned(slot: LiftSlot): Double? = e.planItem(slot)?.targetRpe
        val out = ArrayList<FinishedSet>(e.sets.size + e.allSlots.size)
        for (set in e.sets) {
            val shown = values(set.slot)
            out += FinishedSet(
                slot = set.slot, weightKg = shown.weightKg, reps = shown.reps,
                rpe = set.rpe ?: planned(set.slot), isWarmup = set.isWarmup,
                startTs = set.startTs, endTs = set.endTs, restSec = set.restSec,
            )
        }
        for (slot in e.allSlots) {
            if (e.isCompleted(slot)) continue
            val typed = pendingValues[slot]
            val grey = carry(slot)
            out += FinishedSet(
                slot = slot,
                weightKg = if (completingUnfinished) typed?.weightKg ?: grey.weightKg else 0.0,
                reps = if (completingUnfinished) typed?.reps ?: grey.reps else 0,
                rpe = if (completingUnfinished) typed?.rpe ?: planned(slot) else null,
                isWarmup = pendingWarmups.containsKey(slot),
                startTs = null, endTs = null, restSec = null,
            )
        }
        return out
    }

    // MARK: - The rest's two moments
    //
    // A running session changes with time alone at two moments of each rest: the warning buzz
    // [restWarningLeadSec] before it ends, and its end. Each gets a ONE-SHOT timer, set when the rest
    // starts and replaced whenever the rest does; between taps a session does no work at all.
    //
    // It used to tick once a second and publish the tick to every screen watching the session — the whole
    // tab shell, the session sheet, the bar, the Lift Log hub — so all of them were redrawn every second,
    // on screen or not. iOS killed NOOP four times in one gym session for background CPU ("over 80% for
    // 60 s, busy redrawing views"), and every kill cost a Lock Screen banner and the log before it. The
    // clocks on screen tick by themselves, and only while shown.

    private fun scheduleRestTimers() {
        val endsAt = (engine?.stage as? LiftSessionEngine.Stage.Resting)?.endsAt
        if (endsAt == scheduledRestEnd) return
        scheduledRestEnd = endsAt
        restJobs.forEach { it.cancel() }
        restJobs = emptyList()
        if (endsAt == null) return
        val s = scope ?: return
        val times = restEventTimes(endsAt, now())
        restJobs = buildList {
            add(s.launch { fireAt(times.warning) { fireRestWarningIfDue() } })
            times.end?.let { end -> add(s.launch { fireAt(end) { restDidEnd() } }) }
        }
    }

    private suspend fun fireAt(unix: Int, action: () -> Unit) {
        val waitMs = (unix - now()) * 1000L
        if (waitMs > 0) delay(waitMs)
        action()
    }

    private fun fireRestWarningIfDue() {
        val e = engine ?: return
        val resting = e.stage as? LiftSessionEngine.Stage.Resting ?: return
        if (warnedFor == resting.endsAt) return
        if (resting.endsAt - now() > restWarningLeadSec) return
        warnedFor = resting.endsAt
        buzz(restWarningBuzzes)
    }

    /**
     * The rest is over: the one moment a session's words change with time alone — "Resting after set 2"
     * becomes "Ready for the next set".
     */
    private fun restDidEnd() {
        revision++
    }

    // MARK: - Persistence

    private fun persist() {
        val e = engine ?: return
        if (e.isFinished) return
        // A fresh VALUE each time, never the engine itself: the engine mutates in place, so handing it
        // over would let the next tap edit the snapshot a store was still writing.
        persistSnapshot(
            LiftSessionPersistence.snapshot(
                engine = e,
                programId = programId,
                programName = programName,
                pendingValues = pendingValues,
                pendingWarmups = pendingWarmups.keys,
            ),
        )
    }

    companion object {
        /** One pulse confirms a strap double-tap registered. */
        const val advanceConfirmBuzzes = 1

        /** Three means the rest is nearly up. Two patterns that cannot be mistaken for each other. */
        const val restWarningBuzzes = 3

        /** How long before the rest ends the warning fires. */
        const val restWarningLeadSec = 5

        /**
         * A strap double-tap this soon after the last one the session acted on is taken as a knock.
         */
        const val strapKnockWindowSec = 5

        /**
         * Whether a strap double-tap [secondsSinceLastStep] after the last one the session acted on is a
         * knock rather than a tap.
         *
         * The strap's own sensor log for a real session shows two double-taps it detected 3 s and 4 s
         * after one that had just started a set — the arm going onto the bar, not a second tap. Each was
         * a genuine detection with its own timestamp, so the de-duplication in the frame router rightly
         * let it through, and each finished a set seconds old and started its rest: "it skipped two
         * things when it should have done only one". Only the timing tells such a knock from a tap.
         *
         * Under [strapKnockWindowSec] counts as a knock, because no set a lifter means to finish, and no
         * rest a lifter means to end, is that short. It was 8 s at first; after the next session that was
         * found too long to wait for a deliberate second tap, and 5 s still covers the knocks measured at
         * +2.9 s and +4.1 s. The exception is a rest that is already over — a line planned with no rest,
         * or the one left once every set is done — where going straight on is the plan. The on-screen
         * button is never held back: a knock does not press it.
         */
        fun isKnock(secondsSinceLastStep: Int, stage: LiftSessionEngine.Stage, now: Int): Boolean {
            if (secondsSinceLastStep !in 0 until strapKnockWindowSec) return false
            val resting = stage as? LiftSessionEngine.Stage.Resting
            if (resting != null && resting.endsAt <= now) return false
            return true
        }

        /** A rest's two moments, in unix seconds. See [restEventTimes]. */
        data class RestEventTimes(val warning: Int, val end: Int?)

        /**
         * When a rest's warning and end fire, in unix seconds. The warning comes [restWarningLeadSec]
         * before the end, or one second from now when the rest is already inside that window (a short
         * rest, none at all, or the rest left once every set is done) — clear of the confirming buzz the
         * same tap just sent. The end fires only for a rest still to run.
         */
        fun restEventTimes(endsAt: Int, now: Int): RestEventTimes =
            RestEventTimes(
                warning = maxOf(now + 1, endsAt - restWarningLeadSec),
                end = if (endsAt > now) endsAt else null,
            )

        /**
         * The set after this one, as the bar shows it on one line.
         *
         * It replaced "3 of 19 sets done", which answered nothing a lifter acts on mid-session, while the
         * set coming up says where to walk. It is always a SET, never the rest before it. The set number
         * comes before the exercise so that a narrow line cuts the name, not the number. "Last set" while
         * the final set is worked; "All sets done" once it is.
         */
        internal fun nextLine(engine: LiftSessionEngine): DisplayText {
            val upcoming = engine.upcomingSlot
            val item = upcoming?.let { engine.planItem(it) }
            if (upcoming != null && item != null) {
                return DisplayText.Resource(
                    R.string.l10n_lift_next_set_lld_7d9ca288,
                    listOf(upcoming.setIndex, item.exercise),
                )
            }
            return if (engine.allCompleted) {
                DisplayText.Resource(R.string.l10n_lift_all_sets_done_615a1502)
            } else {
                DisplayText.Resource(R.string.l10n_lift_last_set_f3eaef5d)
            }
        }

        /** Whether any of [sets] was performed. When none was, there is nothing to file. */
        fun anyPerformed(sets: List<FinishedSet>): Boolean = sets.any { LiftMetrics.isPerformed(reps = it.reps) }

        /**
         * Lines whose set count in this session differs from the program's current one. A line with no
         * count counts as one set, as it does when a session starts, and a line deleted from the program
         * since is skipped rather than resurrected — as is a line added during the session, which the
         * program does not have yet.
         */
        fun setCountChanges(plan: List<LiftPlanItem>, program: List<LiftProgramItemRow>): List<SetCountChange> {
            val byId = program.associateBy { it.id }
            return plan.mapNotNull { line ->
                val id = line.programItemId ?: return@mapNotNull null
                val row = byId[id] ?: return@mapNotNull null
                val saved = maxOf(1, row.targetSets ?: 1)
                if (saved == line.targetSets) null
                else SetCountChange(itemId = id, exercise = line.exercise, from = saved, to = line.targetSets)
            }
        }

        /**
         * The program's lines with each one's HEAVIEST done set this session as its new weight and reps.
         *
         * Numbers typed during a session update the program, without asking. A line holds one weight and
         * one rep count for all its sets, so it takes the heaviest set — the working weight, which a
         * lighter back-off set must not pull down; between sets of equal weight, the one with more reps.
         * Only sets actually done count: a warm-up, a set discarded to zeros and a set completed at finish
         * without being started move nothing. A number the heaviest set does not have is left as it was; a
         * leftover rep-range top below the new count is dropped, since the editor keeps one rep count.
         */
        fun applyingHeaviestSets(
            sets: List<FinishedSet>,
            plan: List<LiftPlanItem>,
            items: List<LiftProgramItemRow>,
        ): List<LiftProgramItemRow> {
            val heaviest = HashMap<String, FinishedSet>()
            for (set in sets) {
                if (set.startTs == null || set.isWarmup || !LiftMetrics.isPerformed(reps = set.reps)) continue
                val id = plan.getOrNull(set.slot.exerciseIndex)?.programItemId ?: continue
                val best = heaviest[id]
                if (best != null && !isHeavier(set, best)) continue
                heaviest[id] = set
            }
            return items.map { row ->
                val top = heaviest[row.id] ?: return@map row
                var edited = row
                top.weightKg?.let { edited = edited.copy(targetWeightKg = it) }
                top.reps?.let { reps ->
                    edited = edited.copy(targetRepsLow = reps)
                    val high = edited.targetRepsHigh
                    if (high != null && high < reps) edited = edited.copy(targetRepsHigh = null)
                }
                edited
            }
        }

        /** More weight wins; at equal weight, more reps. A missing number ranks below any number. */
        private fun isHeavier(a: FinishedSet, than: FinishedSet): Boolean {
            val weightA = a.weightKg ?: -1.0
            val weightB = than.weightKg ?: -1.0
            if (weightA != weightB) return weightA > weightB
            return (a.reps ?: -1) > (than.reps ?: -1)
        }

        /**
         * The program's lines after this session — what the session's save writes.
         *
         * Every line already in the program takes its heaviest done set (always). With [keepingChanges] —
         * the answer to the finish sheet's program question — changed set counts move too, and each
         * exercise added during the session becomes a new line at the end, in the order added: the
         * session's set count for it, its heaviest done set's weight and reps, else 0 kg x 0 reps, and
         * nothing else (the rest is set later in the program editor).
         */
        fun programAfterSession(
            sets: List<FinishedSet>,
            plan: List<LiftPlanItem>,
            program: List<LiftProgramItemRow>,
            keepingChanges: Boolean,
            programId: String,
            deviceId: String,
        ): List<LiftProgramItemRow> {
            var lines = program
            if (keepingChanges) {
                lines = applying(setCountChanges(plan, program), lines)
                val next = (program.mapNotNull { it.ord }.maxOrNull() ?: -1) + 1
                var offset = 0
                for (line in plan.filter { it.addedInSession }) {
                    val id = line.programItemId
                    if (id == null || lines.any { it.id == id }) continue
                    lines = lines + LiftProgramItemRow(
                        id = id, deviceId = deviceId, programId = programId, ord = next + offset,
                        exercise = line.exercise, targetSets = line.targetSets,
                        targetRepsLow = 0, targetRepsHigh = null, targetRpe = null, targetWeightKg = 0.0,
                        restSec = null, note = null,
                    )
                    offset++
                }
            }
            return applyingHeaviestSets(sets, plan, lines)
        }

        /** The program's lines with [changes] applied. Only `targetSets` moves. */
        fun applying(changes: List<SetCountChange>, to: List<LiftProgramItemRow>): List<LiftProgramItemRow> {
            val counts = changes.associate { it.itemId to it.to }
            return to.map { row -> counts[row.id]?.let { row.copy(targetSets = it) } ?: row }
        }
    }
}

/** A fresh id for a session-owned row. */
private fun newId(): String = java.util.UUID.randomUUID().toString()
