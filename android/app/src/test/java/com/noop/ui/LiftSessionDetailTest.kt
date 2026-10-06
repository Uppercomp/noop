package com.noop.ui

import com.noop.R

import com.noop.analytics.LiftMetrics
import com.noop.data.LiftMuscle
import com.noop.data.LiftSetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the session detail screen's arithmetic-as-presentation.
 *
 * The screen makes ONE promise — every figure is arithmetic the reader can redo by hand from the sets
 * listed beside it — and each of these helpers is a place that promise can break silently. The load
 * caption is the one that already did, on the Apple side: showing whole minutes while computing from exact
 * seconds invites the reader to check 8 × 2 against a displayed 21.
 *
 * The maths itself is [LiftMetrics]' and is pinned by its own oracle test; what is pinned HERE is the
 * wording that has to travel with it.
 */
class LiftSessionDetailTest {

    private fun set(
        setIndex: Int = 1,
        weightKg: Double? = 100.0,
        reps: Int? = 5,
        rpe: Double? = null,
        isWarmup: Boolean = false,
    ) = LiftSetEntity(
        id = "x$setIndex", deviceId = "dev", sessionId = "s1", ord = setIndex, exercise = "Squat",
        primaryMuscle = "quads", secondaryMuscles = null, setIndex = setIndex,
        weightKg = weightKg, reps = reps, rpe = rpe, isWarmup = isWarmup,
        startTs = null, endTs = null, restSec = null, note = null,
    )

    private fun summary(exercise: String = "Squat", volumeKg: Double? = 500.0) =
        LiftMetrics.ExerciseSummary(
            exercise = exercise, workingSets = 1, warmupSets = 0, volumeKg = volumeKg,
            bestWeightKg = 100.0, bestReps = 5, bestEstimatedOneRepMaxKg = 112.5,
        )

    // MARK: - The caption the reader checks the number against

    @Test
    fun `the load caption shows the same minutes the load was computed from`() {
        // 2:40 is 2.666… minutes. A caption reading "× 2 min" invites the 8 x 2 = 16 check against a
        // displayed 21, and the app looks like it is making numbers up.
        assertEquals(
            DisplayText.Resource(R.string.l10n_lift_rpe_min_220638b9, listOf("8", "2.67")),
            sessionLoadCaption(rpe = 8.0, durationSec = 160),
        )
        assertEquals(
            DisplayText.Resource(R.string.l10n_lift_rpe_min_220638b9, listOf("7.5", "1")),
            sessionLoadCaption(rpe = 7.5, durationSec = 60),
        )
    }

    @Test
    fun `an unrated session says so rather than showing a zero load`() {
        assertEquals(
            DisplayText.Resource(R.string.l10n_lift_not_rated_58efc7ea),
            sessionLoadCaption(rpe = null, durationSec = 3_600),
        )
    }

    /** And the number the caption explains is the number the metric computes from those same seconds. */
    @Test
    fun `the caption's minutes reproduce the displayed load`() {
        val durationSec = 2_400
        val rpe = 8.0
        val load = LiftMetrics.sessionLoad(rpe, durationSec)!!
        val minutesFromCaption = durationSec / 60.0
        assertEquals("${load.toInt()}", "${(rpe * minutesFromCaption).toInt()}")
    }

    // MARK: - One set as it reads back

    @Test
    fun `a weighted set reads weight times reps`() {
        assertEquals(DisplayText.Dynamic("100 kg × 5"), setValueText(set(), UnitSystem.METRIC))
        assertEquals(DisplayText.Dynamic("12.5 kg × 3"), setValueText(set(weightKg = 12.5, reps = 3), UnitSystem.METRIC))
    }

    @Test
    fun `a pounds user reads pounds`() {
        // 100 kg shown in pounds, converted by the SAME constant the store round-trips through.
        val shown = setValueText(set(), UnitSystem.IMPERIAL) as DisplayText.Dynamic
        assertTrue(shown.value.endsWith("lb × 5"))
    }

    /**
     * Bodyweight work: reps alone, with no fabricated tonnage behind it — and as COPY, because the Apple
     * side localises this one (`String(localized: "\(reps) reps")` reads "%@ Wdh." in German) while it
     * builds "100 kg × 5" with plain interpolation.
     */
    @Test
    fun `a set with no weight reads reps alone`() {
        assertEquals(
            DisplayText.Resource(R.string.l10n_lift_reps_515a3a8f, listOf("12")),
            setValueText(set(weightKg = null, reps = 12), UnitSystem.METRIC),
        )
    }

