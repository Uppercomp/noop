package com.noop.ui

import com.noop.R
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noop.data.LiftExerciseRow
import com.noop.data.LiftLogRepository
import com.noop.data.LiftMuscle
import com.noop.data.LiftProgramItemRow
import com.noop.data.LiftProgramRow
import com.noop.data.LiftVocabularyFullException
import com.noop.data.primary
import com.noop.data.secondaries
import kotlinx.coroutines.launch
import java.util.UUID

// Build or edit a program: a name and an ordered list of exercise lines carrying the TARGETS — working
// sets, reps, weight, rest and the user's own technique note.
//
// Lines are edited as local drafts and written in one go on Save, through `replaceProgramItems`, which
// swaps the whole list transactionally. Editing a program never rewrites history: a session snapshots the
// program's NAME when it runs, so renaming "Upper A" or deleting it entirely leaves every past session
// reading exactly as it did.
//
// Kotlin twin of `Strand/Screens/LiftProgramEditorSheet.swift` + `LiftProgramItemSheet.swift`.
//
// ONE SHEET, TWO STEPS. iOS presents the line editor as a sheet INSIDE the program sheet; Android's
// `ModalBottomSheet` does not nest reliably, so the second step replaces the first inside the same sheet
// and carries a back chevron. The flow — and what Cancel/Save mean at each step — is otherwise identical.

/**
 * The line editor's pure half: what a typed target means. Pulled out of the composable so the parse
 * rules (which decide what gets STORED) are unit-testable without a device.
 *
 * Every target is optional — this is the plan, not the record. A blank field is nil, not zero: nil means
 * "no target", which the session then shows as the grey number carried from last time.
 *
 * The Swift twin is `LiftProgramItemSheet`'s computed properties plus its `save()`.
 */
object LiftLineTargets {

    /**
     * The RPE ceiling as typed, when it is one: 1 to 10. A blank field is no ceiling, and anything
     * outside the range is refused rather than clamped — clamping would store a number the user did not
     * type.
     */
    fun maxRpe(text: String): Double? = LiftFormat.number(text)?.takeIf { it in 1.0..10.0 }

    /** True when something was typed that is not a valid ceiling, so the field can say so. */
    fun maxRpeInvalid(text: String): Boolean = text.trim().isNotEmpty() && maxRpe(text) == null

    /**
     * A whole-number target, or null for blank/garbage.
     *
     * Deliberately NOT [LiftFormat.number]: a set count, a rep count and a rest period are integers, and
     * the Swift twin reads them with `Int(text)` — which refuses "4.5" and "4,5" outright. Truncating
     * instead would STORE a number the user did not type (4 sets from "4.5"), which is exactly the class
     * of divergence the stored-data contract exists to prevent.
     */
    fun whole(text: String): Int? = text.trim().toIntOrNull()

    /** A weight typed in the user's display unit, in the kilograms that get stored. */
    fun weightKg(text: String, system: UnitSystem): Double? =
        LiftFormat.number(text)?.let { LiftFormat.kilograms(fromDisplay = it, system = system) }

    /** Whether the line may be saved: a name, and no invalid ceiling in the way. */
    fun canSave(exercise: String, maxRpeText: String): Boolean =
        exercise.trim().isNotEmpty() && !maxRpeInvalid(maxRpeText)
}

/**
 * "4 × 8 · 60 kg · max RPE 8 · 2:00 rest" — only the parts that were actually filled in. Twin of the
 * Swift `targetSummary`.
 *
 * Returns the parts rather than one String because three of them ARE copy on the Apple side
 * (`"\(sets) sets"`, `"max RPE \(…)"`, `"\(…) rest"`, `"No targets set"`) and resolving a resource needs
 * the Application — see [DisplayText]. The join itself (`" · "`) is plain interpolation there too.
 */
