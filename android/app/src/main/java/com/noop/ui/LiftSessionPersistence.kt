package com.noop.ui

import android.content.Context
import android.content.SharedPreferences
import com.noop.data.LiftMuscle
import com.noop.data.LiftPlanItem
import com.noop.data.LiftRecordedSet
import com.noop.data.LiftSessionEngine
import com.noop.data.LiftSlot
import org.json.JSONArray
import org.json.JSONObject

// Crash-safety for an in-flight gym session. Kotlin twin of `Strand/Data/LiftSessionPersistence.swift`.
//
// A session is written to SharedPreferences on every tap and rehydrated on launch, so a crash, a phone
// call, a flat battery or simply swiping the app away mid-workout costs nothing. This mirrors
// [ActiveWorkoutPersistence] exactly — a small snapshot, unix-second anchors, and an encode/decode
// pair that is PURE (no SharedPreferences dependency of its own) so the round-trip is testable on the
// JVM without a Context.
//
// Unix seconds rather than a timestamp object for the same reason the store uses them: an absolute
// instant survives suspension, timezone changes and a relaunch, and it is the only anchor that keeps a
// rest countdown honest.
//
// THE SHAPE IS THE SWIFT SHAPE, key for key. The blob never crosses platforms (it is per-device and
// survives exactly one process death), so this is not a wire contract the way `.noopbak` is — it is a
// maintainability one: the two encoders are read side by side when a field is added, and a key that
// differs by accident is a difference someone has to explain later.
object LiftSessionPersistence {

    /**
     * The durable shape of an in-flight session — the minimum needed to rebuild the engine and still
     * finish and save the session after a relaunch.
     *
     * The PLAN is stored flattened rather than re-read from the program, so editing or deleting the
     * program mid-session cannot change what is being run.
     */
    data class Snapshot(
        val startSec: Int,
        val programId: String?,
        val programName: String?,
        val plan: List<PlanItem>,
        val stage: StageBox,
        val sets: List<RecordedSet>,
        val stageStartedAt: Int,
        /**
         * Numbers typed into sets that have NOT happened yet, and slots marked a warm-up in advance.
         * Both are intent the user has already expressed, so a crash must not cost them — that is the
         * whole point of this snapshot.
         *
         * Nullable because a snapshot written before these existed does not carry them: it decodes as
         * absent and the session resumes with nothing pending, which is exactly right.
         */
        val pendingValues: List<PendingValue>? = null,
        val pendingWarmups: List<SlotBox>? = null,
    ) {

        data class PendingValue(
            val exerciseIndex: Int,
            val setIndex: Int,
            val weightKg: Double?,
            val reps: Int?,
            val rpe: Double?,
        )

        data class SlotBox(val exerciseIndex: Int, val setIndex: Int)

        data class PlanItem(
            val exercise: String,
            val primaryMuscle: String?,
            val secondaryMuscles: List<String>,
            val targetSets: Int,
            val restSec: Int,
            val targetRepsLow: Int?,
            val targetRepsHigh: Int?,
            val targetRpe: Double?,
            val targetWeightKg: Double?,
            val note: String?,
            /**
             * The program line this was flattened from. Absent from a snapshot written before the set
             * count could be changed mid-session, which decodes to null and simply skips the
             * write-back — the session itself is unaffected.
             */
            val programItemId: String? = null,
            /**
             * True for a line added during the session. Absent from a snapshot written before lines
             * could be added, which decodes to null: a line from the program.
             */
            val addedInSession: Boolean? = null,
        )

        /**
         * The stage as a flat, forward-compatible record rather than an encoded enum: a persisted
         * enum with associated values is a migration hazard the moment a case is added, and this
         * shape decodes to "warm-up" rather than to garbage if it is ever read by an older build.
         */
        data class StageBox(
            /** warmup | working | resting | finished. */
            val kind: String,
            val item: Int? = null,
            val set: Int? = null,
            val endsAt: Int? = null,
        )

        data class RecordedSet(
            val exerciseIndex: Int,
            val setIndex: Int,
            val weightKg: Double?,
            val reps: Int?,
            val rpe: Double?,
            val isWarmup: Boolean,
            val startTs: Int,
            val endTs: Int,
            val restSec: Int?,
        )
    }

    /** The single key (a JSON-encoded [Snapshot]), namespaced like the Swift `noop.activeLiftSession`. */
    const val defaultsKey = "noop.activeLiftSession"

    // MARK: - Codec

    /** Encode a snapshot. Returns null only if the values cannot be represented, which never happens
     *  for a snapshot this object built. */
    fun encode(s: Snapshot): String? = runCatching { toJson(s).toString() }.getOrNull()

