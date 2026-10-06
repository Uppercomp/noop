package com.noop.data

import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * The Lift-log slice of the DAO (liftExercise / liftProgram / liftProgramItem / liftSession / liftSet,
 * schema v41) — the Android port of the Swift `LiftLogStore` reads/writes in `Packages/WhoopStore`.
 *
 * Split into its own interface exactly as [DeviceRegistryDao] is, and for the same reason: the lift
 * repository depends on a narrow, easily-faked surface and can be unit-tested on the plain JVM without
 * Room/Robolectric. Room flattens inherited annotated methods into the concrete `@Dao` at compile time.
 *
 * THE ENTITIES LANDED WITHOUT THIS. `LiftEntities` shipped the oracle-verified SCHEMA with the
 * migration and deliberately stopped there, so until now nothing on Android read or wrote these five
 * tables. This is the read/write half.
 *
 * WHERE THIS DEPARTS FROM `@Insert(REPLACE)`, AND WHY IT MATTERS. Room's REPLACE upserts on the PRIMARY
 * KEY (the `id`). That is exactly right for `liftProgram` and `liftSet`, whose Swift twins also
 * conflict on `id`, so those two use it.
 *
 * It is WRONG for the other two, and wrong in a way that loses data:
 *  - `liftSession` conflicts on the natural key `(deviceId, startTs, sport)`. A REPLACE that arrived
 *    carrying a fresh `id` would delete the existing row and insert under the new one — orphaning every
 *    `liftSet` row that points at the old `sessionId`, because sets reference the session by id.
 *  - `liftExercise` conflicts on `(deviceId, name)` AND its update is not a plain overwrite: the Swift
 *    version keeps an existing muscle classification when the incoming one is absent (`COALESCE`) and
 *    never lets `lastUsedTs` move backwards (`MAX`). REPLACE would discard both rules.
 *
 * So those two carry explicit `ON CONFLICT ... DO UPDATE` statements instead.
 */
interface LiftLogDao {

    // MARK: - Exercises

    /** Whether this device already knows the exercise by name — half of the vocabulary cap below. */
    @Query("SELECT COUNT(*) FROM liftExercise WHERE deviceId = :deviceId AND name = :name")
    suspend fun liftExerciseNameCount(deviceId: String, name: String): Int

    /** How many exercises this device has remembered, for the cap. */
    @Query("SELECT COUNT(*) FROM liftExercise WHERE deviceId = :deviceId")
    suspend fun liftExerciseCount(deviceId: String): Int

    /**
     * Insert-or-update one exercise by its NATURAL key, keeping the existing row's id.
     *
     * `COALESCE(excluded.x, x)` keeps what is already stored when the incoming value is null, and
     * `MAX(...)` means a re-import can only ever move `lastUsedTs` forward. That is what makes the read
     * below ("most recently used first") mean what it says.
     */
    @Query(
        """
        INSERT INTO liftExercise (id, deviceId, name, primaryMuscle, secondaryMuscles, createdAt, lastUsedTs)
        VALUES (:id, :deviceId, :name, :primaryMuscle, :secondaryMuscles, :createdAt, :lastUsedTs)
        ON CONFLICT(deviceId, name) DO UPDATE SET
            primaryMuscle = COALESCE(excluded.primaryMuscle, primaryMuscle),
            secondaryMuscles = COALESCE(excluded.secondaryMuscles, secondaryMuscles),
            lastUsedTs = MAX(COALESCE(excluded.lastUsedTs, 0), COALESCE(lastUsedTs, 0))
        """,
    )
    suspend fun upsertLiftExercise(
        id: String,
        deviceId: String,
        name: String,
        primaryMuscle: String?,
        secondaryMuscles: String?,
        createdAt: Long,
        lastUsedTs: Long?,
    ): Long

    /**
     * The user's exercises, most recently used first and never-used ones after, alphabetical within.
     * `COALESCE(lastUsedTs, 0)`, not a bare `lastUsedTs DESC`: SQLite sorts NULLs FIRST in a DESC scan,
     * which would put every never-used exercise at the TOP of the picker.
     */
    @Query("SELECT * FROM liftExercise WHERE deviceId = :deviceId ORDER BY COALESCE(lastUsedTs, 0) DESC, name ASC")
    suspend fun liftExercises(deviceId: String): List<LiftExerciseRow>

