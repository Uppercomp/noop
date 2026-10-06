package com.noop.ui

import com.noop.R
import com.noop.data.LiftExerciseRow
import com.noop.data.LiftMuscle
import com.noop.data.LiftProgramItemRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the program editor's PURE half — the parse rules that decide what gets stored, and the summary
 * line a program row shows.
 *
 * These are worth pinning rather than eyeballing because every one of them decides a stored value: a
 * rep count read as 4 from "4.5" is a number the user never typed, and an RPE ceiling clamped from 11 to
 * 10 is a target they did not set. The composable above them is a rendering of these results.
 *
 * NO SWIFT TOOLCHAIN was available where this was ported, so these assert the behaviour the Swift source
 * states (the `LiftProgramItemSheet` computed properties and its `save()`), not byte-identical stdout
 * from an oracle. Weaker, and named as such.
 */
class LiftProgramEditorTest {

    private fun item(
        exercise: String = "Squat",
        targetSets: Int? = null,
        targetRepsLow: Int? = null,
        targetRpe: Double? = null,
        targetWeightKg: Double? = null,
        restSec: Int? = null,
        note: String? = null,
    ) = LiftProgramItemRow(
        id = "i1", deviceId = "dev", programId = "p1", ord = 0, exercise = exercise,
        targetSets = targetSets, targetRepsLow = targetRepsLow, targetRepsHigh = null,
        targetRpe = targetRpe, targetWeightKg = targetWeightKg, restSec = restSec, note = note,
    )

    private fun exerciseRow(
        id: String = "e1",
        name: String = "Squat",
        primary: LiftMuscle? = LiftMuscle.quads,
        secondaries: String? = "glutes",
    ) = LiftExerciseRow(
        id = id, deviceId = "dev", name = name,
        primaryMuscle = primary?.name, secondaryMuscles = secondaries,
        createdAt = 1L, lastUsedTs = null,
    )

    // MARK: - The RPE ceiling

    @Test
    fun `the ceiling is the typed number when it is one to ten`() {
        assertEquals(8.0, LiftLineTargets.maxRpe("8")!!, 0.0)
        assertEquals(8.5, LiftLineTargets.maxRpe("8.5")!!, 0.0)
        assertEquals("a comma decimal separator is what most shipped locales type", 7.5, LiftLineTargets.maxRpe("7,5")!!, 0.0)
        assertEquals(1.0, LiftLineTargets.maxRpe("1")!!, 0.0)
        assertEquals(10.0, LiftLineTargets.maxRpe("10")!!, 0.0)
    }

    @Test
    fun `an out-of-range or unparseable ceiling is nil rather than clamped`() {
        assertNull("11 must not become 10: that is a target the user did not set", LiftLineTargets.maxRpe("11"))
        assertNull(LiftLineTargets.maxRpe("0"))
        assertNull(LiftLineTargets.maxRpe("10.5"))
        assertNull(LiftLineTargets.maxRpe("-3"))
        assertNull(LiftLineTargets.maxRpe("hard"))
        assertNull("a blank field is no ceiling", LiftLineTargets.maxRpe(""))
        assertNull(LiftLineTargets.maxRpe("   "))
    }

    @Test
    fun `the field only complains about something that was actually typed`() {
        assertFalse(LiftLineTargets.maxRpeInvalid(""))
        assertFalse(LiftLineTargets.maxRpeInvalid("  "))
        assertFalse(LiftLineTargets.maxRpeInvalid("8"))
        assertTrue(LiftLineTargets.maxRpeInvalid("11"))
        assertTrue(LiftLineTargets.maxRpeInvalid("hard"))
    }

    // MARK: - Whole-number targets

    @Test
    fun `sets, reps and rest are integers or nothing`() {
        assertEquals(4, LiftLineTargets.whole("4"))
        assertEquals(120, LiftLineTargets.whole(" 120 "))
        assertNull(LiftLineTargets.whole(""))
        assertNull(LiftLineTargets.whole("abc"))
        assertNull("the Swift twin reads these with Int(text), which refuses a decimal", LiftLineTargets.whole("4.5"))
        assertNull(LiftLineTargets.whole("4,5"))
    }

    // MARK: - Weight is typed in the display unit and stored in kilograms

    @Test
    fun `a typed weight is converted to kilograms, and a metric one is not moved`() {
        assertEquals(100.0, LiftLineTargets.weightKg("100", UnitSystem.METRIC)!!, 0.0)
        assertEquals(102.5, LiftLineTargets.weightKg("102.5", UnitSystem.METRIC)!!, 0.0)
        assertNull(LiftLineTargets.weightKg("", UnitSystem.METRIC))
        assertNull(LiftLineTargets.weightKg("heavy", UnitSystem.METRIC))

        val kg = LiftLineTargets.weightKg("225", UnitSystem.IMPERIAL)!!
        assertEquals(225.0 / UnitFormatter.POUNDS_PER_KILOGRAM, kg, 1e-9)
        assertEquals("and it renders back as what was typed", 225.0, LiftFormat.display(kg, UnitSystem.IMPERIAL), 1e-6)
    }

    // MARK: - Save gating