internal fun liftProgramTargetSummary(item: LiftProgramItemRow, system: UnitSystem): List<DisplayText> {
    val parts = mutableListOf<DisplayText>()
    val sets = item.targetSets
    if (sets != null) {
        val reps = item.targetRepsLow
        parts += if (reps != null) {
            DisplayText.Dynamic("$sets × $reps")
        } else {
            DisplayText.Resource(R.string.l10n_lift_lld_sets_703048fd, listOf(sets))
        }
    }
    item.targetWeightKg?.let { parts += DisplayText.Dynamic(LiftFormat.weight(it, system)) }
    item.targetRpe?.let {
        parts += DisplayText.Resource(R.string.l10n_lift_max_rpe_ef20676d, listOf(LiftFormat.trim(it)))
    }
    item.restSec?.let {
        parts += DisplayText.Resource(R.string.l10n_lift_rest_1bd51a33, listOf(LiftFormat.duration(it)))
    }
    if (parts.isEmpty()) parts += DisplayText.Resource(R.string.l10n_lift_no_targets_set_e8d35c12)
    return parts
}

/** The line being added or edited, keyed so "new line" has an identity of its own. */
private data class LineDraft(val id: String, val item: LiftProgramItemRow?)

/**
 * The program editor sheet body. Hosted inside a [androidx.compose.material3.ModalBottomSheet] by
 * [LiftLogScreen].
 *
 * @param program the program being edited, or null to create a new one.
 * @param onSaved called after a successful save or delete, so the caller can reload.
 */
