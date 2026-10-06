package com.noop.ui

import com.noop.R

import com.noop.data.LiftPlanItem
import com.noop.data.LiftSessionEngine
import com.noop.ingest.LiftingImporter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the session sheet's pure copy decisions and the sport token a logged session is filed under.
 *
 * Small, but each one is a place two facts could disagree: the button label is a rendering of the engine's
 * stage (four states, one label each), and the sport is what puts a typed session in the SAME bucket as an
 * imported one — two spellings of "strength training" would show as two sports in the same day's list.
 */
class LiftSessionUiTest {

    private fun engine(sets: Int, advances: Int): LiftSessionEngine {
        val e = LiftSessionEngine(listOf(LiftPlanItem("Squat", plannedSets = sets)), startTs = 1_000)
        repeat(advances) { e.advance(now = 1_000 + it * 10) }
        return e
    }

    /**
     * The action button's label is a DECISION about the session's state, so the test asserts the decision —
     * the resource it names — rather than the English words. The words live in nine translation files now.
     */
    @Test
    fun `the action button names what the next tap will do`() {
        assertEquals(DisplayText.Resource(R.string.l10n_lift_start_first_set_5f9bbb3b), actionLabel(engine(sets = 2, advances = 0)))
        assertEquals(DisplayText.Resource(R.string.l10n_lift_set_done_d919abfe), actionLabel(engine(sets = 2, advances = 1)))
        assertEquals(DisplayText.Resource(R.string.l10n_lift_start_next_set_c88008ec), actionLabel(engine(sets = 2, advances = 2)))
        assertEquals(
            "a resting session with nothing left is not asked to start another set",
            DisplayText.Resource(R.string.l10n_lift_all_sets_done_615a1502),
            actionLabel(engine(sets = 1, advances = 2)),
        )
    }

    @Test
    fun `the program question is worded for what actually changed`() {
        assertEquals(
            DisplayText.Resource(R.string.l10n_lift_you_added_exercises_and_changed_the_numb_5dff7a6c),
            programQuestion(countsChanged = true, exercisesAdded = true),
        )
        assertEquals(
            DisplayText.Resource(R.string.l10n_lift_you_added_exercises_add_them_to_the_prog_531dc08e),
            programQuestion(countsChanged = false, exercisesAdded = true),
        )
        assertEquals(
            DisplayText.Resource(R.string.l10n_lift_you_changed_the_number_of_sets_keep_the_3b661010),
            programQuestion(countsChanged = true, exercisesAdded = false),
        )
    }

    /** One token, one bucket: the importer's sport and a typed session's must be the same string. */
    @Test
    fun `a logged session is filed under the importer's sport`() {
        assertEquals(LiftingImporter.SPORT, LIFT_SPORT)
    }

    /** The four stage labels are exhaustive over the engine's stages, so a new stage cannot go unnamed. */
    @Test
    fun `every stage has a label`() {
        val labels = listOf(
            engine(sets = 1, advances = 0),
            engine(sets = 1, advances = 1),
            engine(sets = 1, advances = 2),
            engine(sets = 1, advances = 2).also { it.finish(now = 2_000) },
        ).map { actionLabel(it) }
        assertEquals(
            listOf(
                DisplayText.Resource(R.string.l10n_lift_start_first_set_5f9bbb3b),
                DisplayText.Resource(R.string.l10n_lift_set_done_d919abfe),
                DisplayText.Resource(R.string.l10n_lift_all_sets_done_615a1502),
                DisplayText.Resource(R.string.l10n_lift_saving_56a2285c),
            ),
            labels,
        )
    }

    /**
     * The bar and the sheet must agree about what each stage's colour MEANS — green = working, amber =
     * resting, Effort blue = neither. This is the one thing the two surfaces share, and the reason the
     * finished set is not tinted: the set is over, and what is running is the rest after it.
     */
    @Test
    fun `the session bar takes its colour from the stage, not from the set`() {
        assertEquals(Palette.statusPositive, barTint(engine(sets = 2, advances = 1)))
        assertEquals(Palette.metricAmber, barTint(engine(sets = 2, advances = 2)))
        assertEquals(Palette.effortColor, barTint(engine(sets = 2, advances = 0)))
        assertEquals(
            "a finished session is not 'working'",
            Palette.effortColor,
            barTint(engine(sets = 1, advances = 1).also { it.finish(now = 2_000) }),
        )
    }
}