    /**
     * Decode a snapshot, bound-checking the untrusted persisted values. Returns null for
     * null/garbage/empty input or an implausible start time, so a corrupt write is treated as "no
     * session in flight" rather than reviving a broken screen the user cannot get out of.
     *
     * [now] is a parameter rather than a clock read so the round-trip stays pure and testable, which is
     * what the Swift version's `Date()` inside `decode` gives up.
     */
    fun decode(raw: String?, now: Int = nowUnix()): Snapshot? {
        if (raw.isNullOrBlank()) return null
        val raw0 = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val out = fromJson(raw0) ?: return null
        if (out.startSec <= 1_000_000_000) return null          // after 2001; not a zero/garbage anchor
        if (out.startSec >= now + 86_400) return null           // not a day into the future
        if (out.plan.isEmpty()) return null
        return out
    }

    /** The current unix second. */
    fun nowUnix(): Int = (System.currentTimeMillis() / 1000L).toInt()

    // MARK: - Storage

    /** Store (or, for a null snapshot, clear) the crash snapshot. */
    fun store(store: LiftSnapshotStore, snapshot: Snapshot?) {
        val raw = snapshot?.let { encode(it) }
        if (raw == null) store.clear() else store.save(raw)
    }

    /** Read the stored snapshot, or null if there is none or it is corrupt. */
    fun load(store: LiftSnapshotStore): Snapshot? = decode(store.load())

    // MARK: - Engine bridge

    fun snapshot(
        engine: LiftSessionEngine,
        programId: String?,
        programName: String?,
        pendingValues: Map<LiftSlot, LiftSessionController.PendingSetValues>,
        pendingWarmups: Collection<LiftSlot>,
    ): Snapshot = Snapshot(
        startSec = engine.startTs,
        programId = programId,
        programName = programName,
        plan = engine.plan.map {
            Snapshot.PlanItem(
                exercise = it.exercise,
                primaryMuscle = it.primaryMuscle?.name,
                secondaryMuscles = it.secondaryMuscles.map { m -> m.name },
                targetSets = it.targetSets,
                restSec = it.restSec,
                targetRepsLow = it.targetRepsLow,
                targetRepsHigh = it.targetRepsHigh,
                targetRpe = it.targetRpe,
                targetWeightKg = it.targetWeightKg,
                note = it.note,
                programItemId = it.programItemId,
                // Written only when true, so a session nobody added to encodes as before.
                addedInSession = if (it.addedInSession) true else null,
            )
        },
        stage = box(engine.stage),
        sets = engine.sets.map {
            Snapshot.RecordedSet(
                exerciseIndex = it.exerciseIndex, setIndex = it.setIndex,
                weightKg = it.weightKg, reps = it.reps, rpe = it.rpe,
                isWarmup = it.isWarmup, startTs = it.startTs, endTs = it.endTs, restSec = it.restSec,
            )
        },
        stageStartedAt = engine.stageStartedAt,
        // Sorted so the encoded snapshot is STABLE: a map and a set have no order, and an unstable
        // encoding would rewrite the preferences blob on every tap for no reason.
        pendingValues = pendingValues.entries
            .sortedWith(compareBy({ it.key.exerciseIndex }, { it.key.setIndex }))
            .map { (slot, values) ->
                Snapshot.PendingValue(
                    exerciseIndex = slot.exerciseIndex, setIndex = slot.setIndex,
                    weightKg = values.weightKg, reps = values.reps, rpe = values.rpe,
                )
            },
        pendingWarmups = pendingWarmups
            .sortedWith(compareBy({ it.exerciseIndex }, { it.setIndex }))
            .map { Snapshot.SlotBox(exerciseIndex = it.exerciseIndex, setIndex = it.setIndex) },
    )

    /** The numbers typed in advance, back as the controller holds them. */
    fun pendingValues(from: Snapshot): Map<LiftSlot, LiftSessionController.PendingSetValues> =
        (from.pendingValues ?: emptyList()).associate {
            LiftSlot(it.exerciseIndex, it.setIndex) to
                LiftSessionController.PendingSetValues(it.weightKg, it.reps, it.rpe)
        }

    /** The warm-up marks made in advance, back as the controller holds them. */
    fun pendingWarmups(from: Snapshot): List<LiftSlot> =
        (from.pendingWarmups ?: emptyList()).map { LiftSlot(it.exerciseIndex, it.setIndex) }

