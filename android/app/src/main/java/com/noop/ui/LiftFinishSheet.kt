package com.noop.ui

import com.noop.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.noop.data.LiftLogRepository
import com.noop.data.LiftPlanItem
import com.noop.data.LiftSessionRow
import com.noop.data.WorkoutRow
import com.noop.data.liftSetEntity
import kotlinx.coroutines.launch
import java.util.UUID

// The end of a session: one session rating, one answer about the sets that were never started, and one
// answer about whether the program keeps what this session changed.
//
// Kotlin twin of the finish sheet in `Strand/Screens/LiftSessionView.swift`.
//
// SAVING FILES NOTHING WHEN NOTHING WAS DONE. With no set performed, "Discard them" turns every set into a
// zero; filing that anyway wrote a session with nothing in it AND a manual workout, and the engine fills
// that workout's strain from the heart rate the strap measured — so an hour that recorded nothing still
// read back as a workout. The program's set counts are a separate thing the user chose explicitly, so
// those still apply.

/**
 * Every logged session is filed under this sport — deliberately the SAME token the Hevy/Liftosaur importer
 * uses ([com.noop.ingest.LiftingImporter.SPORT]), so a typed session and an imported one land in one bucket
 * with one icon rather than two nearly-identical rows in the day's list.
 */
const val LIFT_SPORT = com.noop.ingest.LiftingImporter.SPORT

private enum class UnfinishedChoice { Complete, Discard }

private enum class ProgramChoice { Update, Keep }