@Composable
fun LiftProgramEditorSheet(
    repo: LiftLogRepository,
    deviceId: String,
    system: UnitSystem,
    program: LiftProgramRow?,
    onSaved: () -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(program?.name ?: "") }
    var note by remember { mutableStateOf(program?.note ?: "") }
    // `ord` is assigned from the list index on save, so reordering is all it takes to reorder.
    val items = remember { mutableStateListOf<LiftProgramItemRow>() }
    var saving by remember { mutableStateOf(false) }
    var editingLine by remember { mutableStateOf<LineDraft?>(null) }
    var confirmingDelete by remember { mutableStateOf(false) }

    LaunchedEffect(program?.id) {
        if (program != null) {
            items.clear()
            items.addAll(repo.programItems(program.id))
        }
    }

    val editing = editingLine
    if (editing != null) {
        LiftProgramLineEditor(
            repo = repo,
            deviceId = deviceId,
            system = system,
            line = editing.item,
            onApply = { saved ->
                val at = items.indexOfFirst { it.id == saved.id }
                if (at >= 0) items[at] = saved else items.add(saved)
                editingLine = null
            },
            onBack = { editingLine = null },
        )
        return
    }

    val isNew = program == null

    ScreenScaffold(
        title = if (isNew) {
            uiString(R.string.l10n_lift_new_program_742c5122)
        } else {
            uiString(R.string.l10n_lift_edit_program_1cf307c0)
        },
        subtitle = uiString(R.string.l10n_lift_your_targets_for_each_exercise_what_you_d88ef9db),
        leading = {
            IconButton(onClick = onClose) {
                Icon(Icons.Filled.Close, contentDescription = uiString(R.string.l10n_components_close_bbfa773e), tint = Palette.textSecondary)
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.sectionGap)) {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                SectionHeader(uiString(R.string.l10n_lift_program_9d68007b), overline = uiString(R.string.l10n_lift_details_dc3decbb))
                NoopCard {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        EditorField(uiString(R.string.l10n_devices_screen_name_709a2322)) {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                placeholder = { Text(uiString(R.string.l10n_lift_upper_a_0d8ada3e), style = NoopType.body, color = Palette.textTertiary) },
                                singleLine = true,
                                colors = liftFieldColors(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        EditorField(uiString(R.string.l10n_lift_note_optional_4e395670)) {
                            OutlinedTextField(
                                value = note,
                                // Capped at what the hub can actually show. A field that silently
                                // discards the end is worse than one that stops.
                                onValueChange = { note = it.take(LiftLogRepository.maxProgramNoteLength) },
                                placeholder = {
                                    Text(uiString(R.string.l10n_lift_anything_you_want_to_remember_7889a085), style = NoopType.body, color = Palette.textTertiary)
                                },
                                singleLine = true,
                                colors = liftFieldColors(),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                SectionHeader(uiString(R.string.l10n_lift_exercises_4dc503da), overline = uiString(R.string.l10n_lift_in_order_32a183d4))

                if (items.isEmpty()) {
                    NoopCard {
                        Text(
                            uiString(R.string.l10n_lift_no_exercises_yet_add_the_first_one_below_67b9eaf1),
                            style = NoopType.footnote,
                            color = Palette.textSecondary,
                        )
                    }
                } else {
                    items.forEachIndexed { index, item ->
                        ProgramLineRow(
                            item = item,
                            system = system,
                            canMoveUp = index > 0,
                            canMoveDown = index < items.size - 1,
                            onEdit = { editingLine = LineDraft(item.id, item) },
                            onMoveUp = { if (index > 0) items.add(index - 1, items.removeAt(index)) },
                            onMoveDown = { if (index < items.size - 1) items.add(index + 1, items.removeAt(index)) },
                            onRemove = { items.removeAll { it.id == item.id } },
                        )
                    }
                }

                NoopButton(
                    text = uiString(R.string.l10n_lift_add_exercise_64404808),
                    leadingIcon = Icons.Filled.Add,
                    kind = NoopButtonKind.Secondary,
                    onClick = { editingLine = LineDraft("new", null) },
                )
            }

            if (!isNew) {
                NoopButton(
                    text = uiString(R.string.l10n_lift_delete_program_f4b7b46b),
                    leadingIcon = Icons.Outlined.Delete,
                    kind = NoopButtonKind.Secondary,
                    onClick = { confirmingDelete = true },
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                SheetTextLink(uiString(R.string.ground_truth_cancel), onClick = onClose)
                Spacer(Modifier.weight(1f))
                NoopButton(
                    text = uiString(R.string.ground_truth_save),
                    kind = NoopButtonKind.Primary,
                    enabled = name.trim().isNotEmpty() && !saving,
                    modifier = Modifier.width(160.dp),
                    onClick = {
                        saving = true
                        scope.launch {
                            saveProgram(repo, deviceId, program, name, note, items.toList())
                            saving = false
                            onSaved()
                            onClose()
                        }
                    },
                )
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(uiString(R.string.l10n_lift_delete_this_program_1a710ad3), style = NoopType.headline, color = Palette.textPrimary) },
            text = {
                Text(
                    uiString(R.string.l10n_lift_sessions_you_already_logged_from_it_are_153d1b27),
                    style = NoopType.body,
                    color = Palette.textSecondary,
                )
            },
            confirmButton = {
                SheetTextLink(uiString(R.string.ground_truth_delete_confirm), color = Palette.statusCritical) {
                    confirmingDelete = false
                    scope.launch {
                        program?.let { repo.deleteProgram(it.id) }
                        onSaved()
                        onClose()
                    }
                }
            },
            dismissButton = { SheetTextLink(uiString(R.string.ground_truth_cancel)) { confirmingDelete = false } },
            containerColor = Palette.surfaceOverlay,
        )
    }
}

/**
 * Write the program and its lines. `ord` comes from the list position, and the whole line list is
 * replaced in one transaction — so a reorder, an added line and a removed line are one write, not three.
 */
private suspend fun saveProgram(
    repo: LiftLogRepository,
    deviceId: String,
    program: LiftProgramRow?,
    name: String,
    note: String,
    items: List<LiftProgramItemRow>,
) {
    val now = System.currentTimeMillis() / 1000L
    val id = program?.id ?: UUID.randomUUID().toString()
    val trimmedNote = note.trim()
    repo.upsertPrograms(
        listOf(
            LiftProgramRow(
                id = id,
                deviceId = deviceId,
                name = name.trim(),
                note = trimmedNote.ifEmpty { null },
                createdAt = program?.createdAt ?: now,
                updatedAt = now,
                archived = program?.archived ?: false,
            ),
        ),
    )
    repo.replaceProgramItems(id, items.mapIndexed { index, item -> item.copy(deviceId = deviceId, programId = id, ord = index) })
}

/** One line as the program editor lists it: the exercise, its targets, and the reorder/remove controls. */
@Composable
private fun ProgramLineRow(
    item: LiftProgramItemRow,
    system: UnitSystem,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    onEdit: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    NoopCard {
        Row(verticalAlignment = Alignment.Top) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .plainClickable(onEdit),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(item.exercise, style = NoopType.headline, color = Palette.textPrimary)
                Text(liftProgramTargetSummary(item, system).joined(" · "), style = NoopType.caption, color = Palette.textSecondary)
                item.note?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = NoopType.caption, color = Palette.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                // iOS `VStack(spacing: NoopMetrics.rowSpacing)` — a literal 10, not one of the 4-based
                // space indices, so it is NOT `space2` (2dp) here.
                verticalArrangement = Arrangement.spacedBy(Metrics.space10),
            ) {
                LineControl(
                    Icons.Filled.KeyboardArrowUp,
                    uiString(R.string.l10n_lift_move_up_b4f57cd0),
                    canMoveUp,
                    onMoveUp,
                )
                LineControl(
                    Icons.Filled.KeyboardArrowDown,
                    uiString(R.string.l10n_lift_move_down_260ff8ae),
                    canMoveDown,
                    onMoveDown,
                )
                LineControl(
                    Icons.Outlined.Delete,
                    uiString(R.string.l10n_lift_remove_exercise_4c522946),
                    true,
                    onRemove,
                )
            }
        }
    }
}

@Composable
private fun LineControl(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(28.dp)
            .semantics { contentDescription = label },
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (enabled) Palette.textSecondary else Palette.textTertiary.copy(alpha = Palette.disabledOpacity),
            modifier = Modifier.size(15.dp),
        )
    }
}