    /**
     * Rebuild an engine from a snapshot. Unknown muscle tokens are dropped rather than failing the
     * read, matching [LiftMuscle.decodeList]: a snapshot written by a newer build must not strand a
     * session in an older one.
     */
    fun engine(from: Snapshot): LiftSessionEngine {
        val plan = from.plan.map {
            LiftPlanItem(
                exercise = it.exercise,
                primaryMuscle = LiftMuscle.fromRaw(it.primaryMuscle),
                secondaryMuscles = it.secondaryMuscles.mapNotNull { m -> LiftMuscle.fromRaw(m) },
                plannedSets = it.targetSets,
                plannedRestSec = it.restSec,
                targetRepsLow = it.targetRepsLow,
                targetRepsHigh = it.targetRepsHigh,
                targetRpe = it.targetRpe,
                targetWeightKg = it.targetWeightKg,
                note = it.note,
                programItemId = it.programItemId,
                addedInSession = it.addedInSession == true,
            )
        }
        val sets = from.sets.map {
            LiftRecordedSet(
                exerciseIndex = it.exerciseIndex, setIndex = it.setIndex,
                weightKg = it.weightKg, reps = it.reps, rpe = it.rpe,
                isWarmup = it.isWarmup, startTs = it.startTs, endTs = it.endTs, restSec = it.restSec,
            )
        }
        return LiftSessionEngine.restoring(
            plan = plan, startTs = from.startSec,
            stage = unbox(from.stage), sets = sets, stageStartedAt = from.stageStartedAt,
        )
    }

    // MARK: - Stage boxing

    private fun box(stage: LiftSessionEngine.Stage): Snapshot.StageBox = when (stage) {
        LiftSessionEngine.Stage.Warmup -> Snapshot.StageBox(kind = "warmup")
        is LiftSessionEngine.Stage.Working ->
            Snapshot.StageBox(kind = "working", item = stage.slot.exerciseIndex, set = stage.slot.setIndex)
        is LiftSessionEngine.Stage.Resting ->
            Snapshot.StageBox(
                kind = "resting", item = stage.slot.exerciseIndex,
                set = stage.slot.setIndex, endsAt = stage.endsAt,
            )
        LiftSessionEngine.Stage.Finished -> Snapshot.StageBox(kind = "finished")
    }

    /**
     * Anything unrecognised — a `working`/`resting` box missing its indices, or the retired
     * "cooldown" kind written by an earlier build — falls back to the warm-up.
     *
     * That is a real recovery, not a shrug: every COMPLETED set is carried in `sets` regardless, so
     * nothing logged is lost. Only the cursor's position is forgotten, and the next tap simply
     * resumes at the first set still outstanding.
     */
    private fun unbox(box: Snapshot.StageBox): LiftSessionEngine.Stage {
        val i = box.item
        val s = box.set
        return when (box.kind) {
            "working" -> if (i != null && s != null) LiftSessionEngine.Stage.Working(LiftSlot(i, s)) else LiftSessionEngine.Stage.Warmup
            "resting" -> {
                val e = box.endsAt
                if (i != null && s != null && e != null) LiftSessionEngine.Stage.Resting(LiftSlot(i, s), e)
                else LiftSessionEngine.Stage.Warmup
            }
            "finished" -> LiftSessionEngine.Stage.Finished
            else -> LiftSessionEngine.Stage.Warmup
        }
    }

    // MARK: - JSON
    //
    // Hand-written rather than annotation-driven. A nil optional is OMITTED rather than written as
    // JSON null, exactly as Swift's `JSONEncoder` does, so the two encoders produce the same keys —
    // and an absent key is what a snapshot written by an older build looks like, which every reader
    // here already tolerates.

    private fun toJson(s: Snapshot): JSONObject = JSONObject().apply {
        put("startSec", s.startSec)
        putIfPresent("programId", s.programId)
        putIfPresent("programName", s.programName)
        put("plan", JSONArray().apply { s.plan.forEach { put(planItemToJson(it)) } })
        put("stage", stageToJson(s.stage))
        put("sets", JSONArray().apply { s.sets.forEach { put(recordedSetToJson(it)) } })
        put("stageStartedAt", s.stageStartedAt)
        s.pendingValues?.let { values ->
            put("pendingValues", JSONArray().apply {
                values.forEach { v -> put(pendingValueToJson(v)) }
            })
        }
        s.pendingWarmups?.let { warmups ->
            put("pendingWarmups", JSONArray().apply {
                warmups.forEach { w -> put(slotToJson(w.exerciseIndex, w.setIndex)) }
            })
        }
    }

