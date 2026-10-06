package com.noop.data

import com.noop.analytics.LiftMetrics
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Pins [LiftLogRepository] against [LiftLogDao]'s documented semantics, with an in-memory fake.
 *
 * The fake is not a stub that records calls: it re-implements the SQL the DAO declares — the natural-key
 * upserts, the `COALESCE`/`MAX` merge on the exercise vocabulary, the `reps <> 0` filter, the
 * `COALESCE(lastUsedTs, 0)` ordering — so the rules the repository is built on are exercised rather than
 * assumed. It is still not the database; the SQL itself is pinned separately by the Room schema oracle
 * and by `WhoopDatabaseUpgradeTest`.
 *
 * The agreement test at the bottom is the important one: [LiftLogRepository.setCounts] and
 * [LiftMetrics.muscleCounts] are two implementations of one number, and the Swift twin pins its pair the
 * same way (`LiftMetricsStoreAgreementTests`).
 */
class LiftLogRepositoryTest {

    // MARK: - Fixtures

    private class FakeDao : LiftLogDao {
        val exercises = mutableListOf<LiftExerciseRow>()
        val programs = mutableListOf<LiftProgramRow>()
        val items = mutableListOf<LiftProgramItemRow>()
        val sessions = mutableListOf<LiftSessionRow>()
        val sets = mutableListOf<LiftSetEntity>()

        override suspend fun liftExerciseNameCount(deviceId: String, name: String): Int =
            exercises.count { it.deviceId == deviceId && it.name == name }

        override suspend fun liftExerciseCount(deviceId: String): Int =
            exercises.count { it.deviceId == deviceId }

        override suspend fun upsertLiftExercise(
            id: String,
            deviceId: String,
            name: String,
            primaryMuscle: String?,
            secondaryMuscles: String?,
            createdAt: Long,
            lastUsedTs: Long?,
        ): Long {
            val at = exercises.indexOfFirst { it.deviceId == deviceId && it.name == name }
            if (at < 0) {
                exercises += LiftExerciseRow(
                    id = id, deviceId = deviceId, name = name, primaryMuscle = primaryMuscle,
                    secondaryMuscles = secondaryMuscles, createdAt = createdAt, lastUsedTs = lastUsedTs,
                )
            } else {
                val old = exercises[at]
                exercises[at] = old.copy(
                    primaryMuscle = primaryMuscle ?: old.primaryMuscle,
                    secondaryMuscles = secondaryMuscles ?: old.secondaryMuscles,
                    lastUsedTs = maxOf(lastUsedTs ?: 0L, old.lastUsedTs ?: 0L),
                )
            }
            return 1
        }

        override suspend fun liftExercises(deviceId: String): List<LiftExerciseRow> =
            exercises.filter { it.deviceId == deviceId }
                .sortedWith(compareByDescending<LiftExerciseRow> { it.lastUsedTs ?: 0L }.thenBy { it.name })

        override suspend fun deleteLiftExercise(id: String): Int =
            if (exercises.removeAll { it.id == id }) 1 else 0

        override suspend fun upsertLiftProgram(row: LiftProgramRow) {
            val at = programs.indexOfFirst { it.id == row.id }
            if (at < 0) programs += row else programs[at] = row
        }

        override suspend fun liftPrograms(deviceId: String, includeArchived: Boolean): List<LiftProgramRow> =
            programs.filter { it.deviceId == deviceId && (includeArchived || !it.archived) }
                .sortedByDescending { it.updatedAt }

        override suspend fun deleteLiftProgramRow(id: String): Int =
            if (programs.removeAll { it.id == id }) 1 else 0

        override suspend fun upsertLiftProgramItems(rows: List<LiftProgramItemRow>) {
            rows.forEach { row ->
                val at = items.indexOfFirst { it.id == row.id }
                if (at < 0) items += row else items[at] = row
            }
        }

        override suspend fun liftProgramItems(programId: String): List<LiftProgramItemRow> =
            items.filter { it.programId == programId }.sortedBy { it.ord }