@Composable
fun LiftFinishSheet(
    vm: AppViewModel,
    session: LiftSessionController,
    deviceId: String,
    onCancel: () -> Unit,
    onFinished: () -> Unit,
    onClose: () -> Unit,
) {
    @Suppress("UNUSED_VARIABLE")
    val revision = session.revision
    val engine = session.engine
    val repo = vm.liftLog

    var sessionRpeText by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var confirmingDiscard by remember { mutableStateOf(false) }
    // The two questions finishing can ask. Null until answered: saving waits for an answer rather than
    // deciding for the user.
    var unfinishedChoice by remember { mutableStateOf<UnfinishedChoice?>(null) }
    var programChoice by remember { mutableStateOf<ProgramChoice?>(null) }
    var setCountChanges by remember { mutableStateOf<List<LiftSessionController.SetCountChange>>(emptyList()) }

    LaunchedEffect(session.programId, engine?.plan) {
        val programId = session.programId
        val plan = engine?.plan
        setCountChanges = if (programId == null || plan == null) {
            emptyList()
        } else {
            LiftSessionController.setCountChanges(plan, repo.programItems(programId))
        }
    }

    val unfinished = session.unfinishedSlots.size
    val added = engine?.plan?.filter { it.addedInSession } ?: emptyList()
    val asksAboutProgram = setCountChanges.isNotEmpty() || added.isNotEmpty()
    val answered = (unfinished == 0 || unfinishedChoice != null) && (!asksAboutProgram || programChoice != null)

    ScreenScaffold(
        title = uiString(R.string.l10n_lift_finish_session_5fe14d3f),
        subtitle = uiString(R.string.l10n_lift_one_number_for_the_whole_session_so_a_le_e337dfde),
        leading = {
            SheetTextLink(uiString(R.string.l10n_add_device_wizard_back_b52b36b7), onClick = onCancel)
        },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.sectionGap)) {
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    Overline(uiString(R.string.l10n_lift_how_hard_was_the_whole_session_1_10_a0035188))
                    OutlinedTextField(
                        value = sessionRpeText,
                        onValueChange = { sessionRpeText = it },
                        placeholder = { Text("7", style = NoopType.bodyNumber, color = Palette.textTertiary) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        colors = liftFieldColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        uiString(R.string.l10n_lift_this_is_session_rpe_multiplied_by_the_se_280b899b),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
            }

            if (unfinished > 0) {
                NoopCard {
                    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                        Overline(uiString(R.string.l10n_lift_unfinished_sets_97573f9c))
                        Text(
                            uiString(R.string.l10n_lift_sets_not_started_lld_ea906f1b, unfinished),
                            style = NoopType.body,
                            color = Palette.textPrimary,
                        )
                        LiftChoiceRow(
                            options = listOf(
                                UnfinishedChoice.Complete to uiString(R.string.l10n_lift_complete_them_9b048552),
                                UnfinishedChoice.Discard to uiString(R.string.l10n_lift_discard_them_c0fe148b),
                            ),
                            selection = unfinishedChoice,
                            onSelect = { unfinishedChoice = it },
                        )
                        Text(
                            uiString(R.string.l10n_lift_completing_saves_them_with_the_grey_numb_77dee8af),
                            style = NoopType.footnote,
                            color = Palette.textTertiary,
                        )
                        // Said before Save rather than after: `save` files nothing when no set counts.
                        if (unfinishedChoice == UnfinishedChoice.Discard &&
                            !LiftSessionController.anyPerformed(session.setsToSave(completingUnfinished = false))
                        ) {
                            Text(
                                uiString(R.string.l10n_lift_every_set_would_be_a_zero_so_discarding_fdb73594),
                                style = NoopType.footnote,
                                color = Palette.statusWarning,
                            )
                        }
                    }
                }
            }

            if (asksAboutProgram) {
                NoopCard {
                    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                        Overline(uiString(R.string.l10n_lift_program_9d68007b))
                        Text(
                            programQuestion(
                                countsChanged = setCountChanges.isNotEmpty(),
                                exercisesAdded = added.isNotEmpty(),
                            ).text(),
                            style = NoopType.body,
                            color = Palette.textPrimary,
                        )
                        setCountChanges.forEach { change ->
                            Text(
                                uiString(
                                    R.string.l10n_lift_lld_lld_sets_323d06ef,
                                    change.exercise,
                                    change.from,
                                    change.to,
                                ),
                                style = NoopType.bodyNumber,
                                color = Palette.textSecondary,
                            )
                        }
                        added.forEach { line ->
                            Text(
                                uiString(
                                    R.string.l10n_lift_new_sets_lld_0c6ac0fa,
                                    line.exercise,
                                    line.targetSets,
                                ),
                                style = NoopType.bodyNumber,
                                color = Palette.textSecondary,
                            )
                        }
                        LiftChoiceRow(
                            options = listOf(
                                ProgramChoice.Update to uiString(R.string.l10n_lift_update_program_a6ee257f),
                                ProgramChoice.Keep to uiString(R.string.l10n_lift_keep_as_it_was_56a32cea),
                            ),
                            selection = programChoice,
                            onSelect = { programChoice = it },
                        )
                    }
                }
            }

            // One way to save. Session RPE above is optional, so an empty field is simply no rating; a
            // separate "Skip" saved exactly the same way and read as a second choice.
            NoopButton(
                text = uiString(R.string.l10n_lift_save_session_8c7478cc),
                kind = NoopButtonKind.Primary,
                enabled = !saving && answered,
                fullWidth = true,
                onClick = {
                    saving = true
                    // Launched on the PROCESS scope, not this sheet's: saving calls `session.finish()`
                    // first, which makes the session inactive and takes this sheet out of the
                    // composition — and a composition-scoped coroutine is cancelled the moment its
                    // composable leaves. That cost the sets, the program write-back and the paired
                    // workout while still writing the session row.
                    vm.liftScope.launch {
                        saveSession(
                            vm = vm,
                            repo = repo,
                            session = session,
                            deviceId = deviceId,
                            sessionRpeText = sessionRpeText,
                            completeUnfinished = unfinishedChoice == UnfinishedChoice.Complete,
                            keepProgramChanges = programChoice == ProgramChoice.Update,
                        )
                        saving = false
                        onFinished()
                        onClose()
                    }
                },
            )
            if (!answered) {
                Text(uiString(R.string.l10n_lift_choose_an_option_above_to_save_0df4e134), style = NoopType.footnote, color = Palette.textTertiary)
            }

            // A way OUT that records nothing. Until this existed, the only route off this screen saved: a
            // session started by a mis-tap had to be saved and then lived in the history for good.
            NoopButton(
                text = uiString(R.string.l10n_lift_discard_session_38ed11f8),
                leadingIcon = Icons.Outlined.Delete,
                kind = NoopButtonKind.Secondary,
                enabled = !saving,
                fullWidth = true,
                onClick = { confirmingDiscard = true },
            )
        }
    }

    if (confirmingDiscard) {
        AlertDialog(
            onDismissRequest = { confirmingDiscard = false },
            title = { Text(uiString(R.string.l10n_lift_discard_this_session_7e823f0e), style = NoopType.headline, color = Palette.textPrimary) },
            text = {
                Text(
                    uiString(
                        R.string.l10n_lift_lld_recorded_sets_will_be_thrown_away_no_576f3fdf,
                        engine?.completedWorkingSets ?: 0,
                    ),
                    style = NoopType.body,
                    color = Palette.textSecondary,
                )
            },
            confirmButton = {
                SheetTextLink(uiString(R.string.l10n_lift_discard_36fff63c), color = Palette.statusCritical) {
                    confirmingDiscard = false
                    session.discard()
                    onFinished()
                    onClose()
                }
            },
            dismissButton = { SheetTextLink(uiString(R.string.l10n_lift_keep_going_6902b083)) { confirmingDiscard = false } },
            containerColor = Palette.surfaceOverlay,
        )
    }
}

