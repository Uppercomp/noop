package com.noop.data

import androidx.room.withTransaction
import com.noop.analytics.LiftMetrics

/**
 * The in-app strength log's read/write façade — the Android port of the Swift `WhoopStore` lift
 * extension (`Packages/WhoopStore/Sources/WhoopStore/LiftLogStore.swift`).
 *
 * It exists between the DAO and the screens for three reasons, and each one is a rule that has to hold
 * in ONE place rather than at every call site:
 *
 *  - **Normalisation.** The muscle vocabulary is stored as raw tokens. Swift gets its invariant — a
 *    secondary list never repeats a muscle and never contains the primary — for free from
 *    `LiftSetRow.init` / `LiftExerciseRow.init`, which run their secondaries through
 *    `decodeList(encodeList(_:excluding:))` before storing them. A Room entity has no such hook, so the
 *    factory functions here ([liftSetEntity], [liftExerciseEntity]) are where it happens. Two writers
 *    encoding that column by hand is exactly how the same set ends up counted twice for one muscle.
 *  - **The vocabulary cap.** A cap exists because the vocabulary is typo-accumulating by design: every
 *    misspelling becomes a permanent picker entry ("Chest supported row3"). Refusing a genuinely NEW
 *    name past the cap is a read-then-write decision, so it cannot live in the DAO.
 *  - **Atomicity.** Replacing a program's lines, and saving a session with its sets, are each a
 *    delete-then-insert pair that must not be observable half-done.
 *
 * The transaction boundary is injected as a [Transactor] (defaulting to Room's `db.withTransaction`) so
 * every rule above is exercisable on the plain JVM without a Room runtime — the [DeviceRegistry] idiom,
 * and what keeps this suite Robolectric-free.
 */
