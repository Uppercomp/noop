package com.noop.ui

import com.noop.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.noop.data.LiftSessionEngine
import com.noop.data.LiftSessionRow
import com.noop.data.LiftSetEntity
import kotlinx.coroutines.launch
import java.util.UUID

// Correct a finished session: a number missed or mistyped at the gym, a set added or removed, a warm-up not
// marked, or the session's RPE. Only this session changes — never the program it ran from — and every
// figure on the session screen is recomputed from these rows.
//
// Sets saved at 0 reps (discarded at finish, or skipped) appear here and nowhere else, so a discard made by
// mistake can be filled back in: give such a set its reps and it counts again.
//
// Fields hold plain text and are parsed once, on Save, so a field is never rewritten while it is being
// typed into (the bug that stored 45.5 kg as 455). Only a field whose text CHANGED is written back:
// re-parsing an untouched pound value into kilograms would otherwise nudge it by a rounding error.
//
// Kotlin twin of `Strand/Screens/LiftSessionEditSheet.swift`.

/** One set's fields, as typed. */
data class LiftSetForm(
    val weight: String,
    val reps: String,
    val rpe: String,
    val isWarmup: Boolean,
)

/** One row on the sheet: a saved set (its row id) or one added here (a new id). */
data class LiftSetEntry(val id: String, val form: LiftSetForm)

/** An exercise and its rows, in sheet order. */
data class LiftExerciseRows(val name: String, val entries: List<LiftSetEntry>)

/** What saving writes. */
data class LiftSetChanges(val upserts: List<LiftSetEntity>, val deletedIds: List<String>)

/**
 * The edit sheet's pure half — the part that decides what gets STORED.
 *
 * Pulled out of the composable so it is unit-testable without a device, which matters more here than
 * anywhere else in the feature: this is the one screen that rewrites sets that have already been saved.
 */
object LiftSetEdit {

    /** A set's fields as the sheet opens, in the unit the app displays. */
    fun form(row: LiftSetEntity, system: UnitSystem): LiftSetForm = LiftSetForm(
        weight = row.weightKg?.let { LiftFormat.trim(LiftFormat.display(fromKilograms = it, system = system)) } ?: "",
        reps = row.reps?.toString() ?: "",
        rpe = row.rpe?.let { LiftFormat.trim(it) } ?: "",
        isWarmup = row.isWarmup,
    )

    /**
     * The rows as the sheet groups them: exercises in the order they were first performed, each with its
     * sets in set order.
     */
    fun fill(sets: List<LiftSetEntity>, system: UnitSystem): List<LiftExerciseRows> {
        val order = LinkedHashSet<String>()
        for (row in sets.sortedBy { it.ord }) order.add(row.exercise)
        return order.map { name ->
            LiftExerciseRows(
                name = name,
                entries = sets
                    .filter { it.exercise == name }
                    .sortedWith(compareBy({ it.setIndex }, { it.ord }))
                    .map { LiftSetEntry(it.id, form(it, system)) },
            )
        }
    }

    /** [row] with every field that DIFFERS between [before] and [after] parsed back in. A blank clears. */
    fun applying(after: LiftSetForm, before: LiftSetForm, row: LiftSetEntity, system: UnitSystem): LiftSetEntity {
        var edited = row
        if (after.weight != before.weight) edited = edited.copy(weightKg = kilograms(after.weight, system))
        if (after.reps != before.reps) edited = edited.copy(reps = repCount(after.reps))
        if (after.rpe != before.rpe) edited = edited.copy(rpe = LiftFormat.number(after.rpe))
        return edited.copy(isWarmup = after.isWarmup)
    }

    /**
     * What saving writes: every row that changed or was added, and the ids of rows no longer on the sheet.
     *
     * Rows are numbered 1… per exercise in sheet order, so removing a set renumbers the ones after it. A
     * saved row has only its EDITED fields parsed back in; an added row takes its muscles from the
     * exercise's other sets, has no timing (there is no moment to record, and inventing one would give it a
     * heart-rate window it never had), and is ordered after every set the session already had.
     */
    fun changes(
        rows: List<LiftSetEntity>,
        exercises: List<LiftExerciseRows>,
        system: UnitSystem,
    ): LiftSetChanges {
        val byId = rows.associateBy { it.id }
        var nextOrd = (rows.maxOfOrNull { it.ord } ?: -1) + 1
        val upserts = ArrayList<LiftSetEntity>()
        val kept = HashSet<String>()
        for (group in exercises) {
            val template = rows.firstOrNull { it.exercise == group.name } ?: continue
            group.entries.forEachIndexed { position, entry ->
                val row = byId[entry.id]
                if (row != null) {
                    kept.add(row.id)
                    val edited = applying(entry.form, form(row, system), row, system)
                        .copy(setIndex = position + 1)
                    if (edited != row) upserts.add(edited)
                } else {
                    upserts.add(
                        LiftSetEntity(
                            id = entry.id,
                            deviceId = template.deviceId,
                            sessionId = template.sessionId,
                            ord = nextOrd,
                            exercise = group.name,
                            primaryMuscle = template.primaryMuscle,
                            secondaryMuscles = template.secondaryMuscles,
                            setIndex = position + 1,
                            weightKg = kilograms(entry.form.weight, system),
                            reps = repCount(entry.form.reps),
                            rpe = LiftFormat.number(entry.form.rpe),
                            isWarmup = entry.form.isWarmup,
                            startTs = null,
                            endTs = null,
                            restSec = null,
                            note = null,
                        ),
                    )
                    nextOrd++
                }
            }
        }
        return LiftSetChanges(upserts, rows.map { it.id }.filter { it !in kept })
    }