    @Test
    fun `a set with no rep count still reads as something`() {
        assertEquals(DisplayText.Dynamic("100 kg × —"), setValueText(set(reps = null), UnitSystem.METRIC))
    }

    @Test
    fun `the best set is the one the metric ranked, printed its own way`() {
        assertEquals(DisplayText.Dynamic("100 kg × 5"), bestSetText(summary(), UnitSystem.METRIC))
        assertEquals(
            "a bodyweight exercise reports reps rather than a weight nobody lifted",
            DisplayText.Resource(R.string.l10n_lift_reps_515a3a8f, listOf("12")),
            bestSetText(summary().copy(bestWeightKg = null, bestReps = 12), UnitSystem.METRIC),
        )
        assertEquals(DisplayText.Dynamic("—"), bestSetText(summary().copy(bestReps = null), UnitSystem.METRIC))
    }

    // MARK: - Progression is a comparison

    @Test
    fun `the volume delta compares with last time and names the direction`() {
        // "… vs last time" is plain interpolation on the Apple side and is not in its String Catalog, so
        // it is a literal here; "same as last time" IS localised there, so it is copy.
        assertEquals(
            DisplayText.Dynamic("+50 kg vs last time"),
            volumeDeltaText(summary(volumeKg = 550.0), 500.0, UnitSystem.METRIC),
        )
        assertEquals(
            DisplayText.Dynamic("−50 kg vs last time"),
            volumeDeltaText(summary(volumeKg = 450.0), 500.0, UnitSystem.METRIC),
        )
        assertEquals(
            "a sub-plate difference is called what it is",
            DisplayText.Resource(R.string.l10n_lift_same_as_last_time_ac5840c1),
            volumeDeltaText(summary(volumeKg = 500.2), 500.0, UnitSystem.METRIC),
        )
    }

    @Test
    fun `there is no delta without a previous session to compare against`() {
        assertNull(volumeDeltaText(summary(), null, UnitSystem.METRIC))
        assertNull("a zero previous volume is not a baseline", volumeDeltaText(summary(), 0.0, UnitSystem.METRIC))
        assertNull("and there is nothing to compare when this session has no volume", volumeDeltaText(summary(volumeKg = null), 500.0, UnitSystem.METRIC))
    }

    @Test
    fun `a bigger session is the only thing that reads as progress`() {
        // Green is reserved for MORE than last time: the same volume is not an achievement, and less is
        // not a failure — it is a lighter day, and the screen does not editorialise about it.
        assertEquals(Palette.statusPositive, deltaColor(summary(volumeKg = 550.0), 500.0))
        assertEquals(Palette.textSecondary, deltaColor(summary(volumeKg = 450.0), 500.0))
        assertEquals(Palette.textTertiary, deltaColor(summary(volumeKg = 500.0), 500.0))
        assertEquals(Palette.textTertiary, deltaColor(summary(), null))
    }

    // MARK: - The per-muscle arithmetic, made inspectable

    @Test
    fun `the per-muscle line shows how the total was arrived at`() {
        val counts = LiftMetrics.MuscleCounts(
            fractional = mapOf(LiftMuscle.chest to 3.0),
            direct = mapOf(LiftMuscle.chest to 2),
            indirect = mapOf(LiftMuscle.chest to 2),
        )
        assertEquals(
            "both counts, in the order the resource's specifiers expect them",
            DisplayText.Resource(R.string.l10n_lift_lld_direct_lld_indirect_7b1cc7c4, listOf(2, 2)),
            componentText(counts, LiftMuscle.chest),
        )
        assertEquals(
            DisplayText.Resource(R.string.l10n_lift_lld_direct_741a1af5, listOf(1)),
            componentText(counts.copy(direct = mapOf(LiftMuscle.chest to 1), indirect = emptyMap()), LiftMuscle.chest),
        )
        assertEquals(
            DisplayText.Resource(R.string.l10n_lift_lld_indirect_f24bf95b, listOf(2)),
            componentText(counts.copy(direct = emptyMap(), indirect = mapOf(LiftMuscle.chest to 2)), LiftMuscle.chest),
        )
    }
}