class LiftLogRepository(
    private val dao: LiftLogDao,
    private val transactor: Transactor,
    /**
     * Deleting individual sets by id, as editing a finished session does when a set is removed.
     *
     * Injected rather than declared here: the one statement lives on [DeviceRegistryDao] beside the
     * `delete*For` family the device-data wipe is guarded over, and a second copy would only be a place
     * for the two to drift.
     */
    private val deleteSetsById: suspend (List<String>) -> Unit,
) {

    /** A single-transaction boundary. Production wraps Room's `withTransaction`; tests pass through.
     *  Not a `fun interface` — a SAM method may not be generic — so implementors use the object form. */
    interface Transactor {
        suspend fun <R> run(block: suspend () -> R): R
    }

    /** Production constructor: wraps the DAO + Room transaction over [db]. */
    constructor(db: WhoopDatabase) : this(
        dao = db.whoopDao(),
        transactor = object : Transactor {
            override suspend fun <R> run(block: suspend () -> R): R = db.withTransaction { block() }
        },
        deleteSetsById = { ids -> db.whoopDao().deleteLiftSets(ids) },
    )

    // MARK: - Exercises (the user's own vocabulary)

    /**
     * Remember exercises, keyed on the natural key `(deviceId, name)` so recording the same name twice
     * updates its classification and recency instead of duplicating it.
     *
     * A muscle field is only overwritten when the caller supplies one, so merely USING an exercise never
     * erases the classification the user set for it — the `COALESCE` in the DAO's upsert.
     *
     * An EXISTING name always updates, cap or no cap: only genuinely new names are refused, or a user at
     * the cap could no longer correct the classification of the exercise they use every week.
     *
     * @throws LiftVocabularyFullException when a new name would push the vocabulary past the cap.
     * @return how many rows were written.
     */
    suspend fun rememberExercises(rows: List<LiftExerciseRow>): Int {
        if (rows.isEmpty()) return 0
        return transactor.run {
            var written = 0
            for (r in rows) {
                if (dao.liftExerciseNameCount(r.deviceId, r.name) == 0 &&
                    dao.liftExerciseCount(r.deviceId) >= LiftLogDao.maxRememberedExercises
                ) {
                    throw LiftVocabularyFullException(LiftLogDao.maxRememberedExercises)
                }
                dao.upsertLiftExercise(
                    id = r.id,
                    deviceId = r.deviceId,
                    name = r.name,
                    primaryMuscle = r.primaryMuscle,
                    secondaryMuscles = r.secondaryMuscles,
                    createdAt = r.createdAt,
                    lastUsedTs = r.lastUsedTs,
                )
                written++
            }
            written
        }
    }

    /**
     * The user's exercises, most recently used first and never-used ones after, alphabetical within
     * each. This is the picker's list — built entirely from what they have typed.
     */
    suspend fun exercises(deviceId: String): List<LiftExerciseRow> = dao.liftExercises(deviceId)

    /**
     * Forget one exercise from the vocabulary. Sets already logged under that name are untouched —
     * they carry their own copy of the name and muscle group, so history never loses meaning.
     */
    suspend fun deleteExercise(id: String): Boolean = dao.deleteLiftExercise(id) > 0

    // MARK: - Programs

    /** Upsert programs by `id`. Returns rows written. */
    suspend fun upsertPrograms(rows: List<LiftProgramRow>): Int {
        if (rows.isEmpty()) return 0
        return transactor.run {
            rows.forEach { dao.upsertLiftProgram(it) }
            rows.size
        }
    }

    /** Programs for a device, most recently touched first. [includeArchived] false is the hub's default. */
    suspend fun programs(deviceId: String, includeArchived: Boolean = false): List<LiftProgramRow> =
        dao.liftPrograms(deviceId, includeArchived)

    /**
     * Delete a program and its item lines. Sessions already run from it are NOT touched — they carry
     * their own `programName` snapshot, so history survives the program's deletion.
     */
    suspend fun deleteProgram(id: String): Boolean = transactor.run {
        dao.clearLiftProgramItems(id)
        dao.deleteLiftProgramRow(id) > 0
    }

    /**
     * Replace a program's item lines wholesale. The editor hands back the whole list, and deleting then
     * reinserting inside one transaction avoids reconciling removals and reorders row by row.
     */
    suspend fun replaceProgramItems(programId: String, items: List<LiftProgramItemRow>): Int =
        transactor.run {
            dao.clearLiftProgramItems(programId)
            if (items.isNotEmpty()) dao.upsertLiftProgramItems(items)
            items.size
        }

    /** A program's exercise lines in plan order. */
    suspend fun programItems(programId: String): List<LiftProgramItemRow> = dao.liftProgramItems(programId)

    // MARK: - Sessions

    /**
     * Upsert sessions, keyed on the NATURAL key `(deviceId, startTs, sport)` rather than the `id` PK, so
     * re-saving the same session updates it in place even if the caller minted a fresh id — which is what
     * keeps this row paired 1:1 with its `workout` row.
     */
    suspend fun upsertSessions(rows: List<LiftSessionRow>): Int {
        if (rows.isEmpty()) return 0
        return transactor.run {
            rows.forEach {
                dao.upsertLiftSession(
                    id = it.id, deviceId = it.deviceId, startTs = it.startTs, endTs = it.endTs,
                    sport = it.sport, programId = it.programId, programName = it.programName,
                    sessionRpe = it.sessionRpe, note = it.note,
                )
            }
            rows.size
        }
    }

    /** Sessions for a device that started within `[fromTs, toTs]` inclusive, most recent first. */
    suspend fun sessions(deviceId: String, fromTs: Long, toTs: Long): List<LiftSessionRow> =
        dao.liftSessions(deviceId, fromTs, toTs)

    /** One session by id, or null. */
    suspend fun session(id: String): LiftSessionRow? = dao.liftSession(id)

    /**
     * The id the session with this natural key is actually stored under.
     *
     * Needed by any caller that WRITES SETS: `upsertSessions` keeps the id of a row already stored under
     * `(deviceId, startTs, sport)`, so a re-save that minted a fresh id would write its sets against an id
     * no session row has — and every JOIN read (the weekly per-muscle card, "what did I lift last time")
     * would silently stop seeing them.
     */
    suspend fun sessionIdFor(deviceId: String, startTs: Long, sport: String): String? =
        dao.liftSessionIdFor(deviceId, startTs, sport)

    /** Delete a session and every set in it. Returns true if a session row was removed. */
    suspend fun deleteSession(id: String): Boolean = transactor.run {
        dao.deleteLiftSetsForSession(id)
        dao.deleteLiftSessionRow(id) > 0
    }

    // MARK: - Sets

    /**
     * Upsert sets by `id`. Called as each set is logged, so a session in progress is durable set by set
     * rather than only on finish.
     */
    suspend fun upsertSets(rows: List<LiftSetEntity>): Int {
        if (rows.isEmpty()) return 0
        return transactor.run {
            dao.upsertLiftSets(rows)
            rows.size
        }
    }

    /**
     * Replace a session's sets wholesale. The editor hands back the whole list — the ones that changed,
     * the ones that were added and the survivors — and the operation is a delete-then-insert in ONE
     * transaction, so no reader ever sees the session with half its sets.
     *
     * Surviving sets keep the ids they arrived with, so nothing downstream that refers to a set by id
     * has to be re-found. This is the same shape as [replaceProgramItems], and it exists rather than a
     * per-row upsert+delete pair because a set the user REMOVED and a set they ADDED arrive in the same
     * edit, and reconciling those two by hand is the mistake worth designing out.
     */
    suspend fun replaceSets(sessionId: String, sets: List<LiftSetEntity>): Int = transactor.run {
        dao.deleteLiftSetsForSession(sessionId)
        if (sets.isNotEmpty()) dao.upsertLiftSets(sets)
        sets.size
    }

    /** Every set in a session, in the order they were performed. */
    suspend fun sets(sessionId: String): List<LiftSetEntity> = dao.liftSets(sessionId)

    /** Delete individual sets by id, as editing a finished session does when a set is removed. */
    suspend fun deleteSets(ids: List<String>) {
        if (ids.isEmpty()) return
        deleteSetsById(ids)
    }

    /**
     * The sets from the most recent session that contained [exercise], in performed order.
     *
     * This is the read the whole feature exists for: it pre-fills the next session with what you actually
     * did last time, which the user then confirms or overrides. Empty when the exercise has never been
     * logged. [before] excludes the session currently in progress (pass its `startTs`) so a running
     * session never pre-fills from itself. Sets with zero reps were not performed
     * ([LiftMetrics.isPerformed]) and are skipped by the DAO, so a discarded set never becomes the next
     * session's grey numbers, and a session holding only such sets is not "last time" for the exercise.
     */
    suspend fun lastSets(deviceId: String, exercise: String, before: Long? = null): List<LiftSetEntity> {
        val cutoff = before ?: Long.MAX_VALUE
        val sessionId = dao.lastLiftSessionIdForExercise(deviceId, exercise, cutoff) ?: return emptyList()
        return dao.liftSetsForExerciseInSession(sessionId, exercise)
    }

    /**
     * Per-muscle working-set counts over `[fromTs, toTs]`, from the classification each set was logged
     * under.
     *
     * `fractional` is the headline figure: direct sets count 1, indirect sets count 0.5 — see
     * [LiftMetrics.muscleCounts] for why that particular split is not a house convention. `direct` and
     * `indirect` are returned alongside so the arithmetic is inspectable rather than asserted.
     *
     * THIS IS A SECOND IMPLEMENTATION of that aggregation, over rows read from the database rather than
     * from a caller's list, and the Swift twin has the same duplication for the same reason (the hub's
     * weekly card reads the store; the session detail reads its in-memory list). Two implementations of
     * one number have to be pinned against each other — `LiftLogRepositoryTest` does exactly that, and
     * the Swift `LiftMetricsStoreAgreementTests` is the precedent.
     */
    suspend fun setCounts(deviceId: String, fromTs: Long, toTs: Long): LiftMetrics.MuscleCounts {
        val rows = dao.liftSetsInRange(deviceId, fromTs, toTs)
        val direct = HashMap<LiftMuscle, Int>()
        val indirect = HashMap<LiftMuscle, Int>()
        for (row in rows) {
            val primary = LiftMuscle.fromRaw(row.primaryMuscle)
            if (primary != null) direct[primary] = (direct[primary] ?: 0) + 1
            // A muscle listed BOTH as primary and as secondary is credited once, as direct.
            // `LiftMuscle.encodeList(secondaries, primary)` already strips the primary on the way in, so
            // today no stored row needs this — but "today no row needs it" is not a guarantee, and
            // without the guard this aggregation and `LiftMetrics.muscleCounts` (which has always had it)
            // would report DIFFERENT numbers for the same set, with nothing to catch it.
            for (m in LiftMuscle.decodeList(row.secondaryMuscles)) {
                if (m == primary) continue
                indirect[m] = (indirect[m] ?: 0) + 1
            }
        }
        val fractional = LinkedHashMap<LiftMuscle, Double>()
        direct.forEach { (m, n) -> fractional[m] = (fractional[m] ?: 0.0) + n * LiftMuscle.directSetCredit }
        indirect.forEach { (m, n) -> fractional[m] = (fractional[m] ?: 0.0) + n * LiftMuscle.indirectSetCredit }
        return LiftMetrics.MuscleCounts(
            fractional = fractional,
            direct = direct,
            indirect = indirect,
        )
    }

    companion object {
        /**
         * How long a PROGRAM note may be.
         *
         * 120 because that is what can actually be SEEN: the hub renders it under the program name at
         * three lines in caption type, in a column narrowed by the Start button — roughly three lines on
         * a phone. A longer note is not stored-and-shown, it is stored-and-silently-truncated, and typing
         * into a field that quietly discards the end is worse than a field that stops.
         */
        const val maxProgramNoteLength = 120

        /**
         * How long an EXERCISE (technique) note may be.
         *
         * 200, about four lines. Longer than a program note because it is a cue you read BETWEEN sets —
         * "slow eccentric, pause at the bottom, don't let the elbows flare" — and shorter than unlimited
         * because it renders directly above the set rows and every line pushes them down the screen. Both
         * are a MAXIMUM, not a target — a one-line note is usually the better note.
         */
        const val maxExerciseNoteLength = 200
    }
}

