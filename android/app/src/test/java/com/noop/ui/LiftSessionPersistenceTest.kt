package com.noop.ui

import com.noop.data.LiftMuscle
import com.noop.data.LiftPlanItem
import com.noop.data.LiftRecordedSet
import com.noop.data.LiftSessionEngine
import com.noop.data.LiftSlot
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [LiftSessionPersistence], the Kotlin twin of `Strand/Data/LiftSessionPersistence.swift`.
 *
 * The codec is deliberately Context-free — the SharedPreferences half is a three-line wrapper in
 * [LiftSessionStore] — so the whole durable contract is exercised here on the JVM with no store.
 *
 * A session snapshot is written on EVERY tap, so the two properties that matter most are pinned as
 * hard as the fields are: a snapshot that cannot be read back costs the user their workout, and an
 * unstable encoding rewrites the preferences file forever for no reason.
 *
 * NOTE ON VERIFICATION. `docs/CONTRIBUTING.md` asks for a port of this kind to be pinned by an oracle:
 * compile the Swift twin standalone and paste its stdout as the expected literal here. No Swift
 * toolchain exists on the machine this was ported on, so that was not possible. What stands in its
 * place is the invariants the Swift doc comments state explicitly (forward-tolerant stage box,
 * bound-checked decode, optional-because-older-build pending fields, stable ordering). That is weaker
 * than an oracle and should be strengthened to one on a machine with Xcode.
 */
class LiftSessionPersistenceTest {

    private val now = 1_700_000_000

    private fun planItem(
        exercise: String = "Squat",
        primaryMuscle: String? = "quads",
        secondaryMuscles: List<String> = listOf("glutes"),
        targetSets: Int = 4,
        restSec: Int = 120,
        targetRepsLow: Int? = 5,
        targetRepsHigh: Int? = null,
        targetRpe: Double? = 8.0,
        targetWeightKg: Double? = 100.0,
        note: String? = null,
        programItemId: String? = "i1",
        addedInSession: Boolean? = null,
    ) = LiftSessionPersistence.Snapshot.PlanItem(
        exercise = exercise, primaryMuscle = primaryMuscle, secondaryMuscles = secondaryMuscles,
        targetSets = targetSets, restSec = restSec, targetRepsLow = targetRepsLow,
        targetRepsHigh = targetRepsHigh, targetRpe = targetRpe, targetWeightKg = targetWeightKg,
        note = note, programItemId = programItemId, addedInSession = addedInSession,
    )

    private fun recordedSet(
        exerciseIndex: Int = 0,
        setIndex: Int = 1,
        weightKg: Double? = 100.0,
        reps: Int? = 5,
        rpe: Double? = 8.5,
        isWarmup: Boolean = false,
        startTs: Int = now - 300,
        endTs: Int = now - 255,
        restSec: Int? = 120,
    ) = LiftSessionPersistence.Snapshot.RecordedSet(
        exerciseIndex = exerciseIndex, setIndex = setIndex, weightKg = weightKg, reps = reps, rpe = rpe,
        isWarmup = isWarmup, startTs = startTs, endTs = endTs, restSec = restSec,
    )

    /** Everything populated, so a dropped field shows up as a round-trip inequality. */
    private fun fullSnapshot(
        stage: LiftSessionPersistence.Snapshot.StageBox = LiftSessionPersistence.Snapshot.StageBox(
            kind = "resting", item = 0, set = 1, endsAt = now + 60,
        ),
    ) = LiftSessionPersistence.Snapshot(
        startSec = now - 600,
        programId = "p1",
        programName = "Upper A",
        plan = listOf(
            planItem(),
            planItem(
                exercise = "Curl", primaryMuscle = "biceps", secondaryMuscles = listOf("forearms"),
                targetSets = 1, restSec = 90, targetRepsLow = 0, targetRepsHigh = 12,
                targetRpe = null, targetWeightKg = 0.0, note = "slow eccentric",
                programItemId = "i2", addedInSession = true,
            ),
        ),
        stage = stage,
        sets = listOf(recordedSet(), recordedSet(setIndex = 2, weightKg = 90.0, reps = 8, isWarmup = true)),
        stageStartedAt = now - 60,
        pendingValues = listOf(
            LiftSessionPersistence.Snapshot.PendingValue(1, 1, 20.0, 12, null),
            LiftSessionPersistence.Snapshot.PendingValue(0, 3, null, 5, 9.0),
        ),
        pendingWarmups = listOf(
            LiftSessionPersistence.Snapshot.SlotBox(1, 1),
            LiftSessionPersistence.Snapshot.SlotBox(0, 3),
        ),
    )