    /** A typed weight in the user's display unit, in the kilograms that get stored. */
    fun kilograms(text: String, system: UnitSystem): Double? =
        LiftFormat.number(text)?.let { LiftFormat.kilograms(fromDisplay = it, system = system) }

    /**
     * A rep count, or null for blank — and deliberately NOT [LiftFormat.number], which accepts a decimal:
     * `Int(text)` is what the Swift twin reads, and truncating "4.5" to 4 would store a number nobody typed.
     */
    fun repCount(text: String): Int? = text.trim().toIntOrNull()
}

/** Which of a row's two zero-able fields was emptied on focus. Internal so the rule can be pinned. */
internal enum class ZeroField { Weight, Reps }

/**
 * The sheet's editable state: the rows, and the one field (if any) whose leading 0 was emptied when it was
 * focused.
 *
 * A field holding 0 — a set discarded at finish — empties on focus, so typing replaces the 0 instead of
 * appending to it ("0" then "60" read "600" in the simulator). Left empty it goes back to 0 before anything
 * reads the fields, because an empty reps field saves as nil, which counts as a PERFORMED set.
 */
internal class EditRowsState(initial: List<LiftExerciseRows>) {
    val groups = mutableStateListOf<LiftExerciseRows>().apply { addAll(initial) }
    var cleared by mutableStateOf<Pair<String, ZeroField>?>(null)
        private set

    fun formOf(id: String): LiftSetForm? = groups.firstNotNullOfOrNull { g -> g.entries.firstOrNull { it.id == id }?.form }

    fun setForm(id: String, form: LiftSetForm) {
        for (gi in groups.indices) {
            val at = groups[gi].entries.indexOfFirst { it.id == id }
            if (at >= 0) {
                groups[gi] = groups[gi].copy(
                    entries = groups[gi].entries.toMutableList().also { it[at] = LiftSetEntry(id, form) },
                )
                return
            }
        }
    }

    /** Put back a 0 that was emptied on focus and left empty. */
    fun restoreZero() {
        val (id, kind) = cleared ?: return
        cleared = null
        val form = formOf(id) ?: return
        val current = if (kind == ZeroField.Weight) form.weight else form.reps
        if (current.isEmpty()) {
            setForm(id, if (kind == ZeroField.Weight) form.copy(weight = "0") else form.copy(reps = "0"))
        }
    }

    /** A field's focus changed: restore the previous zero, then empty a fresh one. */
    fun onFocus(id: String, kind: ZeroField, focused: Boolean) {
        restoreZero()
        if (!focused) return
        val form = formOf(id) ?: return
        val value = if (kind == ZeroField.Weight) form.weight else form.reps
        if (value == "0") {
            cleared = id to kind
            setForm(id, if (kind == ZeroField.Weight) form.copy(weight = "") else form.copy(reps = ""))
        }
    }

    fun addSet(index: Int) {
        restoreZero()
        val entries = groups[index].entries
        val last = entries.lastOrNull() ?: return
        if (entries.size >= LiftSessionEngine.maxSetsPerExercise) return
        // A new set starts from the exercise's last one — usually what the extra set was — without its RPE.
        groups[index] = groups[index].copy(
            entries = entries + LiftSetEntry(
                id = UUID.randomUUID().toString(),
                form = LiftSetForm(last.form.weight, last.form.reps, "", isWarmup = false),
            ),
        )
    }

    fun removeSet(index: Int) {
        val entries = groups[index].entries
        if (entries.size > 1) groups[index] = groups[index].copy(entries = entries.dropLast(1))
    }
}