/** Thrown when the vocabulary is full and the name is a new one. Twin of `LiftExerciseVocabularyFull`. */
class LiftVocabularyFullException(val limit: Int) :
    Exception("This device already remembers $limit exercises; that is the cap.")

// MARK: - Row factories
//
// The normalisation hook a Room entity does not have (see the class header). Every writer of the
// `secondaryMuscles` columns goes through one of these, so the stored list is canonical by construction
// — it never repeats a muscle and never contains the primary.

/**
 * One exercise for the vocabulary. Twin of the Swift `LiftExerciseRow.init`, which runs the secondaries
 * through `decodeList(encodeList(_:excluding:))` before storing them.
 *
 * [name] is kept EXACTLY as the user typed it: the natural key is `(deviceId, name)`, so case and spacing
 * are preserved rather than normalised. Their vocabulary, shown back verbatim.
 */
fun liftExerciseEntity(
    id: String,
    deviceId: String,
    name: String,
    primaryMuscle: LiftMuscle?,
    secondaryMuscles: List<LiftMuscle> = emptyList(),
    createdAt: Long,
    lastUsedTs: Long? = null,
): LiftExerciseRow = LiftExerciseRow(
    id = id,
    deviceId = deviceId,
    name = name,
    primaryMuscle = primaryMuscle?.name,
    secondaryMuscles = LiftMuscle.encodeList(secondaryMuscles, primaryMuscle),
    createdAt = createdAt,
    lastUsedTs = lastUsedTs,
)