    // MARK: - The round trip

    @Test
    fun `a full snapshot survives the round trip`() {
        val before = fullSnapshot()
        val raw = LiftSessionPersistence.encode(before)
        assertNotNull("encoding a representable snapshot must not fail", raw)
        assertEquals(before, LiftSessionPersistence.decode(raw, now = now))
    }

    /** The two fields a snapshot written by an older build does not have: absent is not corrupt. */
    @Test
    fun `an old snapshot without the pending fields decodes to nothing pending`() {
        val raw = LiftSessionPersistence.encode(fullSnapshot())!!
        val stripped = JSONObject(raw).apply {
            remove("pendingValues")
            remove("pendingWarmups")
        }.toString()

        val decoded = LiftSessionPersistence.decode(stripped, now = now)!!
        assertNull(decoded.pendingValues)
        assertNull(decoded.pendingWarmups)
        assertTrue(
            "and the bridges turn that into an empty map, not a crash",
            LiftSessionPersistence.pendingValues(decoded).isEmpty(),
        )
        assertTrue(LiftSessionPersistence.pendingWarmups(decoded).isEmpty())
    }

    /** An `addedInSession` line from before lines could be added is a line from the program. */
    @Test
    fun `an absent addedInSession decodes to null and rebuilds as false`() {
        val raw = LiftSessionPersistence.encode(fullSnapshot())!!
        val stripped = JSONObject(raw).apply {
            val plan = getJSONArray("plan")
            plan.getJSONObject(1).remove("addedInSession")
        }.toString()

        val decoded = LiftSessionPersistence.decode(stripped, now = now)!!
        assertNull(decoded.plan[1].addedInSession)
        assertFalse(LiftSessionPersistence.engine(decoded).plan[1].addedInSession)
    }

    // MARK: - Stable encoding

    @Test
    fun `encoding is stable across two writes`() {
        val s = fullSnapshot()
        assertEquals(LiftSessionPersistence.encode(s), LiftSessionPersistence.encode(s))
    }

    /**
     * A map and a set have no order, so the bridge sorts them: otherwise the same session encodes two
     * different blobs and the preferences file is rewritten on every tap.
     */
    @Test
    fun `the pending fields are sorted, so insertion order cannot change the blob`() {
        val engine = LiftSessionPersistence.engine(fullSnapshot())
        val shuffled = LiftSessionPersistence.snapshot(
            engine = engine, programId = "p1", programName = "Upper A",
            pendingValues = mapOf(
                LiftSlot(1, 1) to LiftSessionController.PendingSetValues(20.0, 12, null),
                LiftSlot(0, 3) to LiftSessionController.PendingSetValues(null, 5, 9.0),
            ),
            pendingWarmups = listOf(LiftSlot(1, 1), LiftSlot(0, 3)),
        )
        assertEquals(listOf(0 to 3, 1 to 1), shuffled.pendingValues!!.map { it.exerciseIndex to it.setIndex })
        assertEquals(listOf(0 to 3, 1 to 1), shuffled.pendingWarmups!!.map { it.exerciseIndex to it.setIndex })
    }

    /** A nil optional is OMITTED, as Swift's JSONEncoder does — the same keys on both sides. */
    @Test
    fun `a nil optional is absent from the JSON rather than written as null`() {
        val raw = LiftSessionPersistence.encode(
            fullSnapshot().copy(programId = null, programName = null),
        )!!
        val o = JSONObject(raw)
        assertFalse(o.has("programId"))
        assertFalse(o.has("programName"))
        assertFalse(o.getJSONArray("plan").getJSONObject(0).has("note"))
        assertTrue("a rest already taken is written", o.getJSONArray("sets").getJSONObject(0).has("restSec"))
        assertTrue("a null RPE on the second plan line is absent", !o.getJSONArray("plan").getJSONObject(1).has("targetRpe"))
        assertTrue("and a name present is written", o.getJSONArray("plan").getJSONObject(0).has("exercise"))
    }

    // MARK: - The stage box

