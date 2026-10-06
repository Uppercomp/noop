package com.noop.ui

import com.noop.data.LiftSetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins [LiftSetEdit] — the one screen in the feature that REWRITES sets that have already been saved.
 *
 * Two rules carry the weight here, and both are invisible in review:
 *
 *  - Only a field whose text CHANGED is parsed back. Re-parsing an untouched pound value into kilograms
 *    and back nudges it by a rounding error, and the log slowly edits itself.
 *  - An added set takes its muscles and its session from the exercise's other sets and gets NO timing:
 *    inventing an end timestamp would give it a heart-rate window it never had.
 */
class LiftSetEditTest {

    private fun set(
        id: String,
        exercise: String = "Squat",
        ord: Int = 0,
        setIndex: Int = 1,
        weightKg: Double? = 100.0,
        reps: Int? = 5,
        rpe: Double? = null,
        isWarmup: Boolean = false,
    ) = LiftSetEntity(
        id = id, deviceId = "dev", sessionId = "s1", ord = ord, exercise = exercise,
        primaryMuscle = "quads", secondaryMuscles = "glutes", setIndex = setIndex,
        weightKg = weightKg, reps = reps, rpe = rpe, isWarmup = isWarmup,
        startTs = 1_000L, endTs = 1_045L, restSec = 60, note = null,
    )

    // MARK: - What the sheet opens with

    @Test
    fun `rows group by exercise in the order they were first performed`() {
        val rows = listOf(
            set("a", exercise = "Bench", ord = 1),
            set("b", exercise = "Squat", ord = 0),
            set("c", exercise = "Bench", ord = 2, setIndex = 2),
        )
        val groups = LiftSetEdit.fill(rows, UnitSystem.METRIC)
        assertEquals(listOf("Squat", "Bench"), groups.map { it.name })
        assertEquals("sets read in set order, not completion order", listOf("a", "c"), groups[1].entries.map { it.id })
    }

    @Test
    fun `a field shows the display unit and a blank for nothing stored`() {
        assertEquals("100", LiftSetEdit.form(set("a"), UnitSystem.METRIC).weight)
        assertEquals("", LiftSetEdit.form(set("a", weightKg = null), UnitSystem.METRIC).weight)
        assertEquals("", LiftSetEdit.form(set("a", reps = null, rpe = null), UnitSystem.METRIC).reps)
        assertEquals("8.5", LiftSetEdit.form(set("a", rpe = 8.5), UnitSystem.METRIC).rpe)
        assertTrue(
            "a pounds user opens the sheet in pounds, rounded once by the same formatter the store round-trips through",
            LiftSetEdit.form(set("a"), UnitSystem.IMPERIAL).weight.startsWith("220.4"),
        )
    }

    // MARK: - Only what changed is parsed back

    @Test
    fun `a field left alone is not re-parsed`() {
        val row = set("a", weightKg = 102.05, reps = 5, rpe = 8.0)
        val before = LiftSetEdit.form(row, UnitSystem.IMPERIAL)
        // The reps changed; the weight did not. The weight must come out of `applying` EXACTLY as it went
        // in, not re-derived from the rounded text the field happened to show.
        val edited = LiftSetEdit.applying(
            after = before.copy(reps = "6"),
            before = before,
            row = row,
            system = UnitSystem.IMPERIAL,
        )
        assertEquals(102.05, edited.weightKg!!, 0.0)
        assertEquals(6, edited.reps)
        assertEquals(8.0, edited.rpe!!, 0.0)
    }

    @Test
    fun `a changed field is parsed, and a blank clears it`() {
        val row = set("a")
        val before = LiftSetEdit.form(row, UnitSystem.METRIC)
        val cleared = LiftSetEdit.applying(before.copy(weight = ""), before, row, UnitSystem.METRIC)
        assertNull(cleared.weightKg)
        val retyped = LiftSetEdit.applying(before.copy(weight = "60.5"), before, row, UnitSystem.METRIC)
        assertEquals(60.5, retyped.weightKg!!, 0.0)
    }