    @Test
    fun `a line needs a name and a valid ceiling`() {
        assertFalse(LiftLineTargets.canSave("", ""))
        assertFalse(LiftLineTargets.canSave("   ", ""))
        assertTrue(LiftLineTargets.canSave("Squat", ""))
        assertTrue(LiftLineTargets.canSave(" Squat ", "8"))
        assertFalse("an invalid ceiling blocks the save rather than being dropped", LiftLineTargets.canSave("Squat", "11"))
    }

    // MARK: - The summary line

    @Test
    fun `the summary lists only the parts that were filled in`() {
        // The parts, not one String: three of them are copy on the Apple side and resolving a resource
        // needs the Application, which a JVM test does not have (`DisplayText`). Asserting the parts pins
        // WHICH sentence each one is, which is the decision; the words live in `strings.xml`.
        assertEquals(
            listOf(
                DisplayText.Dynamic("4 × 8"),
                DisplayText.Dynamic("60 kg"),
                DisplayText.Resource(R.string.l10n_lift_max_rpe_ef20676d, listOf("8")),
                DisplayText.Resource(R.string.l10n_lift_rest_1bd51a33, listOf("2:00")),
            ),
            liftProgramTargetSummary(
                item(targetSets = 4, targetRepsLow = 8, targetWeightKg = 60.0, targetRpe = 8.0, restSec = 120),
                UnitSystem.METRIC,
            ),
        )
        assertEquals(
            listOf(DisplayText.Dynamic("4 × 8")),
            liftProgramTargetSummary(item(targetSets = 4, targetRepsLow = 8), UnitSystem.METRIC),
        )
        assertEquals(
            listOf(DisplayText.Resource(R.string.l10n_lift_lld_sets_703048fd, listOf(4))),
            liftProgramTargetSummary(item(targetSets = 4), UnitSystem.METRIC),
        )
        assertEquals(
            listOf(DisplayText.Resource(R.string.l10n_lift_rest_1bd51a33, listOf("2:00"))),
            liftProgramTargetSummary(item(restSec = 120), UnitSystem.METRIC),
        )
        assertEquals(
            listOf(DisplayText.Resource(R.string.l10n_lift_rest_1bd51a33, listOf("45s"))),
            liftProgramTargetSummary(item(restSec = 45), UnitSystem.METRIC),
        )
        assertEquals(
            listOf(DisplayText.Resource(R.string.l10n_lift_no_targets_set_e8d35c12)),
            liftProgramTargetSummary(item(), UnitSystem.METRIC),
        )
    }

    @Test
    fun `the summary renders the weight in the unit the user reads`() {
        val summary = liftProgramTargetSummary(item(targetWeightKg = 100.0), UnitSystem.IMPERIAL)
        assertEquals(listOf(DisplayText.Dynamic("220.46 lb")), summary)
        assertTrue(
            "a pounds user must not be shown kilograms",
            (summary.single() as DisplayText.Dynamic).value.endsWith("lb"),
        )
    }

    // MARK: - The exercise vocabulary

    @Test
    fun `suggestions match what has been typed and never the exact name`() {
        val vocabulary = listOf(
            exerciseRow(id = "a", name = "Bench press"),
            exerciseRow(id = "b", name = "Incline bench"),
            exerciseRow(id = "c", name = "Squat"),
        )
        assertEquals(listOf("Bench press", "Incline bench"), LiftExerciseVocabulary.suggestions(vocabulary, "bench").map { it.name })
        assertEquals(
            "the thing already in the box is not a suggestion",
            emptyList<String>(),
            LiftExerciseVocabulary.suggestions(vocabulary, "Squat").map { it.name },
        )
        assertEquals(
            "a match is case-insensitive",
            listOf("Bench press", "Incline bench"),
            LiftExerciseVocabulary.suggestions(vocabulary, "BEN").map { it.name },
        )
    }

    @Test
    fun `an empty box offers the most recently used names, capped`() {
        val vocabulary = (0 until 10).map { exerciseRow(id = "e$it", name = "Exercise $it") }
        assertEquals(6, LiftExerciseVocabulary.suggestions(vocabulary, "").size)
        assertEquals("Exercise 0", LiftExerciseVocabulary.suggestions(vocabulary, "").first().name)
        assertEquals(listOf("Exercise 0"), LiftExerciseVocabulary.suggestions(vocabulary, "", limit = 1).map { it.name })
    }

    @Test
    fun `secondaries are stored in the vocabulary's canonical order, without the primary`() {
        val picked = setOf(LiftMuscle.triceps, LiftMuscle.chest, LiftMuscle.frontDelts)
        assertEquals(
            "declaration order, not the set's iteration order, so a re-save does not reshuffle the blob",
            listOf(LiftMuscle.frontDelts, LiftMuscle.triceps),
            LiftExerciseVocabulary.ordered(picked, excluding = LiftMuscle.chest),
        )
        assertEquals(
            listOf(LiftMuscle.chest, LiftMuscle.frontDelts, LiftMuscle.triceps),
            LiftExerciseVocabulary.ordered(picked, excluding = null),
        )
    }
}