/**
 * A two-option choice that starts UNSELECTED, so "which answer" is a real question rather than a default
 * the user has to notice and correct. `SegmentedPillControl` needs a selection, and iOS's `Picker` here is
 * bound to an optional for exactly this reason.
 */
@Composable
private fun <T> LiftChoiceRow(options: List<Pair<T, String>>, selection: T?, onSelect: (T) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        options.forEach { (value, label) ->
            val selected = value == selection
            Text(
                label,
                style = NoopType.subhead,
                color = if (selected) Palette.accent else Palette.textSecondary,
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) Palette.accent.copy(alpha = 0.14f) else Palette.surfaceInset)
                    .clickable { onSelect(value) }
                    .padding(vertical = 10.dp),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Which program question the finish sheet asks. The DECISION is pinned; the words resolve at the call site. */
internal fun programQuestion(countsChanged: Boolean, exercisesAdded: Boolean): DisplayText = when {
    countsChanged && exercisesAdded ->
        DisplayText.Resource(R.string.l10n_lift_you_added_exercises_and_changed_the_numb_5dff7a6c)
    exercisesAdded -> DisplayText.Resource(R.string.l10n_lift_you_added_exercises_add_them_to_the_prog_531dc08e)
    else -> DisplayText.Resource(R.string.l10n_lift_you_changed_the_number_of_sets_keep_the_3b661010)
}

/**
 * Write the finished session: its `liftSession` row, its sets, the program write-back and the paired
 * `workout` row.
 *
 * Through the SAME path a manual workout takes, so it inherits overlap dedup, the engine's HR-derived
 * strain fill and delete/merge. `strain` stays nil deliberately: the engine fills it from the heart rate
 * the strap MEASURED, never from typed sets and reps — there is no validated path from typed sets to a
 * cardiovascular-strain equivalent.
 */
private suspend fun saveSession(
    vm: AppViewModel,
    repo: LiftLogRepository,
    session: LiftSessionController,
    deviceId: String,
    sessionRpeText: String,
    completeUnfinished: Boolean,
    keepProgramChanges: Boolean,
) {
    session.finish()
    val engine = session.engine ?: return
    val endTs = System.currentTimeMillis() / 1000L
    val startTs = engine.startTs.toLong()
    // After `finish`, which closes out the running rest: that set's measured rest belongs to it.
    val finished = session.setsToSave(completingUnfinished = completeUnfinished)

    if (!LiftSessionController.anyPerformed(finished)) {
        writeProgram(repo, deviceId, session, engine.plan, finished, keepProgramChanges)
        session.finishedSaving()
        return
    }

    val sessionId = UUID.randomUUID().toString()
    repo.upsertSessions(
        listOf(
            LiftSessionRow(
                id = sessionId,
                deviceId = deviceId,
                startTs = startTs,
                endTs = endTs,
                sport = LIFT_SPORT,
                programId = session.programId,
                // Snapshot the name: renaming or deleting the program never rewrites this session.
                programName = session.programName,
                sessionRpe = LiftFormat.number(sessionRpeText),
                note = session.programName,
            ),
        ),
    )
    // The id the row is ACTUALLY stored under. `upsertSessions` keeps the id of a session already stored
    // under the same natural key, so saving the same session twice (resume after a killed process, or a
    // second Save) would otherwise write this save's sets against an id no session row has — orphaning
    // every one of them from both JOIN-based reads. Verified against the device database: the sets were
    // there, the join found nothing.
    val storedSessionId = repo.sessionIdFor(deviceId, startTs, LIFT_SPORT) ?: sessionId

    // `ord` is COMPLETION order, which with out-of-order work is not the plan's order — and it is the
    // order that actually happened, which is what a session should read back as. Sets completed at finish
    // without being started come last.
    repo.upsertSets(
        finished.mapIndexed { ord, set ->
            val item = engine.planItem(set.slot)
            liftSetEntity(
                id = UUID.randomUUID().toString(),
                deviceId = deviceId,
                sessionId = storedSessionId,
                ord = ord,
                exercise = item?.exercise ?: "",
                // Snapshot the classification AS IT WAS, so reclassifying later never rewrites what past
                // weeks were counted as.
                primaryMuscle = item?.primaryMuscle,
                secondaryMuscles = item?.secondaryMuscles ?: emptyList(),
                setIndex = set.slot.setIndex,
                weightKg = set.weightKg,
                reps = set.reps,
                rpe = set.rpe,
                isWarmup = set.isWarmup,
                startTs = set.startTs?.toLong(),
                endTs = set.endTs?.toLong(),
                restSec = set.restSec,
                note = null,
            )
        },
    )

    writeProgram(repo, deviceId, session, engine.plan, finished, keepProgramChanges)

    vm.saveManualWorkout(
        WorkoutRow(
            deviceId = deviceId,
            startTs = startTs,
            endTs = endTs,
            sport = LIFT_SPORT,
            source = "manual",
            durationS = (endTs - startTs).coerceAtLeast(0L).toDouble(),
            notes = session.programName,
        ),
    )

    session.finishedSaving()
}

/**
 * Carry this session onto its program: each line's heaviest done set becomes its weight and reps (always);
 * changed set counts and exercises added during the session reach it only when the user chose to keep them.
 *
 * Re-reads the lines, so a program edited elsewhere while the session ran keeps every other change and a
 * line deleted since is not resurrected. The store call replaces the lines wholesale, so nothing is written
 * when no line differs.
 */
private suspend fun writeProgram(
    repo: LiftLogRepository,
    deviceId: String,
    session: LiftSessionController,
    plan: List<LiftPlanItem>,
    sets: List<LiftSessionController.FinishedSet>,
    keepChanges: Boolean,
) {
    val programId = session.programId ?: return
    val rows = repo.programItems(programId)
    val edited = LiftSessionController.programAfterSession(
        sets = sets,
        plan = plan,
        program = rows,
        keepingChanges = keepChanges,
        programId = programId,
        deviceId = deviceId,
    )
    if (edited == rows) return
    repo.replaceProgramItems(programId, edited)
}