    @Test
    fun `every stage boxes and unboxes`() {
        val stages = listOf(
            LiftSessionEngine.Stage.Warmup,
            LiftSessionEngine.Stage.Working(LiftSlot(1, 3)),
            LiftSessionEngine.Stage.Resting(LiftSlot(0, 2), endsAt = now + 45),
            LiftSessionEngine.Stage.Finished,
        )
        for (stage in stages) {
            val engine = LiftSessionEngine.restoring(
                plan = listOf(LiftPlanItem("Squat")), startTs = now - 10,
                stage = stage, sets = emptyList(), stageStartedAt = now - 5,
            )
            val rebuilt = LiftSessionPersistence.engine(
                LiftSessionPersistence.decode(
                    LiftSessionPersistence.encode(
                        LiftSessionPersistence.snapshot(engine, null, null, emptyMap(), emptyList()),
                    )!!,
                    now = now,
                )!!,
            )
            assertEquals(stage, rebuilt.stage)
            assertEquals(now - 5, rebuilt.stageStartedAt)
        }
    }

    /**
     * Anything unrecognised falls back to the warm-up — including the retired "cooldown" kind an earlier
     * build wrote, and a box that lost the indices it needs. Every COMPLETED set rides in `sets`, so
     * only the cursor's position is forgotten.
     */
    @Test
    fun `an unrecognised or incomplete stage decodes to the warm-up`() {
        fun rebuild(box: LiftSessionPersistence.Snapshot.StageBox): LiftSessionEngine.Stage =
            LiftSessionPersistence.engine(fullSnapshot(stage = box)).stage

        assertEquals(LiftSessionEngine.Stage.Warmup, rebuild(LiftSessionPersistence.Snapshot.StageBox("cooldown")))
        assertEquals(LiftSessionEngine.Stage.Warmup, rebuild(LiftSessionPersistence.Snapshot.StageBox("warmup")))
        assertEquals(LiftSessionEngine.Stage.Warmup, rebuild(LiftSessionPersistence.Snapshot.StageBox("working")))
        assertEquals(
            LiftSessionEngine.Stage.Warmup,
            rebuild(LiftSessionPersistence.Snapshot.StageBox("working", item = 0)),
        )
        assertEquals(
            "a rest with no end would be a countdown to nowhere",
            LiftSessionEngine.Stage.Warmup,
            rebuild(LiftSessionPersistence.Snapshot.StageBox("resting", item = 0, set = 1)),
        )
        // And the sets are untouched by any of it: nothing logged is lost.
        assertEquals(2, LiftSessionPersistence.engine(fullSnapshot(stage = LiftSessionPersistence.Snapshot.StageBox("cooldown"))).sets.size)
    }

    // MARK: - The bounds

    @Test
    fun `decode refuses nil, blank and malformed input`() {
        assertNull(LiftSessionPersistence.decode(null, now = now))
        assertNull(LiftSessionPersistence.decode("", now = now))
        assertNull(LiftSessionPersistence.decode("   ", now = now))
        assertNull(LiftSessionPersistence.decode("not json at all", now = now))
        assertNull(LiftSessionPersistence.decode("{}", now = now))
        assertNull(LiftSessionPersistence.decode("[]", now = now))
        // Valid JSON, missing the fields the engine cannot be rebuilt without.
        assertNull(LiftSessionPersistence.decode("""{"startSec":1700000000}""", now = now))
    }

    /**
     * The anchors are bound-checked, because a corrupt write must read as "no session in flight" rather
     * than reviving a broken screen. A zero anchor would put every set in 1970; a start a day into the
     * future is not a workout that happened.
     */
    @Test
    fun `decode refuses an implausible start time or an empty plan`() {
        val good = fullSnapshot()
        assertNull("zero/garbage anchor", LiftSessionPersistence.decode(LiftSessionPersistence.encode(good.copy(startSec = 0))!!, now = now))
        assertNull("before 2001", LiftSessionPersistence.decode(LiftSessionPersistence.encode(good.copy(startSec = 1_000_000_000))!!, now = now))
        assertNull("a day into the future", LiftSessionPersistence.decode(LiftSessionPersistence.encode(good.copy(startSec = now + 86_400))!!, now = now))
        assertNull("no lines to run", LiftSessionPersistence.decode(LiftSessionPersistence.encode(good.copy(plan = emptyList()))!!, now = now))
        // Just inside the bounds, so the checks are not simply rejecting everything.
        assertNotNull(LiftSessionPersistence.decode(LiftSessionPersistence.encode(good.copy(startSec = 1_000_000_001))!!, now = now))
        assertNotNull(LiftSessionPersistence.decode(LiftSessionPersistence.encode(good.copy(startSec = now + 86_399))!!, now = now))
    }