    @Test
    fun `a typed comma is read as a decimal separator, and a rep count stays whole`() {
        val row = set("a")
        val before = LiftSetEdit.form(row, UnitSystem.METRIC)
        assertEquals(62.5, LiftSetEdit.applying(before.copy(weight = "62,5"), before, row, UnitSystem.METRIC).weightKg!!, 0.0)
        assertEquals(6, LiftSetEdit.applying(before.copy(reps = "6"), before, row, UnitSystem.METRIC).reps)
        assertNull(
            "the Swift twin reads reps with Int(text); truncating 4.5 to 4 would store a number nobody typed",
            LiftSetEdit.applying(before.copy(reps = "4.5"), before, row, UnitSystem.METRIC).reps,
        )
    }

    @Test
    fun `the warm-up mark always follows the form`() {
        val row = set("a")
        val before = LiftSetEdit.form(row, UnitSystem.METRIC)
        assertTrue(LiftSetEdit.applying(before.copy(isWarmup = true), before, row, UnitSystem.METRIC).isWarmup)
    }

    // MARK: - What saving writes

    @Test
    fun `an untouched sheet writes nothing`() {
        val rows = listOf(set("a"), set("b", ord = 1, setIndex = 2))
        val groups = LiftSetEdit.fill(rows, UnitSystem.METRIC)
        val change = LiftSetEdit.changes(rows, groups, UnitSystem.METRIC)
        assertTrue("no edits, no upserts", change.upserts.isEmpty())
        assertTrue(change.deletedIds.isEmpty())
    }

    @Test
    fun `removing a set deletes it and renumbers the ones after it`() {
        val rows = listOf(set("a"), set("b", ord = 1, setIndex = 2), set("c", ord = 2, setIndex = 3))
        val groups = LiftSetEdit.fill(rows, UnitSystem.METRIC)
        // Drop the middle row: the sheet only removes the LAST one, so this is the sheet's own list state.
        val edited = listOf(groups[0].copy(entries = listOf(groups[0].entries[0], groups[0].entries[2])))
        val change = LiftSetEdit.changes(rows, edited, UnitSystem.METRIC)
        assertEquals(listOf("b"), change.deletedIds)
        assertEquals("the surviving third set is renumbered to 2", 2, change.upserts.single().setIndex)
    }

    @Test
    fun `an added set takes the exercise's muscles and gets no timing`() {
        val rows = listOf(set("a", ord = 4, setIndex = 1))
        val group = LiftSetEdit.fill(rows, UnitSystem.METRIC).single()
        val added = listOf(
            group.copy(
                entries = group.entries + LiftSetEntry("new", LiftSetForm("60", "8", "", isWarmup = false)),
            ),
        )
        val row = LiftSetEdit.changes(rows, added, UnitSystem.METRIC).upserts.single()

        assertEquals("new", row.id)
        assertEquals("dev", row.deviceId)
        assertEquals("s1", row.sessionId)
        assertEquals("quads", row.primaryMuscle)
        assertEquals("glutes", row.secondaryMuscles)
        assertEquals("ordered after everything the session already had", 5, row.ord)
        assertEquals(2, row.setIndex)
        assertEquals(60.0, row.weightKg!!, 0.0)
        assertEquals(8, row.reps)
        assertNull("inventing a moment would give it a heart-rate window it never had", row.startTs)
        assertNull(row.endTs)
        assertNull(row.restSec)
    }

    @Test
    fun `a field holding zero empties on focus and goes back to zero when it is left`() {
        val rows = listOf(set("a", reps = 0))
        val state = EditRowsState(LiftSetEdit.fill(rows, UnitSystem.METRIC))
        assertEquals("0", state.formOf("a")!!.reps)

        state.onFocus("a", ZeroField.Reps, focused = true)
        assertEquals("typing replaces the 0 rather than appending to it", "", state.formOf("a")!!.reps)

        state.onFocus("a", ZeroField.Reps, focused = false)
        assertEquals("left empty it goes back to 0, because blank reps would count as performed", "0", state.formOf("a")!!.reps)
    }

    @Test
    fun `a zero that was typed over keeps what was typed`() {
        val rows = listOf(set("a", reps = 0))
        val state = EditRowsState(LiftSetEdit.fill(rows, UnitSystem.METRIC))
        state.onFocus("a", ZeroField.Reps, focused = true)
        state.setForm("a", state.formOf("a")!!.copy(reps = "6"))
        state.onFocus("a", ZeroField.Reps, focused = false)
        assertEquals("6", state.formOf("a")!!.reps)
    }
}