@Composable
fun LiftSessionEditSheet(
    vm: AppViewModel,
    session: LiftSessionRow,
    system: UnitSystem,
    onSaved: () -> Unit,
    onClose: () -> Unit,
) {
    val repo = vm.liftLog
    val scope = rememberCoroutineScope()

    val state = remember { EditRowsState(emptyList()) }
    var original by remember { mutableStateOf<List<LiftExerciseRows>>(emptyList()) }
    var sessionRpeText by remember { mutableStateOf("") }
    var originalSessionRpe by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(session.id) {
        val rows = repo.sets(session.id)
        val grouped = LiftSetEdit.fill(rows, system)
        state.groups.clear()
        state.groups.addAll(grouped)
        original = grouped
        sessionRpeText = session.sessionRpe?.let { LiftFormat.trim(it) } ?: ""
        originalSessionRpe = sessionRpeText
    }

    val hasChanges = state.groups.toList() != original || sessionRpeText != originalSessionRpe
    val focus = LocalFocusManager.current

    ScreenScaffold(
        title = uiString(R.string.l10n_lift_edit_sets_5ef5fa0f),
        subtitle = uiString(R.string.l10n_lift_fix_numbers_or_add_and_remove_sets_sets_3d94b9f9),
        leading = { SheetTextLink(uiString(R.string.l10n_components_close_bbfa773e), onClick = onClose) },
    ) {
        Column(
            verticalArrangement = Arrangement.spacedBy(Metrics.sectionGap),
            // iOS's `.dismissesKeyboardOnTap`: a tap on the sheet's background puts the keyboard away,
            // which is also what releases a field's zero-clearing. Without it the IME stays up and covers
            // the footer — and BACK would dismiss the whole sheet, losing the edits.
            modifier = Modifier.pointerInput(Unit) { detectTapGestures { focus.clearFocus() } },
        ) {
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    Overline(uiString(R.string.l10n_lift_how_hard_was_the_whole_session_1_10_a0035188))
                    EditField(
                        value = sessionRpeText,
                        keyboard = KeyboardType.Decimal,
                        onValueChange = { sessionRpeText = it.replace(',', '.') },
                        onFocusChange = { focused -> if (!focused) state.restoreZero() },
                    )
                }
            }

            state.groups.forEachIndexed { index, group ->
                ExerciseEditCard(
                    group = group,
                    system = system,
                    onFormChange = { id, form -> state.setForm(id, form) },
                    onAddSet = { state.addSet(index) },
                    onRemoveSet = { state.removeSet(index) },
                    onFieldFocus = { id, kind, focused -> state.onFocus(id, kind, focused) },
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                SheetTextLink(uiString(R.string.ground_truth_cancel), onClick = onClose)
                Spacer(Modifier.weight(1f))
                NoopButton(
                    text = uiString(R.string.l10n_noop_button_save_changes_179359b3),
                    kind = NoopButtonKind.Primary,
                    enabled = !saving && hasChanges,
                    modifier = Modifier.width(180.dp),
                    onClick = {
                        saving = true
                        // The zero that was emptied on focus and left empty goes back before anything reads
                        // these fields.
                        state.restoreZero()
                        scope.launch {
                            saveEdits(vm, session, state.groups.toList(), sessionRpeText, originalSessionRpe, system)
                            saving = false
                            onSaved()
                            onClose()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ExerciseEditCard(
    group: LiftExerciseRows,
    system: UnitSystem,
    onFormChange: (String, LiftSetForm) -> Unit,
    onAddSet: () -> Unit,
    onRemoveSet: () -> Unit,
    onFieldFocus: (String, ZeroField, Boolean) -> Unit,
) {
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
            Text(group.name, style = NoopType.headline, color = Palette.textPrimary)

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.width(EditSetColumnWidth), contentAlignment = Alignment.Center) { Overline(uiString(R.string.l10n_lift_set_448ab73b)) }
                Overline(if (system == UnitSystem.IMPERIAL) uiString(R.string.l10n_lift_lb_75ebff0d) else uiString(R.string.l10n_lift_kg_aee5f633), modifier = Modifier.weight(1f))
                Overline(uiString(R.string.l10n_lift_reps_702045f3), modifier = Modifier.weight(1f))
                Overline(uiString(R.string.l10n_lift_rpe_b845d311), modifier = Modifier.weight(1f))
            }

            group.entries.forEachIndexed { position, entry ->
                val warmup = entry.form.isWarmup
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 6.dp),
                ) {
                    // The set number toggles a warm-up, as on the session sheet. Numbers follow the rows, so
                    // removing a set renumbers the ones after it.
                    Box(
                        modifier = Modifier
                            .width(EditSetColumnWidth)
                            .clickable { onFormChange(entry.id, entry.form.copy(isWarmup = !warmup)) }
                            .semantics {
                                contentDescription = if (warmup) {
                                    uiString(R.string.l10n_lift_warm_up_set_tap_to_make_it_a_working_set_420a0b59)
                                } else {
                                    uiString(
                                        R.string.l10n_lift_set_lld_tap_to_mark_it_a_warm_up_25988360,
                                        position + 1,
                                    )
                                }
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (warmup) uiString(R.string.l10n_lift_w_e2415cb7) else "${position + 1}",
                            style = NoopType.captionNumber,
                            color = if (warmup) Palette.metricAmber else Palette.textSecondary,
                        )
                    }

                    EditField(
                        value = entry.form.weight,
                        keyboard = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f),
                        onValueChange = { onFormChange(entry.id, entry.form.copy(weight = it.replace(',', '.'))) },
                        onFocusChange = { onFieldFocus(entry.id, ZeroField.Weight, it) },
                    )
                    EditField(
                        value = entry.form.reps,
                        keyboard = KeyboardType.Number,
                        modifier = Modifier.weight(1f),
                        onValueChange = { onFormChange(entry.id, entry.form.copy(reps = it)) },
                        onFocusChange = { onFieldFocus(entry.id, ZeroField.Reps, it) },
                    )
                    EditField(
                        value = entry.form.rpe,
                        keyboard = KeyboardType.Decimal,
                        modifier = Modifier.weight(1f),
                        onValueChange = { onFormChange(entry.id, entry.form.copy(rpe = it.replace(',', '.'))) },
                        onFocusChange = { },
                    )
                }
            }

            // Add a set at the end of an exercise, or drop its last one — the same control as the session
            // sheet. An exercise keeps at least one row: set it to 0 reps to take it out of the figures.
            val canAdd = group.entries.size < LiftSessionEngine.maxSetsPerExercise
            val canRemove = group.entries.size > 1
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 2.dp),
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .weight(1f)
                        .clickable(enabled = canAdd, onClick = onAddSet)
                        .semantics {
                            contentDescription = uiString(R.string.l10n_lift_add_a_set_to_b9d5d907, group.name)
                        },
                ) {
                    Icon(
                        Icons.Filled.AddCircle,
                        contentDescription = null,
                        tint = if (canAdd) Palette.effortColor else Palette.textTertiary,
                        modifier = Modifier.size(17.dp),
                    )
                    Text(uiString(R.string.l10n_lift_add_set_6e0bd060), style = NoopType.caption, color = if (canAdd) Palette.effortColor else Palette.textTertiary)
                }
                Box(
                    modifier = Modifier
                        .width(30.dp)
                        .clickable(enabled = canRemove, onClick = onRemoveSet)
                        .semantics {
                            contentDescription = uiString(
                                R.string.l10n_lift_remove_the_last_set_from_5c65e3d6,
                                group.name,
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.RemoveCircleOutline,
                        contentDescription = null,
                        tint = if (canRemove) Palette.textSecondary else Palette.textTertiary.copy(alpha = 0.4f),
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
    }
}

private val EditSetColumnWidth = 34.dp

/** A chrome-less numeric field with a dash placeholder, matching the Swift `TextField` + `.plain`. */
@Composable
private fun EditField(
    value: String,
    keyboard: KeyboardType,
    modifier: Modifier = Modifier,
    onValueChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        textStyle = NoopType.bodyNumber.copy(color = Palette.textPrimary),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        cursorBrush = SolidColor(Palette.accent),
        modifier = modifier.onFocusChanged { onFocusChange(it.isFocused) },
        decorationBox = { inner ->
            if (value.isEmpty()) Text("—", style = NoopType.bodyNumber, color = Palette.textTertiary) else inner()
        },
    )
}

/**
 * Write the changes: the sets that changed or were added, the ids of sets no longer on the sheet, and the
 * session RPE when it was corrected.
 *
 * Only the SESSION is touched — never the program it ran from.
 */
private suspend fun saveEdits(
    vm: AppViewModel,
    session: LiftSessionRow,
    exercises: List<LiftExerciseRows>,
    sessionRpeText: String,
    originalSessionRpe: String,
    system: UnitSystem,
) {
    val repo = vm.liftLog
    val rows = repo.sets(session.id)
    val change = LiftSetEdit.changes(rows, exercises, system)
    if (change.upserts.isNotEmpty()) repo.upsertSets(change.upserts)
    repo.deleteSets(change.deletedIds)
    if (sessionRpeText != originalSessionRpe) {
        repo.upsertSessions(listOf(session.copy(sessionRpe = LiftFormat.number(sessionRpeText))))
    }
}