        override suspend fun clearLiftProgramItems(programId: String): Int {
            val before = items.size
            items.removeAll { it.programId == programId }
            return before - items.size
        }

        override suspend fun upsertLiftSession(
            id: String,
            deviceId: String,
            startTs: Long,
            endTs: Long?,
            sport: String,
            programId: String?,
            programName: String?,
            sessionRpe: Double?,
            note: String?,
        ): Long {
            // ON CONFLICT(deviceId, startTs, sport) DO UPDATE — the id that was already stored WINS.
            val at = sessions.indexOfFirst {
                it.deviceId == deviceId && it.startTs == startTs && it.sport == sport
            }
            if (at < 0) {
                sessions += LiftSessionRow(
                    id = id, deviceId = deviceId, startTs = startTs, endTs = endTs, sport = sport,
                    programId = programId, programName = programName, sessionRpe = sessionRpe, note = note,
                )
            } else {
                val old = sessions[at]
                sessions[at] = old.copy(
                    endTs = endTs, programId = programId, programName = programName,
                    sessionRpe = sessionRpe, note = note,
                )
            }
            return 1
        }

        override suspend fun liftSessions(deviceId: String, fromTs: Long, toTs: Long): List<LiftSessionRow> =
            sessions.filter { it.deviceId == deviceId && it.startTs >= fromTs && it.startTs <= toTs }
                .sortedByDescending { it.startTs }

        override suspend fun liftSession(id: String): LiftSessionRow? = sessions.firstOrNull { it.id == id }

        override suspend fun liftSessionIdFor(deviceId: String, startTs: Long, sport: String): String? =
            sessions.firstOrNull {
                it.deviceId == deviceId && it.startTs == startTs && it.sport == sport
            }?.id

        override suspend fun deleteLiftSetsForSession(sessionId: String): Int {
            val before = sets.size
            sets.removeAll { it.sessionId == sessionId }
            return before - sets.size
        }

        override suspend fun deleteLiftSessionRow(id: String): Int =
            if (sessions.removeAll { it.id == id }) 1 else 0

        override suspend fun upsertLiftSets(rows: List<LiftSetEntity>) {
            rows.forEach { row ->
                val at = sets.indexOfFirst { it.id == row.id }
                if (at < 0) sets += row else sets[at] = row
            }
        }

        override suspend fun liftSets(sessionId: String): List<LiftSetEntity> =
            sets.filter { it.sessionId == sessionId }.sortedBy { it.ord }

        override suspend fun lastLiftSessionIdForExercise(
            deviceId: String,
            exercise: String,
            before: Long,
        ): String? = sets
            .filter { it.deviceId == deviceId && it.exercise == exercise && LiftMetrics.isPerformed(it.reps) }
            .mapNotNull { s -> sessions.firstOrNull { it.id == s.sessionId && it.startTs < before } }
            .maxByOrNull { it.startTs }
            ?.id

        override suspend fun liftSetsForExerciseInSession(
            sessionId: String,
            exercise: String,
        ): List<LiftSetEntity> = sets
            .filter {
                it.sessionId == sessionId && it.exercise == exercise && LiftMetrics.isPerformed(it.reps)
            }
            .sortedBy { it.ord }

        override suspend fun liftSetsInRange(
            deviceId: String,
            fromTs: Long,
            toTs: Long,
        ): List<LiftSetEntity> {
            val inRange = sessions
                .filter { it.deviceId == deviceId && it.startTs >= fromTs && it.startTs <= toTs }
                .map { it.id }
                .toSet()
            return sets.filter {
                it.deviceId == deviceId && it.sessionId in inRange && !it.isWarmup &&
                    LiftMetrics.isPerformed(it.reps)
            }
        }
    }

    private val dao = FakeDao()
    private val deletedSetIds = mutableListOf<List<String>>()
    private val repo = LiftLogRepository(
        dao = dao,
        transactor = object : LiftLogRepository.Transactor {
            override suspend fun <R> run(block: suspend () -> R): R = block()
        },
        deleteSetsById = { ids -> deletedSetIds += ids },
    )