    /** Drop one exercise by id. Rows already logged keep their own snapshot of the classification. */
    @Query("DELETE FROM liftExercise WHERE id = :id")
    suspend fun deleteLiftExercise(id: String): Int

    // MARK: - Programs

    /** Insert-or-replace a program by its id PK (Swift `ON CONFLICT(id) DO UPDATE`). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLiftProgram(row: LiftProgramRow)

    /**
     * Programs for a device, most recently updated first. [includeArchived] false is the hub's default:
     * a program is archived rather than deleted so history keeps resolving, but it stops being offered.
     */
    @Query(
        """
        SELECT * FROM liftProgram
        WHERE deviceId = :deviceId AND (:includeArchived OR archived = 0)
        ORDER BY updatedAt DESC
        """,
    )
    suspend fun liftPrograms(deviceId: String, includeArchived: Boolean): List<LiftProgramRow>

    /** Drop a program and its lines. Sessions keep `programId`/`programName` as they were. */
    @Query("DELETE FROM liftProgram WHERE id = :id")
    suspend fun deleteLiftProgramRow(id: String): Int

    // MARK: - Program lines

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLiftProgramItems(rows: List<LiftProgramItemRow>)

    /** One program's lines, in plan order — the `ord` the sheet draws. */
    @Query("SELECT * FROM liftProgramItem WHERE programId = :programId ORDER BY ord ASC")
    suspend fun liftProgramItems(programId: String): List<LiftProgramItemRow>

    /** Clear a program's lines; the caller re-inserts, so the whole edit is one transaction. */
    @Query("DELETE FROM liftProgramItem WHERE programId = :programId")
    suspend fun clearLiftProgramItems(programId: String): Int

    // MARK: - Sessions

    /**
     * Insert-or-update one session by its NATURAL key, keeping the existing row's id.
     *
     * See the interface note: REPLACE here would orphan the session's sets. The paired `workout` row is
     * keyed on the same `(deviceId, startTs, sport)` triple, which is why that index is UNIQUE.
     */
    @Query(
        """
        INSERT INTO liftSession (id, deviceId, startTs, endTs, sport, programId, programName, sessionRpe, note)
        VALUES (:id, :deviceId, :startTs, :endTs, :sport, :programId, :programName, :sessionRpe, :note)
        ON CONFLICT(deviceId, startTs, sport) DO UPDATE SET
            endTs = excluded.endTs,
            programId = excluded.programId,
            programName = excluded.programName,
            sessionRpe = excluded.sessionRpe,
            note = excluded.note
        """,
    )
    suspend fun upsertLiftSession(
        id: String,
        deviceId: String,
        startTs: Long,
        endTs: Long?,
        sport: String,
        programId: String?,
        programName: String?,
        sessionRpe: Double?,
        note: String?,
    ): Long

    /** Sessions for a device that started within `[fromTs, toTs]` inclusive, most recent first. */
    @Query(
        """
        SELECT * FROM liftSession
        WHERE deviceId = :deviceId AND startTs >= :fromTs AND startTs <= :toTs
        ORDER BY startTs DESC
        """,
    )
    suspend fun liftSessions(deviceId: String, fromTs: Long, toTs: Long): List<LiftSessionRow>

    @Query("SELECT * FROM liftSession WHERE id = :id")
    suspend fun liftSession(id: String): LiftSessionRow?

    /**
     * The `id` of the row stored under a session's NATURAL key, whatever id the caller last supplied.
     *
     * The upsert above deliberately keeps the id of a row that is already there, so a caller that mints a
     * fresh id for a re-save has no way to know which id its sets must point at. Without this read, every
     * set written by that second save is orphaned: `liftSetsInRange` and `lastLiftSessionIdForExercise`
     * both join on `liftSession.id = liftSet.sessionId`, so the session's sets vanish from the weekly
     * per-muscle card and from "what did I lift last time" while still sitting in the table.
     */
    @Query(
        """
        SELECT id FROM liftSession
        WHERE deviceId = :deviceId AND startTs = :startTs AND sport = :sport
        """,
    )
    suspend fun liftSessionIdFor(deviceId: String, startTs: Long, sport: String): String?