/**
 * Edit ONE exercise line: which exercise, and the targets for it — the second step of the program sheet.
 *
 * It also REMEMBERS the exercise, with the muscles the user gives it, so the classification is a
 * one-time, few-second action per exercise rather than a question asked every session.
 */
@Composable
private fun LiftProgramLineEditor(
    repo: LiftLogRepository,
    deviceId: String,
    system: UnitSystem,
    line: LiftProgramItemRow?,
    onApply: (LiftProgramItemRow) -> Unit,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var exercise by remember { mutableStateOf(line?.exercise ?: "") }
    var primary by remember { mutableStateOf<LiftMuscle?>(null) }
    var secondaries by remember { mutableStateOf<Set<LiftMuscle>>(emptySet()) }
    var setsText by remember { mutableStateOf(line?.targetSets?.toString() ?: "") }
    var repsText by remember { mutableStateOf(line?.targetRepsLow?.toString() ?: "") }
    var weightText by remember {
        mutableStateOf(
            line?.targetWeightKg?.let { LiftFormat.trim(LiftFormat.display(fromKilograms = it, system = system)) } ?: "",
        )
    }
    var restText by remember { mutableStateOf(line?.restSec?.toString() ?: "") }
    var maxRpeText by remember { mutableStateOf(line?.targetRpe?.let { LiftFormat.trim(it) } ?: "") }
    var note by remember { mutableStateOf(line?.note ?: "") }

    val vocabulary = remember { mutableStateListOf<LiftExerciseRow>() }
    var forgetting by remember { mutableStateOf<LiftExerciseRow?>(null) }
    var vocabularyFullLimit by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(line?.id) {
        val known = repo.exercises(deviceId)
        vocabulary.clear()
        vocabulary.addAll(known)
        // An existing line adopts whatever classification its exercise already carries, so editing a
        // line shows the muscles the exercise is known by rather than an empty picker.
        val mine = line?.let { l -> known.firstOrNull { it.name == l.exercise } }
        if (mine != null) {
            primary = mine.primary
            secondaries = mine.secondaries.toSet()
        }
    }

    val suggestions = LiftExerciseVocabulary.suggestions(vocabulary, exercise)
    val canSave = LiftLineTargets.canSave(exercise, maxRpeText)

    ScreenScaffold(
        title = if (line == null) {
            uiString(R.string.l10n_lift_add_exercise_64404808)
        } else {
            uiString(R.string.l10n_lift_edit_exercise_a21e9cce)
        },
        subtitle = uiString(R.string.l10n_lift_type_any_name_you_like_noop_remembers_it_6a414fde),
        leading = {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = uiString(R.string.l10n_add_device_wizard_back_b52b36b7), tint = Palette.textSecondary)
            }
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.sectionGap)) {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                SectionHeader(uiString(R.string.l10n_lift_exercise_20863c83), overline = uiString(R.string.timeline_metric_movement))
                NoopCard {
                    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                        OutlinedTextField(
                            value = exercise,
                            onValueChange = { exercise = it },
                            placeholder = {
                                Text(uiString(R.string.l10n_lift_incline_dumbbell_press_bea7942b), style = NoopType.body, color = Palette.textTertiary)
                            },
                            singleLine = true,
                            colors = liftFieldColors(),
                            modifier = Modifier.fillMaxWidth(),
                        )

                        if (suggestions.isNotEmpty()) {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Overline(uiString(R.string.l10n_lift_used_before_c503a9e2))
                                suggestions.forEach { row ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Row(
                                            modifier = Modifier
                                                .weight(1f)
                                                .plainClickable {
                                                    // Take the classification it already carries, so a
                                                    // movement is classified once and never asked again.
                                                    exercise = row.name
                                                    primary = row.primary
                                                    secondaries = row.secondaries.toSet()
                                                },
                                            verticalAlignment = Alignment.CenterVertically,
                                        ) {
                                            Icon(
                                                Icons.Filled.Check,
                                                contentDescription = null,
                                                tint = Palette.textTertiary,
                                                modifier = Modifier.size(13.dp),
                                            )
                                            Spacer(Modifier.width(8.dp))
                                            LiftExerciseSuggestionLabel(row, Modifier.weight(1f))
                                        }
                                        // A typo becomes a permanent picker entry otherwise. Forgetting a
                                        // name is safe by construction: every logged set SNAPSHOTS its
                                        // exercise name and classification, so history is untouched.
                                        IconButton(
                                            onClick = { forgetting = row },
                                            modifier = Modifier
                                                .size(32.dp)
                                                .semantics { contentDescription = uiString(R.string.l10n_lift_forget_this_exercise_048b017f) },
                                        ) {
                                            Icon(
                                                Icons.Outlined.Delete,
                                                contentDescription = null,
                                                tint = Palette.textTertiary,
                                                modifier = Modifier.size(15.dp),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            LiftMusclePicker(
                primary = primary,
                onPrimary = { primary = it },
                secondaries = secondaries,
                onSecondaries = { secondaries = it },
            )

            Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                SectionHeader(uiString(R.string.l10n_lift_targets_d35260a0), overline = uiString(R.string.l10n_lift_what_you_re_aiming_for_e4675328))
                NoopCard {
                    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                            EditorField(uiString(R.string.l10n_lift_working_sets_5cb19af1), Modifier.weight(1f)) {
                                LiftNumberField("4", setsText) { setsText = it }
                            }
                            EditorField(uiString(R.string.l10n_lift_reps_702045f3), Modifier.weight(1f)) {
                                LiftNumberField("8", repsText) { repsText = it }
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                            EditorField(
                                uiString(
                                    if (system == UnitSystem.IMPERIAL) {
                                        R.string.l10n_lift_weight_lb_5b596595
                                    } else {
                                        R.string.l10n_lift_weight_kg_d2498320
                                    },
                                ),
                                Modifier.weight(1f),
                            ) {
                                LiftNumberField("60", weightText) { weightText = it }
                            }
                            EditorField(uiString(R.string.l10n_lift_rest_seconds_0341bbc2), Modifier.weight(1f)) {
                                LiftNumberField("120", restText) { restText = it }
                            }
                        }
                        // iOS puts this in an HStack with a `Color.clear` spacer, so the field takes HALF
                        // the width like the pairs above it. It has to be a Row: `Modifier.weight` on a
                        // direct child of a Column is a VERTICAL weight, which inside a scrolling column
                        // collapses the child — that cost this field its label the first time.
                        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                            EditorField(uiString(R.string.l10n_lift_max_rpe_1_10_c5fbb081), Modifier.weight(1f)) {
                                LiftNumberField("8", maxRpeText) { maxRpeText = it }
                            }
                            Spacer(Modifier.weight(1f))
                        }
                        if (LiftLineTargets.maxRpeInvalid(maxRpeText)) {
                            Text(
                                uiString(R.string.l10n_lift_max_rpe_must_be_between_1_and_10_8cf168af),
                                style = NoopType.footnote,
                                color = Palette.statusWarning,
                            )
                        }
                        // Max RPE is a CEILING, not effort planned in advance: the hardest a set should
                        // feel, so a lifter knows where to hold back. It shows grey in the session and,
                        // like every other grey number, a set left unrated saves it — typing a rating
                        // always wins.
                        Text(
                            uiString(R.string.l10n_lift_max_rpe_is_a_ceiling_the_hardest_a_set_s_90ff9272),
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                        )
                        Text(
                            uiString(R.string.l10n_lift_every_target_is_optional_this_is_the_pla_6cd6c2c2),
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                        )
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                SectionHeader(uiString(R.string.l10n_lift_technique_note_6d200ec1), overline = uiString(R.string.l10n_lift_in_your_words_c93103a3))
                NoopCard {
                    OutlinedTextField(
                        value = note,
                        // A cue read between sets, and it renders directly above the set rows — every
                        // line pushes them down the screen.
                        onValueChange = { note = it.take(LiftLogRepository.maxExerciseNoteLength) },
                        placeholder = {
                            Text(uiString(R.string.l10n_lift_slow_eccentric_pause_at_the_bottom_59b4d011), style = NoopType.body, color = Palette.textTertiary)
                        },
                        minLines = 1,
                        maxLines = 4,
                        colors = liftFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                SheetTextLink(uiString(R.string.ground_truth_cancel), onClick = onBack)
                Spacer(Modifier.weight(1f))
                NoopButton(
                    text = uiString(R.string.ground_truth_save),
                    kind = NoopButtonKind.Primary,
                    enabled = canSave,
                    modifier = Modifier.width(160.dp),
                    onClick = {
                        scope.launch {
                            // Remember the exercise (and its classification) in the vocabulary, so it is
                            // offered back next time.
                            try {
                                LiftExerciseVocabulary.remember(
                                    repo = repo,
                                    deviceId = deviceId,
                                    name = exercise.trim(),
                                    primary = primary,
                                    secondaries = LiftExerciseVocabulary.ordered(secondaries, primary),
                                    known = vocabulary,
                                )
                            } catch (full: LiftVocabularyFullException) {
                                // Refused rather than silently dropped: the user typed a name and deserves
                                // to know it was not remembered.
                                vocabularyFullLimit = full.limit
                                return@launch
                            }

                            val trimmedNote = note.trim()
                            onApply(
                                LiftProgramItemRow(
                                    id = line?.id ?: UUID.randomUUID().toString(),
                                    deviceId = deviceId,
                                    // Assigned properly by the parent on save; a placeholder here would be
                                    // a second source of truth for ordering.
                                    programId = line?.programId ?: "",
                                    ord = line?.ord ?: 0,
                                    exercise = exercise.trim(),
                                    targetSets = LiftLineTargets.whole(setsText),
                                    // ONE rep count: `targetRepsHigh` stays null, a schema column the
                                    // editor does not fill.
                                    targetRepsLow = LiftLineTargets.whole(repsText),
                                    targetRepsHigh = null,
                                    targetRpe = LiftLineTargets.maxRpe(maxRpeText),
                                    targetWeightKg = LiftLineTargets.weightKg(weightText, system),
                                    restSec = LiftLineTargets.whole(restText),
                                    note = trimmedNote.ifEmpty { null },
                                ),
                            )
                        }
                    },
                )
            }
        }
    }

    forgetting?.let { row ->
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = {
                Text(
                    uiString(R.string.l10n_lift_forget_ccf0ad7c, row.name),
                    style = NoopType.headline,
                    color = Palette.textPrimary,
                )
            },
            text = {
                Text(
                    uiString(R.string.l10n_lift_it_stops_being_offered_here_sessions_you_3418312c),
                    style = NoopType.body,
                    color = Palette.textSecondary,
                )
            },
            confirmButton = {
                SheetTextLink(uiString(R.string.l10n_lift_forget_03d5d801), color = Palette.statusCritical) {
                    forgetting = null
                    scope.launch {
                        repo.deleteExercise(row.id)
                        vocabulary.removeAll { it.id == row.id }
                    }
                }
            },
            dismissButton = { SheetTextLink(uiString(R.string.ground_truth_cancel)) { forgetting = null } },
            containerColor = Palette.surfaceOverlay,
        )
    }

    vocabularyFullLimit?.let {
        AlertDialog(
            onDismissRequest = { vocabularyFullLimit = null },
            title = {
                Text(
                    uiString(R.string.l10n_lift_you_ve_saved_the_most_exercises_noop_rem_d28e2ee8),
                    style = NoopType.headline,
                    color = Palette.textPrimary,
                )
            },
            text = {
                Text(
                    uiString(R.string.l10n_lift_forget_one_you_no_longer_use_and_this_on_d53bdd32),
                    style = NoopType.body,
                    color = Palette.textSecondary,
                )
            },
            confirmButton = { SheetTextLink(uiString(R.string.l10n_lift_ok_9ce3bd42)) { vocabularyFullLimit = null } },
            containerColor = Palette.surfaceOverlay,
        )
    }
}

/** The shared editor field: an overline label 6dp above its control. */
@Composable
private fun EditorField(label: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Overline(label)
        content()
    }
}

@Composable
private fun LiftNumberField(placeholder: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        placeholder = { Text(placeholder, style = NoopType.bodyNumber, color = Palette.textTertiary) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        colors = liftFieldColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** A tappable line of text, used for a sheet's Cancel/Delete/OK actions. */
@Composable
internal fun SheetTextLink(label: String, color: Color = Palette.textSecondary, onClick: () -> Unit) {
    Text(
        label,
        style = NoopType.body,
        color = color,
        modifier = Modifier
            .plainClickable(onClick)
            .padding(vertical = 8.dp, horizontal = 4.dp)
            .semantics { contentDescription = label },
    )
}

/**
 * The click target for a whole card row. Mirrors the Swift `.buttonStyle(.plain)`: the row is one tap
 * target rather than a button with its own chrome.
 */
internal fun Modifier.plainClickable(onClick: () -> Unit): Modifier = clickable(onClick = onClick)

/** The editor fields' colours, matching the other editors' form idiom. */
@Composable
internal fun liftFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Palette.textPrimary,
    unfocusedTextColor = Palette.textPrimary,
    cursorColor = Palette.accent,
    focusedBorderColor = Palette.accent,
    unfocusedBorderColor = Palette.hairline,
    focusedContainerColor = Palette.surfaceInset,
    unfocusedContainerColor = Palette.surfaceInset,
)