    private fun exercise(
        id: String = "e1",
        name: String = "Squat",
        primary: LiftMuscle? = LiftMuscle.quads,
        secondaries: List<LiftMuscle> = listOf(LiftMuscle.glutes, LiftMuscle.quads),
        lastUsedTs: Long? = 1_000L,
    ) = liftExerciseEntity(
        id = id, deviceId = DEVICE, name = name, primaryMuscle = primary,
        secondaryMuscles = secondaries, createdAt = 500L, lastUsedTs = lastUsedTs,
    )

    private fun sessionRow(
        id: String = "s1",
        startTs: Long = 10_000,
        programName: String? = "Upper A",
    ) = LiftSessionRow(
        id = id, deviceId = DEVICE, startTs = startTs, endTs = startTs + 3_600, sport = "strength",
        programId = null, programName = programName, sessionRpe = null, note = null,
    )

    private fun setRow(
        id: String,
        sessionId: String = "s1",
        ord: Int = 0,
        exercise: String = "Squat",
        primary: LiftMuscle? = LiftMuscle.quads,
        secondaries: List<LiftMuscle> = emptyList(),
        setIndex: Int = 1,
        weightKg: Double? = 100.0,
        reps: Int? = 5,
        isWarmup: Boolean = false,
    ) = liftSetEntity(
        id = id, deviceId = DEVICE, sessionId = sessionId, ord = ord, exercise = exercise,
        primaryMuscle = primary, secondaryMuscles = secondaries, setIndex = setIndex,
        weightKg = weightKg, reps = reps, rpe = null, isWarmup = isWarmup,
        startTs = 10_000L + ord, endTs = 10_045L + ord, restSec = null,
    )

    // MARK: - The vocabulary

    @Test
    fun `remembering an exercise stores canonical muscle tokens`() = runBlocking {
        repo.rememberExercises(listOf(exercise()))
        val stored = dao.exercises.single()
        assertEquals("quads", stored.primaryMuscle)
        assertEquals("the primary is stripped from the secondaries", "glutes", stored.secondaryMuscles)
    }

    @Test
    fun `the same name updates in place rather than duplicating`() = runBlocking {
        repo.rememberExercises(listOf(exercise(id = "e1", lastUsedTs = 1_000)))
        repo.rememberExercises(listOf(exercise(id = "e2", name = "Squat", lastUsedTs = 2_000L)))

        assertEquals(1, dao.exercises.size)
        assertEquals("the first row keeps its id", "e1", dao.exercises.single().id)
        assertEquals("and recency moved forward", 2_000L, dao.exercises.single().lastUsedTs)
    }

    /** Merely USING an exercise must never erase the classification the user gave it. */
    @Test
    fun `an absent muscle classification does not overwrite a stored one`() = runBlocking {
        repo.rememberExercises(listOf(exercise()))
        repo.rememberExercises(
            listOf(exercise(id = "e9", primary = null, secondaries = emptyList(), lastUsedTs = 3_000L)),
        )
        assertEquals("quads", dao.exercises.single().primaryMuscle)
        assertEquals("glutes", dao.exercises.single().secondaryMuscles)
    }

    /** A re-import can only ever move recency forward, or "most recently used first" means nothing. */
    @Test
    fun `recency never moves backwards`() = runBlocking {
        repo.rememberExercises(listOf(exercise(lastUsedTs = 5_000L)))
        repo.rememberExercises(listOf(exercise(id = "e2", lastUsedTs = 1_000L)))
        assertEquals(5_000L, dao.exercises.single().lastUsedTs)
    }

    @Test
    fun `exercises come back most recently used first, never-used ones after and alphabetical`() =
        runBlocking {
            repo.rememberExercises(
                listOf(
                    exercise(id = "a", name = "Zercher", lastUsedTs = null),
                    exercise(id = "b", name = "Bench", lastUsedTs = 2_000),
                    exercise(id = "c", name = "Deadlift", lastUsedTs = 3_000),
                    exercise(id = "d", name = "Arnold press", lastUsedTs = null),
                ),
            )
            assertEquals(
                listOf("Deadlift", "Bench", "Arnold press", "Zercher"),
                repo.exercises(DEVICE).map { it.name },
            )
        }