    private fun planItemToJson(p: Snapshot.PlanItem): JSONObject = JSONObject().apply {
        put("exercise", p.exercise)
        putIfPresent("primaryMuscle", p.primaryMuscle)
        put("secondaryMuscles", JSONArray().apply { p.secondaryMuscles.forEach { put(it) } })
        put("targetSets", p.targetSets)
        put("restSec", p.restSec)
        putIfPresent("targetRepsLow", p.targetRepsLow)
        putIfPresent("targetRepsHigh", p.targetRepsHigh)
        putFiniteIfPresent("targetRpe", p.targetRpe)
        putFiniteIfPresent("targetWeightKg", p.targetWeightKg)
        putIfPresent("note", p.note)
        putIfPresent("programItemId", p.programItemId)
        // Only when true — see the Swift comment on the same line.
        if (p.addedInSession == true) put("addedInSession", true)
    }

    private fun stageToJson(stage: Snapshot.StageBox): JSONObject = JSONObject().apply {
        put("kind", stage.kind)
        putIfPresent("item", stage.item)
        putIfPresent("set", stage.set)
        putIfPresent("endsAt", stage.endsAt)
    }

    private fun recordedSetToJson(r: Snapshot.RecordedSet): JSONObject = JSONObject().apply {
        put("exerciseIndex", r.exerciseIndex)
        put("setIndex", r.setIndex)
        putFiniteIfPresent("weightKg", r.weightKg)
        putIfPresent("reps", r.reps)
        putFiniteIfPresent("rpe", r.rpe)
        put("isWarmup", r.isWarmup)
        put("startTs", r.startTs)
        put("endTs", r.endTs)
        putIfPresent("restSec", r.restSec)
    }

    private fun pendingValueToJson(p: Snapshot.PendingValue): JSONObject = JSONObject().apply {
        put("exerciseIndex", p.exerciseIndex)
        put("setIndex", p.setIndex)
        putFiniteIfPresent("weightKg", p.weightKg)
        putIfPresent("reps", p.reps)
        putFiniteIfPresent("rpe", p.rpe)
    }

    private fun slotToJson(exerciseIndex: Int, setIndex: Int): JSONObject =
        JSONObject().put("exerciseIndex", exerciseIndex).put("setIndex", setIndex)