    /** A line that arrives without a name or without its counts is dropped, not reconstructed. */
    @Test
    fun `a plan line missing its required fields is skipped`() {
        val raw = LiftSessionPersistence.encode(fullSnapshot())!!
        val damaged = JSONObject(raw).apply {
            getJSONArray("plan").getJSONObject(1).remove("targetSets")
        }.toString()
        val decoded = LiftSessionPersistence.decode(damaged, now = now)!!
        assertEquals(listOf("Squat"), decoded.plan.map { it.exercise })
    }

    /**
     * A typed field can hold a non-finite double (`toDoubleOrNull` parses "1e999" to infinity), and
     * org.json REFUSES to write one. Dropping it is the same thing the reader would do with it.
     */
    @Test
    fun `a non-finite number is dropped rather than failing the encode`() {
        val raw = LiftSessionPersistence.encode(
            fullSnapshot().copy(sets = listOf(recordedSet(weightKg = Double.POSITIVE_INFINITY, rpe = Double.NaN))),
        )
        assertNotNull("the encode must not fail on a value the decode would discard", raw)
        val set = LiftSessionPersistence.decode(raw, now = now)!!.sets.single()
        assertNull(set.weightKg)
        assertNull(set.rpe)
        assertEquals("and the rest of the set is intact", 5, set.reps)
    }

    // MARK: - The engine bridge

    @Test
    fun `the engine bridge carries the plan, the sets and the timeline`() {
        val before = fullSnapshot()
        val rebuilt = LiftSessionPersistence.engine(before)

        assertEquals(before.startSec, rebuilt.startTs)
        assertEquals(before.stageStartedAt, rebuilt.stageStartedAt)
        assertEquals(before.plan.size, rebuilt.plan.size)
        assertEquals("Squat", rebuilt.plan[0].exercise)
        assertEquals(LiftMuscle.quads, rebuilt.plan[0].primaryMuscle)
        assertEquals(listOf(LiftMuscle.glutes), rebuilt.plan[0].secondaryMuscles)
        assertEquals(4, rebuilt.plan[0].targetSets)
        assertEquals(120, rebuilt.plan[0].restSec)
        assertEquals(5, rebuilt.plan[0].targetRepsLow)
        assertEquals(8.0, rebuilt.plan[0].targetRpe!!, 0.0)
        assertEquals(100.0, rebuilt.plan[0].targetWeightKg!!, 0.0)
        assertEquals("i1", rebuilt.plan[0].programItemId)
        assertFalse(rebuilt.plan[0].addedInSession)
        assertTrue("a line added mid-session comes back as one", rebuilt.plan[1].addedInSession)
        assertEquals("slow eccentric", rebuilt.plan[1].note)

        assertEquals(
            listOf(
                LiftRecordedSet(0, 1, 100.0, 5, 8.5, false, now - 300, now - 255, 120),
                LiftRecordedSet(0, 2, 90.0, 8, 8.5, true, now - 300, now - 255, 120),
            ),
            rebuilt.sets,
        )
        assertEquals(LiftSessionEngine.Stage.Resting(LiftSlot(0, 1), now + 60), rebuilt.stage)
    }

    /**
     * A snapshot written by a NEWER build must not strand a session in an older one — the same rule
     * `LiftMuscle.decodeList` follows for the stored columns.
     */
    @Test
    fun `an unknown muscle token is dropped rather than failing the read`() {
        val snapshot = fullSnapshot().copy(
            plan = listOf(planItem(primaryMuscle = "spleen", secondaryMuscles = listOf("quads", "wings"))),
        )
        val rebuilt = LiftSessionPersistence.engine(snapshot)
        assertNull("a token this build does not know", rebuilt.plan[0].primaryMuscle)
        assertEquals(listOf(LiftMuscle.quads), rebuilt.plan[0].secondaryMuscles)
    }

    @Test
    fun `the pending values and warm-ups round-trip through the bridges`() {
        val snapshot = fullSnapshot()
        val values = LiftSessionPersistence.pendingValues(snapshot)
        assertEquals(LiftSessionController.PendingSetValues(20.0, 12, null), values[LiftSlot(1, 1)])
        assertEquals(LiftSessionController.PendingSetValues(null, 5, 9.0), values[LiftSlot(0, 3)])
        assertEquals(listOf(LiftSlot(1, 1), LiftSlot(0, 3)), LiftSessionPersistence.pendingWarmups(snapshot))
    }