    @Test
    fun `a new name past the cap is refused, but an existing name still updates`() = runBlocking {
        val full = (0 until LiftLogDao.maxRememberedExercises).map {
            exercise(id = "e$it", name = "Exercise $it", lastUsedTs = 1_000L + it)
        }
        repo.rememberExercises(full)

        try {
            repo.rememberExercises(listOf(exercise(id = "new", name = "Chest supported row3")))
            fail("a genuinely new name past the cap must be refused, not silently dropped")
        } catch (e: LiftVocabularyFullException) {
            assertEquals(LiftLogDao.maxRememberedExercises, e.limit)
        }
        assertEquals(LiftLogDao.maxRememberedExercises, dao.exercises.size)

        val corrected = liftExerciseEntity(
            id = "ignored", deviceId = DEVICE, name = "Exercise 0",
            primaryMuscle = LiftMuscle.chest, createdAt = 1L, lastUsedTs = 99_000L,
        )
        repo.rememberExercises(listOf(corrected))
        assertEquals(
            "a user at the cap can still correct the classification of an exercise they use",
            "chest",
            dao.exercises.first { it.name == "Exercise 0" }.primaryMuscle,
        )
    }

    @Test
    fun `forgetting an exercise leaves the sets logged under that name alone`() = runBlocking {
        repo.rememberExercises(listOf(exercise()))
        repo.upsertSessions(listOf(sessionRow()))
        repo.upsertSets(listOf(setRow("x1")))

        assertTrue(repo.deleteExercise("e1"))
        assertFalse(repo.deleteExercise("e1"))
        assertTrue(dao.exercises.isEmpty())
        assertEquals("history keeps its own copy of the name and classification", 1, dao.sets.size)
        assertNotNull(dao.sets.single().primaryMuscle)
    }

    // MARK: - The row factories

    @Test
    fun `an exercise with no secondaries stores NULL rather than an empty string`() {
        val row = liftExerciseEntity(
            id = "e", deviceId = DEVICE, name = "Row", primaryMuscle = LiftMuscle.lats,
            secondaryMuscles = emptyList(), createdAt = 1L,
        )
        assertNull("two spellings of \"none\" is a bug waiting", row.secondaryMuscles)
        assertEquals(LiftMuscle.lats, row.primary)
        assertTrue(row.secondaries.isEmpty())
    }

    @Test
    fun `duplicate secondaries are collapsed and the primary is dropped`() {
        val row = liftExerciseEntity(
            id = "e", deviceId = DEVICE, name = "Row", primaryMuscle = LiftMuscle.lats,
            secondaryMuscles = listOf(
                LiftMuscle.biceps, LiftMuscle.lats, LiftMuscle.biceps, LiftMuscle.forearms,
            ),
            createdAt = 1L,
        )
        assertEquals(listOf(LiftMuscle.biceps, LiftMuscle.forearms), row.secondaries)
    }

    @Test
    fun `an unknown stored token is skipped rather than failing the read`() {
        val row = LiftExerciseRow(
            id = "e", deviceId = DEVICE, name = "Row", primaryMuscle = "spleen",
            secondaryMuscles = "quads,wings", createdAt = 1L,
        )
        assertNull(row.primary)
        assertEquals(listOf(LiftMuscle.quads), row.secondaries)
    }

    @Test
    fun `volume is weight times reps, null for a warm-up and null when either number is missing`() {
        assertEquals(500.0, setRow("x", weightKg = 100.0, reps = 5).volumeKg!!, 0.0)
        assertNull(setRow("x", weightKg = 100.0, reps = 5, isWarmup = true).volumeKg)
        assertNull(setRow("x", weightKg = null, reps = 5).volumeKg)
        assertNull(setRow("x", weightKg = 100.0, reps = null).volumeKg)
    }

    // MARK: - Programs