/**
 * One set as performed. Twin of the Swift `LiftSetRow.init`.
 *
 * [primaryMuscle] / [secondaryMuscles] are snapshotted AS THEY WERE at log time — the caller passes the
 * classification the vocabulary held when the set was logged — so reclassifying an exercise later never
 * silently rewrites what past weeks were counted as.
 */
fun liftSetEntity(
    id: String,
    deviceId: String,
    sessionId: String,
    ord: Int,
    exercise: String,
    primaryMuscle: LiftMuscle?,
    secondaryMuscles: List<LiftMuscle> = emptyList(),
    setIndex: Int,
    weightKg: Double?,
    reps: Int?,
    rpe: Double?,
    isWarmup: Boolean,
    startTs: Long?,
    endTs: Long?,
    restSec: Int?,
    note: String? = null,
): LiftSetEntity = LiftSetEntity(
    id = id,
    deviceId = deviceId,
    sessionId = sessionId,
    ord = ord,
    exercise = exercise,
    primaryMuscle = primaryMuscle?.name,
    secondaryMuscles = LiftMuscle.encodeList(secondaryMuscles, primaryMuscle),
    setIndex = setIndex,
    weightKg = weightKg,
    reps = reps,
    rpe = rpe,
    isWarmup = isWarmup,
    startTs = startTs,
    endTs = endTs,
    restSec = restSec,
    note = note,
)

/** The primary muscle a stored row was classified under, or null when it was never classified. */
val LiftExerciseRow.primary: LiftMuscle? get() = LiftMuscle.fromRaw(primaryMuscle)

/** The also-worked muscles a stored row was classified under. Unknown tokens are skipped. */
val LiftExerciseRow.secondaries: List<LiftMuscle> get() = LiftMuscle.decodeList(secondaryMuscles)

/** The primary muscle a stored set was counted under, or null when it was never classified. */
val LiftSetEntity.primary: LiftMuscle? get() = LiftMuscle.fromRaw(primaryMuscle)

/** The also-worked muscles a stored set was counted under. Unknown tokens are skipped. */
val LiftSetEntity.secondaries: List<LiftMuscle> get() = LiftMuscle.decodeList(secondaryMuscles)

/**
 * Volume for this set: weight x reps, in kilograms. Null unless BOTH are present, and zero for a warm-up
 * — a transparent arithmetic figure, never a physiological claim. Twin of `LiftSetRow.volumeKg`.
 */
val LiftSetEntity.volumeKg: Double?
    get() {
        if (isWarmup) return null
        val w = weightKg ?: return null
        val r = reps ?: return null
        return w * r
    }

/**
 * The set as [LiftMetrics] sees it. The muscle tokens are decoded HERE, so a caller cannot hand the
 * analytics an unnormalised secondary list — [LiftMetrics.Row] normalises again on its own construction,
 * which is the guarantee it documents.
 */
fun LiftSetEntity.toMetricsRow(): LiftMetrics.Row = LiftMetrics.Row(
    ord = ord,
    exercise = exercise,
    isWarmup = isWarmup,
    weightKg = weightKg,
    reps = reps,
    rpe = rpe,
    primaryMuscle = primary,
    secondaryMuscles = secondaries,
)