    /** Drop a session and its sets together — a session with no sets is not a state worth having. */
    @Query("DELETE FROM liftSet WHERE sessionId = :sessionId")
    suspend fun deleteLiftSetsForSession(sessionId: String): Int

    @Query("DELETE FROM liftSession WHERE id = :id")
    suspend fun deleteLiftSessionRow(id: String): Int

    // MARK: - Sets

    /** Insert-or-replace sets by their id PK (Swift `ON CONFLICT(id) DO UPDATE`). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLiftSets(rows: List<LiftSetEntity>)

    /** One session's sets in COMPLETION order — which is what `ord` is written from. */
    @Query("SELECT * FROM liftSet WHERE sessionId = :sessionId ORDER BY ord ASC")
    suspend fun liftSets(sessionId: String): List<LiftSetEntity>

    // `deleteLiftSets(ids)` for discarding specific sets is NOT declared here: [DeviceRegistryDao]
    // already carries it, with the same `DELETE FROM liftSet WHERE id IN (:ids)` and the same Swift
    // twin (`WhoopStore.deleteLiftSets`), ported ahead of its consumer for the device-data wipe. A
    // second declaration would only be a place for the two to drift.

    /**
     * The most recent PRIOR session's sets for one exercise, in performed order — the read the whole
     * feature exists for: it pre-fills the next session with what you actually did last time.
     *
     * Two steps rather than a correlated subquery, mirroring the Swift query: find the latest qualifying
     * session, then read its sets. [before] excludes the session currently in progress (pass its
     * `startTs`) so a running session never pre-fills from itself.
     *
     * Sets with ZERO reps were not performed and are skipped, so a discarded set never becomes the next
     * session's grey numbers — and a session holding only such sets is not "last time" for the exercise.
     */
    @Query(
        """
        SELECT s.sessionId FROM liftSet s
        JOIN liftSession sess ON sess.id = s.sessionId
        WHERE s.deviceId = :deviceId AND s.exercise = :exercise AND sess.startTs < :before
          AND (s.reps IS NULL OR s.reps <> 0)
        ORDER BY sess.startTs DESC
        LIMIT 1
        """,
    )
    suspend fun lastLiftSessionIdForExercise(deviceId: String, exercise: String, before: Long): String?

    @Query(
        """
        SELECT * FROM liftSet
        WHERE sessionId = :sessionId AND exercise = :exercise AND (reps IS NULL OR reps <> 0)
        ORDER BY ord ASC
        """,
    )
    suspend fun liftSetsForExerciseInSession(sessionId: String, exercise: String): List<LiftSetEntity>

    /**
     * Every set of every lift session in `[fromTs, toTs]`, for the per-muscle counts.
     *
     * Warm-ups and zero-rep sets are excluded — nothing else. RPE is deliberately NOT filtered on: the
     * reference doses were derived from unfiltered working-set counts, so filtering here would compare a
     * smaller number against a scale built from a larger one.
     */
    @Query(
        """
        SELECT s.* FROM liftSet s
        JOIN liftSession sess ON sess.id = s.sessionId
        WHERE s.deviceId = :deviceId
          AND sess.startTs >= :fromTs AND sess.startTs <= :toTs
          AND s.isWarmup = 0
          AND (s.reps IS NULL OR s.reps <> 0)
        """,
    )
    suspend fun liftSetsInRange(deviceId: String, fromTs: Long, toTs: Long): List<LiftSetEntity>

    companion object {
        /**
         * The most exercises one device remembers. Past this the vocabulary is full and the caller is
         * told rather than silently evicting one — an exercise the user invented should not vanish
         * because they invented another. Twin of `WhoopStore.maxRememberedExercises`.
         */
        const val maxRememberedExercises: Int = 500
    }
}