    @Test
    fun `programs list most recently touched first and archived ones are hidden unless asked for`() =
        runBlocking {
            repo.upsertPrograms(
                listOf(
                    LiftProgramRow("p1", DEVICE, "Upper A", null, 1L, 100L, archived = false),
                    LiftProgramRow("p2", DEVICE, "Lower A", null, 1L, 200L, archived = false),
                    LiftProgramRow("p3", DEVICE, "Old split", null, 1L, 300L, archived = true),
                ),
            )
            assertEquals(listOf("p2", "p1"), repo.programs(DEVICE).map { it.id })
            assertEquals(listOf("p3", "p2", "p1"), repo.programs(DEVICE, includeArchived = true).map { it.id })
        }

    @Test
    fun `replacing a program's lines swaps the whole list in plan order`() = runBlocking {
        val item = { id: String, ord: Int ->
            LiftProgramItemRow(
                id = id, deviceId = DEVICE, programId = "p1", ord = ord, exercise = "Squat",
                targetSets = 3, targetRepsLow = 5, targetRepsHigh = null, targetRpe = null,
                targetWeightKg = 100.0, restSec = 120, note = null,
            )
        }
        repo.replaceProgramItems("p1", listOf(item("i1", 0), item("i2", 1)))
        assertEquals(listOf("i1", "i2"), repo.programItems("p1").map { it.id })

        repo.replaceProgramItems("p1", listOf(item("i3", 0)))
        assertEquals("the editor hands back the whole list", listOf("i3"), repo.programItems("p1").map { it.id })
    }

    @Test
    fun `deleting a program takes its lines with it`() = runBlocking {
        repo.upsertPrograms(listOf(LiftProgramRow("p1", DEVICE, "Upper A", null, 1L, 100L, archived = false)))
        repo.replaceProgramItems(
            "p1",
            listOf(
                LiftProgramItemRow(
                    id = "i1", deviceId = DEVICE, programId = "p1", ord = 0, exercise = "Squat",
                    targetSets = 3, targetRepsLow = null, targetRepsHigh = null, targetRpe = null,
                    targetWeightKg = null, restSec = null, note = null,
                ),
            ),
        )
        assertTrue(repo.deleteProgram("p1"))
        assertFalse(repo.deleteProgram("p1"))
        assertTrue(dao.items.isEmpty())
    }

    // MARK: - Sessions and sets

    /** The natural key is (deviceId, startTs, sport), so a fresh id must NOT create a second row. */
    @Test
    fun `re-saving a session with a new id updates the row that is already there`() = runBlocking {
        repo.upsertSessions(listOf(sessionRow(id = "s1")))
        repo.upsertSessions(listOf(sessionRow(id = "different-id").copy(endTs = 20_000L)))

        assertEquals(1, dao.sessions.size)
        assertEquals("the id the sets already point at survives", "s1", dao.sessions.single().id)
        assertEquals(20_000L, dao.sessions.single().endTs)
    }

    @Test
    fun `a session's sets come back in the order they were performed`() = runBlocking {
        repo.upsertSessions(listOf(sessionRow()))
        repo.upsertSets(listOf(setRow("x2", ord = 1, setIndex = 1), setRow("x1", ord = 0, setIndex = 2)))
        assertEquals(listOf("x1", "x2"), repo.sets("s1").map { it.id })
    }

    @Test
    fun `replacing a session's sets drops the removed ones and keeps the survivors' ids`() = runBlocking {
        repo.upsertSessions(listOf(sessionRow()))
        repo.upsertSets(listOf(setRow("x1", ord = 0), setRow("x2", ord = 1), setRow("x3", ord = 2)))

        repo.replaceSets("s1", listOf(setRow("x1", ord = 0), setRow("x3", ord = 1)))
        assertEquals(listOf("x1", "x3"), repo.sets("s1").map { it.id })
        assertEquals("the reordered survivor keeps its id", 1, repo.sets("s1").last().ord)
    }

    @Test
    fun `deleting individual sets goes through the injected seam`() = runBlocking {
        repo.deleteSets(listOf("x1", "x2"))
        assertEquals(listOf(listOf("x1", "x2")), deletedSetIds)
        repo.deleteSets(emptyList())
        assertEquals("nothing to delete is not a write", 1, deletedSetIds.size)
    }