    private fun fromJson(o: JSONObject): Snapshot? {
        val startSec = o.optInt("startSec", Int.MIN_VALUE)
        if (startSec == Int.MIN_VALUE) return null
        val planArr = o.optJSONArray("plan") ?: return null
        val stageObj = o.optJSONObject("stage") ?: return null
        val setsArr = o.optJSONArray("sets") ?: return null
        val stageStartedAt = o.optInt("stageStartedAt", Int.MIN_VALUE)
        if (stageStartedAt == Int.MIN_VALUE) return null

        val plan = (0 until planArr.length()).mapNotNull { i ->
            planArr.optJSONObject(i)?.let { p ->
                val exercise = p.optString("exercise", "")
                val targetSets = p.optInt("targetSets", Int.MIN_VALUE)
                val restSec = p.optInt("restSec", Int.MIN_VALUE)
                if (exercise.isEmpty() || targetSets == Int.MIN_VALUE || restSec == Int.MIN_VALUE) return@let null
                Snapshot.PlanItem(
                    exercise = exercise,
                    primaryMuscle = p.textOrNull("primaryMuscle"),
                    secondaryMuscles = p.optJSONArray("secondaryMuscles").let { arr ->
                        if (arr == null) emptyList()
                        else (0 until arr.length()).mapNotNull { j -> arr.optString(j, "").takeIf { it.isNotEmpty() } }
                    },
                    targetSets = targetSets,
                    restSec = restSec,
                    targetRepsLow = p.intOrNull("targetRepsLow"),
                    targetRepsHigh = p.intOrNull("targetRepsHigh"),
                    targetRpe = p.finiteOrNull("targetRpe"),
                    targetWeightKg = p.finiteOrNull("targetWeightKg"),
                    note = p.textOrNull("note"),
                    programItemId = p.textOrNull("programItemId"),
                    addedInSession = p.boolOrNull("addedInSession"),
                )
            }
        }

        val sets = (0 until setsArr.length()).mapNotNull { i ->
            setsArr.optJSONObject(i)?.let { r ->
                val exerciseIndex = r.optInt("exerciseIndex", Int.MIN_VALUE)
                val setIndex = r.optInt("setIndex", Int.MIN_VALUE)
                val startTs = r.optInt("startTs", Int.MIN_VALUE)
                val endTs = r.optInt("endTs", Int.MIN_VALUE)
                if (exerciseIndex == Int.MIN_VALUE || setIndex == Int.MIN_VALUE) return@let null
                if (startTs == Int.MIN_VALUE || endTs == Int.MIN_VALUE) return@let null
                Snapshot.RecordedSet(
                    exerciseIndex = exerciseIndex, setIndex = setIndex,
                    weightKg = r.finiteOrNull("weightKg"), reps = r.intOrNull("reps"),
                    rpe = r.finiteOrNull("rpe"), isWarmup = r.optBoolean("isWarmup", false),
                    startTs = startTs, endTs = endTs, restSec = r.intOrNull("restSec"),
                )
            }
        }

        return Snapshot(
            startSec = startSec,
            programId = o.textOrNull("programId"),
            programName = o.textOrNull("programName"),
            plan = plan,
            stage = Snapshot.StageBox(
                kind = stageObj.optString("kind", ""),
                item = stageObj.intOrNull("item"),
                set = stageObj.intOrNull("set"),
                endsAt = stageObj.intOrNull("endsAt"),
            ),
            sets = sets,
            stageStartedAt = stageStartedAt,
            pendingValues = o.optJSONArray("pendingValues")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { v ->
                        val exerciseIndex = v.optInt("exerciseIndex", Int.MIN_VALUE)
                        val setIndex = v.optInt("setIndex", Int.MIN_VALUE)
                        if (exerciseIndex == Int.MIN_VALUE || setIndex == Int.MIN_VALUE) return@let null
                        Snapshot.PendingValue(
                            exerciseIndex = exerciseIndex, setIndex = setIndex,
                            weightKg = v.finiteOrNull("weightKg"), reps = v.intOrNull("reps"),
                            rpe = v.finiteOrNull("rpe"),
                        )
                    }
                }
            },
            pendingWarmups = o.optJSONArray("pendingWarmups")?.let { arr ->
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { w ->
                        val exerciseIndex = w.optInt("exerciseIndex", Int.MIN_VALUE)
                        val setIndex = w.optInt("setIndex", Int.MIN_VALUE)
                        if (exerciseIndex == Int.MIN_VALUE || setIndex == Int.MIN_VALUE) return@let null
                        Snapshot.SlotBox(exerciseIndex, setIndex)
                    }
                }
            },
        )
    }

    private fun JSONObject.putIfPresent(key: String, value: Any?) {
        if (value != null) put(key, value)
    }

    /**
     * `org.json` REFUSES to write a non-finite double ([Double.NaN] / an infinity throw
     * `JSONException`), and a typed field can hold one: `toDoubleOrNull` parses `"1e999"` to infinity.
     * Writing it as absent is the same thing the reader would do with it anyway — the reader drops a
     * non-finite number — so the encode cannot fail on a value the decode would have discarded.
     */
    private fun JSONObject.putFiniteIfPresent(key: String, value: Double?) {
        if (value != null && value.isFinite()) put(key, value)
    }

    /** A key that is absent OR explicitly null reads as null, matching a Swift optional. */
    private fun JSONObject.textOrNull(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key, "").takeIf { it.isNotEmpty() }

    private fun JSONObject.intOrNull(key: String): Int? =
        if (!has(key) || isNull(key)) null else optInt(key, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }

    private fun JSONObject.finiteOrNull(key: String): Double? {
        if (!has(key) || isNull(key)) return null
        val v = optDouble(key, Double.NaN)
        return v.takeIf { it.isFinite() }
    }

    private fun JSONObject.boolOrNull(key: String): Boolean? =
        if (!has(key) || isNull(key)) null else optBoolean(key, false)
}

/**
 * The three operations the crash snapshot needs from storage. This interface is Android's stand-in for
 * the `UserDefaults` parameter the Swift version injects, so the launch path
 * ([LiftSessionController.resumeSaved]) and the codec are both testable with no Context and no
 * preferences file.
 */
interface LiftSnapshotStore {
    fun save(raw: String)
    fun load(): String?
    fun clear()
}

/**
 * The SharedPreferences implementation: one key in its own preferences file, mirroring the Swift
 * `LiftSessionPersistence.store`/`load`/`clear` over `UserDefaults.standard`.
 */
class LiftSessionStore(private val prefs: SharedPreferences) : LiftSnapshotStore {

    override fun save(raw: String) {
        prefs.edit().putString(KEY_SNAPSHOT, raw).apply()
    }

    override fun load(): String? = prefs.getString(KEY_SNAPSHOT, null)

    override fun clear() {
        prefs.edit().remove(KEY_SNAPSHOT).apply()
    }

    companion object {
        private const val PREFS = "noop_lift_session"

        /** The same key the Swift side uses, so the two files name the same thing. */
        private const val KEY_SNAPSHOT = LiftSessionPersistence.defaultsKey

        fun from(context: Context): LiftSessionStore =
            LiftSessionStore(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}