    // MARK: - Through the controller

    /**
     * The whole point: a session interrupted mid-rest comes back with its plan, its logged sets, the
     * cursor where it was, and the numbers the user had typed for sets they had not reached yet.
     */
    @Test
    fun `an interrupted session is rehydrated and finishes where it left off`() {
        var t = now - 600
        var persisted: LiftSessionPersistence.Snapshot? = null
        val first = LiftSessionController(
            buzz = {}, setStrapHandler = {}, now = { t },
            persistSnapshot = { persisted = it },
        )
        first.start(
            listOf(LiftPlanItem("Squat", plannedSets = 2, targetWeightKg = 100.0, targetRepsLow = 5)),
            programId = "p1", programName = "Upper A",
        )
        first.advance()
        first.updateSet(LiftSlot(0, 2), weightKg = 95.0, reps = 6, rpe = null, isWarmup = true)
        // The warm-up mark for a set not yet performed travels on its own call — the sheet's toggle is
        // what reaches `setWarmup`; a row edit cannot mark a set nobody has done.
        first.setWarmup(LiftSlot(0, 2), isWarmup = true)
        t = now - 540
        first.advance(fromStrap = true)                     // set 1 logged, resting
        t = now - 480
        first.advance()                                      // rest over, working set 2

        val saved = persisted!!
        assertEquals("Upper A", saved.programName)

        // A fresh process: the same clock, nothing else shared.
        var resumed: LiftSessionPersistence.Snapshot? = null
        val second = LiftSessionController(
            buzz = {}, setStrapHandler = {}, now = { t },
            persistSnapshot = { resumed = it },
        )
        second.resume(saved, present = false)
        assertTrue(second.isActive)
        assertFalse("resuming does not throw the sheet in the user's face", second.isPresented)
        assertEquals("p1", second.programId)
        assertEquals("Upper A", second.programName)
        assertEquals(1, second.engine!!.sets.size)
        assertEquals(LiftSessionEngine.Stage.Working(LiftSlot(0, 2)), second.engine!!.stage)
        assertEquals(
            "the warm-up marked in advance is still marked",
            true,
            second.pendingWarmups[LiftSlot(0, 2)],
        )
        assertEquals(
            "and the numbers typed for the set not yet reached came back",
            LiftSessionController.PendingSetValues(95.0, 6, null),
            second.enteredValues(LiftSlot(0, 2)),
        )

        t = now - 400
        second.advance()
        assertEquals(2, second.engine!!.sets.size)
        assertTrue("the warm-up mark applied to the set it was made for", second.engine!!.sets[1].isWarmup)
        assertEquals(95.0, second.engine!!.sets[1].weightKg!!, 0.0)
        assertNotNull("the resumed controller persists again, so it stays crash-safe", resumed)
    }

    /** `resumeSaved` is the launch path: nothing to pick up is a no-op, not an empty session. */
    @Test
    fun `resumeSaved ignores an absent or corrupt snapshot and logs a restart when it has one`() {
        val logs = mutableListOf<String>()
        val c = LiftSessionController(buzz = {}, setStrapHandler = {}, log = { logs += it })
        assertFalse(c.isActive)

        val store = FakeLiftSnapshotStore()
        c.resumeSaved(store)
        assertFalse("no snapshot means no session", c.isActive)
        assertTrue(logs.isEmpty())

        store.raw = "not json"
        c.resumeSaved(store)
        assertFalse("a corrupt write must not revive a broken screen", c.isActive)
        assertTrue(logs.isEmpty())

        store.raw = LiftSessionPersistence.encode(fullSnapshot())!!
        c.resumeSaved(store)
        assertTrue(c.isActive)
        assertEquals(listOf("Lift Log: session picked up again after NOOP restarted"), logs)
    }

    /** Stands in for the preferences file, the way the Swift tests inject their own `UserDefaults`. */
    private class FakeLiftSnapshotStore : LiftSnapshotStore {
        var raw: String? = null
        override fun save(raw: String) {
            this.raw = raw
        }

        override fun load(): String? = raw

        override fun clear() {
            raw = null
        }
    }
}