    /**
     * The defect a device walk found: the natural-key upsert KEEPS the id of the row already stored, so a
     * second save of the same session (a resumed session, or Save pressed twice) minted a fresh id for its
     * sets. They were written, but against an id no session row had — and both JOIN reads (the weekly
     * per-muscle card, "what did I lift last time") went blind to them.
     */
    @Test
    fun `a re-saved session's sets stay attached to the row that is already stored`() = runBlocking {
        repo.upsertSessions(listOf(sessionRow(id = "first-save")))
        repo.upsertSets(listOf(setRow("x1", sessionId = "first-save")))

        repo.upsertSessions(listOf(sessionRow(id = "second-save").copy(endTs = 20_000L)))
        val storedId = repo.sessionIdFor(DEVICE, 10_000L, "strength")

        assertEquals("the row that is there keeps its id", "first-save", storedId)
        repo.replaceSets(storedId!!, listOf(setRow("x1", sessionId = storedId)))
        assertEquals(listOf("x1"), repo.sets("first-save").map { it.id })
        assertEquals(
            "and the weekly read, which joins on that id, can see them",
            1,
            dao.liftSetsInRange(DEVICE, 0L, 20_000L).size,
        )
    }

    @Test
    fun `deleting a session takes its sets with it`() = runBlocking {
        repo.upsertSessions(listOf(sessionRow()))
        repo.upsertSets(listOf(setRow("x1"), setRow("x2", ord = 1)))
        assertTrue(repo.deleteSession("s1"))
        assertTrue(dao.sets.isEmpty())
        assertFalse(repo.deleteSession("s1"))
    }

    // MARK: - Last time

    @Test
    fun `last time is the most recent PRIOR session's performed sets`() = runBlocking {
        repo.upsertSessions(
            listOf(sessionRow(id = "old", startTs = 1_000), sessionRow(id = "new", startTs = 2_000)),
        )
        repo.upsertSets(
            listOf(
                setRow("a1", sessionId = "old", weightKg = 90.0, reps = 8),
                setRow("b1", sessionId = "new", weightKg = 100.0, reps = 5),
                setRow("b2", sessionId = "new", ord = 1, setIndex = 2, weightKg = 100.0, reps = 5),
            ),
        )
        val last = repo.lastSets(DEVICE, "Squat")
        assertEquals(listOf("b1", "b2"), last.map { it.id })
    }

    /** A discarded set (zero reps) was never performed, so it must not become the grey numbers. */
    @Test
    fun `a zero-rep set is not last time, and a session holding only those is skipped`() = runBlocking {
        repo.upsertSessions(
            listOf(sessionRow(id = "old", startTs = 1_000), sessionRow(id = "new", startTs = 2_000)),
        )
        repo.upsertSets(
            listOf(
                setRow("a1", sessionId = "old", weightKg = 90.0, reps = 8),
                setRow("b1", sessionId = "new", weightKg = 100.0, reps = 0),
            ),
        )
        assertEquals(listOf("a1"), repo.lastSets(DEVICE, "Squat").map { it.id })
    }

    /** `before` excludes the session in progress, so a running session never pre-fills from itself. */
    @Test
    fun `a running session does not pre-fill from itself`() = runBlocking {
        repo.upsertSessions(
            listOf(sessionRow(id = "old", startTs = 1_000), sessionRow(id = "running", startTs = 2_000)),
        )
        repo.upsertSets(
            listOf(
                setRow("a1", sessionId = "old", weightKg = 90.0, reps = 8),
                setRow("b1", sessionId = "running", weightKg = 100.0, reps = 5),
            ),
        )
        assertEquals(listOf("a1"), repo.lastSets(DEVICE, "Squat", before = 2_000).map { it.id })
        assertEquals("with no cutoff the running session is the latest", listOf("b1"), repo.lastSets(DEVICE, "Squat").map { it.id })
    }

    @Test
    fun `an exercise never logged has no last time`() = runBlocking {
        repo.upsertSessions(listOf(sessionRow()))
        repo.upsertSets(listOf(setRow("x1")))
        assertTrue(repo.lastSets(DEVICE, "Front squat").isEmpty())
    }

    @Test
    fun `sessions in a window come back most recent first`() = runBlocking {
        repo.upsertSessions(
            listOf(
                sessionRow(id = "a", startTs = 1_000),
                sessionRow(id = "b", startTs = 2_000),
                sessionRow(id = "c", startTs = 3_000),
            ),
        )
        assertEquals(listOf("b", "a"), repo.sessions(DEVICE, 1_000, 2_000).map { it.id })
        assertEquals("c", repo.session("c")!!.id)
    }

    // MARK: - The two implementations must agree

    /**
     * [LiftLogRepository.setCounts] reads the database; [LiftMetrics.muscleCounts] reads a list a screen
     * already has. Two implementations of one number, and the Swift twin pins its pair the same way. The
     * case that motivated the guard: a row whose stored secondary list STILL names its primary — which
     * the factories above prevent, but which the aggregation must survive, or the hub's weekly card and
     * the session detail would report different numbers for the same set.
     */
    @Test
    fun `per-muscle counts from the store agree with the in-memory aggregation`() = runBlocking {
        repo.upsertSessions(listOf(sessionRow(id = "s1", startTs = 2_000)))
        repo.upsertSets(
            listOf(
                setRow("a", primary = LiftMuscle.chest, secondaries = listOf(LiftMuscle.triceps)),
                setRow(
                    "b", ord = 1, setIndex = 2, primary = LiftMuscle.chest,
                    secondaries = listOf(LiftMuscle.triceps, LiftMuscle.frontDelts),
                ),
                setRow("c", ord = 2, setIndex = 3, primary = LiftMuscle.quads, isWarmup = true),
                setRow("d", ord = 3, setIndex = 4, primary = LiftMuscle.quads, reps = 0),
                // A hand-written row that repeats the primary in the secondaries, as the guard defends
                // against. Written straight to the fake rather than through the factory.
                LiftSetEntity(
                    id = "e", deviceId = DEVICE, sessionId = "s1", ord = 4, exercise = "Squat",
                    primaryMuscle = "chest", secondaryMuscles = "chest,triceps", setIndex = 5,
                    weightKg = 100.0, reps = 5, rpe = null, isWarmup = false,
                    startTs = 2_000L, endTs = 2_045L, restSec = null, note = null,
                ),
            ),
        )

        val fromStore = repo.setCounts(DEVICE, 2_000, 2_000)
        val inMemory = LiftMetrics.muscleCounts(dao.sets.map { it.toMetricsRow() })

        assertEquals(inMemory.direct, fromStore.direct)
        assertEquals(inMemory.indirect, fromStore.indirect)
        assertEquals(inMemory.fractional, fromStore.fractional)
        assertEquals("chest as a primary AND a secondary is credited once, as direct", 3, fromStore.direct[LiftMuscle.chest])
        assertEquals(3, fromStore.indirect[LiftMuscle.triceps])
        assertEquals(1, fromStore.indirect[LiftMuscle.frontDelts])
        assertNull("a warm-up and an unperformed set claim no muscle", fromStore.direct[LiftMuscle.quads])
        assertEquals(3.0, fromStore.fractional[LiftMuscle.chest]!!, 1e-9)
    }

    /** Only sessions that STARTED inside the window count, warm-ups and unperformed sets aside. */
    @Test
    fun `counts are scoped to the sessions that started in the window`() = runBlocking {
        repo.upsertSessions(
            listOf(sessionRow(id = "in", startTs = 2_000), sessionRow(id = "out", startTs = 9_000)),
        )
        repo.upsertSets(
            listOf(
                setRow("a", sessionId = "in", primary = LiftMuscle.chest),
                setRow("b", sessionId = "out", primary = LiftMuscle.quads),
            ),
        )
        val counts = repo.setCounts(DEVICE, 1_000, 3_000)
        assertEquals(mapOf(LiftMuscle.chest to 1), counts.direct)
        assertTrue(counts.indirect.isEmpty())
    }
}

private const val DEVICE = "my-whoop"
