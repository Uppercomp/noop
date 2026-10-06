package com.noop.ui

import com.noop.R
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noop.analytics.LiftMetrics
import com.noop.data.LiftLogRepository
import com.noop.data.LiftMuscle
import com.noop.data.LiftPlanItem
import com.noop.data.LiftProgramRow
import com.noop.data.LiftSessionRow
import com.noop.data.primary
import com.noop.data.secondaries
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

// The Lift Log: build a program once, then run it in the gym by tapping through it.
//
// This screen is the front door — it lists the saved programs and the sessions run from them. It lives in
// the Effort colour world, like Workouts, because a finished session lands in the `workout` table beside
// every other workout.
//
// EFFORT IS NEVER MODIFIED HERE (load-bearing). NOOP's Effort is computed from heart rate alone
// (Karvonen %HRR → Edwards TRIMP), and there is no validated public path from typed sets/reps/weight to a
// cardiovascular-strain equivalent. So the lifting figures are shown BESIDE Effort and never folded into
// it.
//
// Kotlin twin of `Strand/Screens/LiftLogView.swift`.

/** How far back the Sessions list reaches. */
private const val LIFT_HISTORY_DAYS = 180

/** How far back "this week" reaches. */
private const val LIFT_WEEK_SEC = 7 * 86_400

/**
 * The span the weekly bar is drawn across.
 *
 * A DRAWING choice, not a dose. The evidence puts a floor at about 4 sets a week and identifies NO
 * ceiling for hypertrophy — gains continue above it with strongly diminishing returns — so any bar
 * maximum is arbitrary and must never be read as a target. 20 is chosen only because it comfortably
 * contains the range people actually train in, which puts the floor tick early on the bar and makes a
 * normal week read as progress rather than as "finished".
 *
 * The NUMBER beside the bar is the truth. The bar is context for it, and a count past 20 fills the bar
 * while the number keeps counting.
 */
private const val WEEKLY_SETS_BAR_SPAN = 20.0

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LiftLogScreen(vm: AppViewModel) {
    val context = LocalContext.current
    val repo = vm.liftLog
    val deviceId = vm.activeStrapId
    val session = vm.liftSession
    val system = remember { UnitPrefs.system(context) }
    val scope = rememberCoroutineScope()

    var programs by remember { mutableStateOf<List<LiftProgramRow>>(emptyList()) }
    var history by remember { mutableStateOf<List<LiftSessionRow>>(emptyList()) }
    var weekCounts by remember { mutableStateOf<Map<LiftMuscle, Double>>(emptyMap()) }
    var loaded by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<ProgramEditTarget?>(null) }
    var viewing by remember { mutableStateOf<LiftSessionRow?>(null) }
    var importing by remember { mutableStateOf(false) }

    // `savedSessions` is bumped once a finished session has been written, and the revision counter moves on
    // every change to a live one — so a session finishing (or resuming) reloads this list.
    val savedSessions = session.savedSessions

    // Read the session's observable fields UNCONDITIONALLY, before anything consults `isActive`. That
    // getter reads `engine`, which is a plain var on a mutable class and therefore invisible to Compose, so
    // a short-circuit `isActive && isPresented` never subscribes to `isPresented` and the session sheet
    // would never appear when a session started — a bug a device walk found and no compile could.
    @Suppress("UNUSED_VARIABLE")
    val sessionRevision = session.revision
    val sessionPresented = session.isPresented

    LaunchedEffect(deviceId, reload, savedSessions) {
        val now = System.currentTimeMillis() / 1000L
        programs = repo.programs(deviceId)
        history = repo.sessions(deviceId, now - LIFT_HISTORY_DAYS * 86_400L, now)
            // An abandoned session is not history: only a finished one lands in the list.
            .filter { it.endTs != null }
            .sortedByDescending { it.startTs }
        weekCounts = repo.setCounts(deviceId, now - LIFT_WEEK_SEC, now).fractional
        loaded = true
    }

    ScreenScaffold(
        title = uiString(R.string.nav_lift_log),
        subtitle = uiString(R.string.l10n_lift_build_a_program_once_then_tap_through_it_3bf123c9),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.sectionGap)) {
            HeaderCard()
            ProgramsSection(
                programs = programs,
                loaded = loaded,
                running = session.isActive,
                onNew = { editing = ProgramEditTarget("new", null) },
                onEdit = { editing = ProgramEditTarget(it.id, it) },
                onImport = { importing = true },
                onStart = { program ->
                    scope.launch {
                        startProgram(repo, deviceId, session, program)
                    }
                },
            )
            WeekSection(weekCounts)
            HistorySection(history, onOpen = { viewing = it })
        }
    }

    val target = editing
    if (target != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { editing = null },
            sheetState = sheetState,
            containerColor = Palette.surfaceRaised,
            contentColor = Palette.textPrimary,
        ) {
            LiftProgramEditorSheet(
                repo = repo,
                deviceId = deviceId,
                system = system,
                program = target.program,
                onSaved = { reload++ },
                onClose = { editing = null },
            )
        }
    }

    // A program from a spreadsheet filled in on a computer.
    if (importing) {
        val importSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { importing = false },
            sheetState = importSheetState,
            containerColor = Palette.surfaceRaised,
            contentColor = Palette.textPrimary,
        ) {
            LiftProgramImportSheet(
                vm = vm,
                deviceId = deviceId,
                onImported = { reload++ },
                onClose = { importing = false },
            )
        }
    }

    // A finished session, read back in full — every set as performed, the figures, and how each exercise
    // compares with the last time it was done.
    val detail = viewing
    if (detail != null) {
        val detailSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { viewing = null },
            sheetState = detailSheetState,
            containerColor = Palette.surfaceRaised,
            contentColor = Palette.textPrimary,
        ) {
            LiftSessionDetailSheet(
                vm = vm,
                session = detail,
                system = system,
                onChanged = { reload++ },
                onClose = { viewing = null },
            )
        }
    }

    // The live session, as a sheet over the hub — exactly the relationship the iOS session sheet has, and
    // the reason a session outlives it: this screen and that sheet are both renderings of the controller.
    if (session.isActive && session.isPresented) {
        val sessionSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { session.isPresented = false },
            sheetState = sessionSheetState,
            containerColor = Palette.surfaceRaised,
            contentColor = Palette.textPrimary,
        ) {
            LiftSessionScreen(
                vm = vm,
                session = session,
                deviceId = deviceId,
                system = system,
                onClose = { session.isPresented = false },
                onFinished = { reload++ },
            )
        }
    }
}

/**
 * Flatten a program into the plan the session runs. The plan is SNAPSHOT at start: editing or deleting the
 * program mid-session cannot change what is being tapped through.
 */
private suspend fun startProgram(
    repo: LiftLogRepository,
    deviceId: String,
    session: LiftSessionController,
    program: LiftProgramRow,
) {
    val items = repo.programItems(program.id)
    if (items.isEmpty()) return
    // Refuse to start a second session over a running one: two live sessions would both claim the strap
    // gesture and both write the in-flight snapshot. The running one is opened instead.
    if (session.isActive) {
        session.isPresented = true
        return
    }
    val vocabulary = repo.exercises(deviceId)
    val plan = items.map { item ->
        // The classification comes from the exercise vocabulary, which is the one place that owns it — the
        // program line deliberately stores no muscle of its own to drift from.
        val known = vocabulary.firstOrNull { it.name == item.exercise }
        LiftPlanItem(
            exercise = item.exercise,
            primaryMuscle = known?.primary,
            secondaryMuscles = known?.secondaries ?: emptyList(),
            plannedSets = item.targetSets ?: 1,
            plannedRestSec = item.restSec ?: LiftPlanItem.defaultRestSec,
            targetRepsLow = item.targetRepsLow,
            targetRepsHigh = item.targetRepsHigh,
            targetRpe = item.targetRpe,
            targetWeightKg = item.targetWeightKg,
            note = item.note,
            // Carried so a set added or dropped mid-session can be written back onto the line it came from,
            // and be there next time.
            programItemId = item.id,
        )
    }
    session.start(plan, program.id, program.name)
}

/** What the editor sheet is editing — a wrapper so "new program" has an identity of its own. */
private data class ProgramEditTarget(val id: String, val program: LiftProgramRow?)

// MARK: - Header

@Composable
private fun HeaderCard() {
    NoopCard(tint = Palette.effortColor) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            // iOS `HStack(spacing: NoopMetrics.rowSpacing)` — a literal 10, not a space index.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Metrics.space10)) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .background(Palette.effortColor.copy(alpha = 0.14f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.FitnessCenter,
                        contentDescription = null,
                        tint = Palette.effortColor,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(uiString(R.string.l10n_lift_your_log_book_2b997b1d), style = NoopType.headline, color = Palette.textPrimary)
                    Text(uiString(R.string.l10n_lift_programs_sessions_and_per_set_history_ac312b23), style = NoopType.caption, color = Palette.textSecondary)
                }
                Spacer(Modifier.weight(1f))
            }
            Text(
                uiString(R.string.l10n_lift_lifting_adds_volume_and_set_counts_it_ne_21334d67),
                style = NoopType.footnote,
                color = Palette.textSecondary,
            )
        }
    }
}

// MARK: - Programs

@Composable
private fun ProgramsSection(
    programs: List<LiftProgramRow>,
    loaded: Boolean,
    running: Boolean,
    onNew: () -> Unit,
    onEdit: (LiftProgramRow) -> Unit,
    onStart: (LiftProgramRow) -> Unit,
    onImport: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(uiString(R.string.l10n_lift_programs_ab14d0a7), overline = uiString(R.string.l10n_lift_saved_c0ae8f6e))

        when {
            !loaded -> NoopCard {
                Text(uiString(R.string.l10n_lift_reading_your_programs_3fe8568e), style = NoopType.footnote, color = Palette.textSecondary)
            }
            programs.isEmpty() -> NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(uiString(R.string.l10n_lift_no_programs_yet_17ed6491), style = NoopType.headline, color = Palette.textPrimary)
                    Text(
                        uiString(R.string.l10n_lift_a_program_is_a_name_and_an_ordered_list_813799b3),
                        style = NoopType.footnote,
                        color = Palette.textSecondary,
                    )
                }
            }
            else -> programs.forEach { program ->
                ProgramRow(
                    program = program,
                    running = running,
                    onClick = { onEdit(program) },
                    onStart = { onStart(program) },
                )
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
            NoopButton(
                text = uiString(R.string.l10n_lift_new_program_742c5122),
                leadingIcon = Icons.Filled.Add,
                kind = NoopButtonKind.Secondary,
                onClick = onNew,
            )
            // Filling a dozen exercise lines by hand on a phone is the most tedious thing in the feature; a
            // spreadsheet on a computer does it in a couple of minutes.
            NoopButton(
                text = uiString(R.string.l10n_lift_import_d6fbc9d2),
                leadingIcon = Icons.Filled.UploadFile,
                kind = NoopButtonKind.Secondary,
                onClick = onImport,
            )
        }
    }
}

@Composable
private fun ProgramRow(
    program: LiftProgramRow,
    running: Boolean,
    onClick: () -> Unit,
    onStart: () -> Unit,
) {
    NoopCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .plainClickable(onClick),
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Text(program.name, style = NoopType.headline, color = Palette.textPrimary)
                program.note?.takeIf { it.isNotEmpty() }?.let {
                    Text(it, style = NoopType.caption, color = Palette.textSecondary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Text(uiString(R.string.l10n_lift_tap_to_edit_2e920bf6), style = NoopType.footnote, color = Palette.textTertiary)
            }
            NoopButton(
                // "Running" rather than "Start" while a session is live: the button opens the session that
                // is already going instead of starting a second one.
                text = if (running) {
                    uiString(R.string.l10n_lift_running_73989d9c)
                } else {
                    uiString(R.string.l10n_lift_start_952f3754)
                },
                kind = NoopButtonKind.Primary,
                modifier = Modifier.width(110.dp),
                onClick = onStart,
            )
        }
    }
}

// MARK: - This week, per muscle

@Composable
private fun WeekSection(weekCounts: Map<LiftMuscle, Double>) {
    val ordered = LiftMuscle.ordered.filter { (weekCounts[it] ?: 0.0) > 0.0 }
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(uiString(R.string.l10n_lift_sets_per_muscle_2836360c), overline = uiString(R.string.l10n_lift_last_7_days_estimated_c109bfb9))
        if (ordered.isEmpty()) {
            NoopCard {
                Text(
                    uiString(R.string.l10n_lift_once_you_ve_logged_a_session_this_shows_3e906b7d),
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                )
            }
        } else {
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.screenRowSpacing)) {
                    ordered.forEach { muscle -> MuscleBar(muscle, weekCounts[muscle] ?: 0.0) }
                    // The band is named and sourced, never phrased as a target NOOP sets for anyone:
                    // this is not a medical device and does not prescribe.
                    Text(
                        uiString(R.string.l10n_lift_counted_from_the_muscles_you_assigned_ea_0b6c81b2),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}

/**
 * One muscle's week: the count, and where it sits relative to the evidence.
 *
 * This must never scale the bar 0…4 and turn it FULL and GREEN at four sets — that would say "done" at
 * the exact point the research says growth merely becomes *detectable*, telling the user to stop at the
 * starting line, and contradicting the caption printed directly below it. Four sets is a TICK a fifth of
 * the way along, and nothing on the bar ever reads as complete, because nothing about the dose is.
 */
@Composable
private fun MuscleBar(muscle: LiftMuscle, sets: Double) {
    val floor = LiftMetrics.ReferenceDose.hypertrophyMinimumSetsPerWeek
    val atOrAboveFloor = sets >= floor
    val label = if (atOrAboveFloor) {
        uiString(
            R.string.l10n_lift_sets_at_or_above_the_weekly_floor_of_073099ca,
            muscle.displayName,
            LiftFormat.trim(sets),
            LiftFormat.trim(floor),
        )
    } else {
        uiString(
            R.string.l10n_lift_sets_below_the_weekly_floor_of_d777f3a9,
            muscle.displayName,
            LiftFormat.trim(sets),
            LiftFormat.trim(floor),
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(muscle.displayName, style = NoopType.caption, color = Palette.textSecondary, modifier = Modifier.weight(1f))
            // Deliberately NOT a success colour. There is no success point to signal, and a green number
            // is exactly what made four sets read as an achievement.
            Text(LiftFormat.trim(sets), style = NoopType.captionNumber, color = Palette.textPrimary)
        }
        BoxWithConstraints(modifier = Modifier.fillMaxWidth().height(6.dp)) {
            val width = maxWidth
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(Palette.surfaceRaised),
            )
            Box(
                modifier = Modifier
                    // 2dp floor: a single fractional set still reads as a mark rather than a hairline.
                    .width(maxOf(2.dp, width * minOf(1.0, sets / WEEKLY_SETS_BAR_SPAN).toFloat()))
                    .height(6.dp)
                    .clip(CircleShape)
                    // Muted below the floor — below it growth is not reliably detectable, which is worth
                    // showing — and the ordinary accent above it. Never a "done" colour.
                    .background(Palette.effortColor.copy(alpha = if (atOrAboveFloor) 1f else 0.45f)),
            )
            Box(
                modifier = Modifier
                    .offset(x = maxOf(0.dp, width * minOf(1.0, floor / WEEKLY_SETS_BAR_SPAN).toFloat() - 1.dp))
                    .width(2.dp)
                    .height(6.dp)
                    .clip(CircleShape)
                    .background(Palette.textPrimary.copy(alpha = 0.45f)),
            )
        }
    }
}

// MARK: - History

@Composable
private fun HistorySection(history: List<LiftSessionRow>, onOpen: (LiftSessionRow) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(uiString(R.string.l10n_insights_screen_sessions_e11e37a9), overline = uiString(R.string.l10n_lift_recent_76eec760))
        if (history.isEmpty()) {
            NoopCard {
                Text(
                    uiString(R.string.l10n_lift_finished_sessions_land_here_with_every_s_8d848933),
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                )
            }
        } else {
            history.forEach { session -> HistoryRow(session, onClick = { onOpen(session) }) }
        }
    }
}

@Composable
private fun HistoryRow(session: LiftSessionRow, onClick: () -> Unit) {
    val stamp = remember(session.startTs) {
        DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(session.startTs * 1000L))
    }
    NoopCard {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Metrics.gap),
            modifier = Modifier
                .fillMaxWidth()
                .plainClickable(onClick),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    session.programName ?: uiString(R.string.l10n_lift_session_f7f1997c),
                    style = NoopType.headline,
                    color = Palette.textPrimary,
                )
                Text(stamp, style = NoopType.caption, color = Palette.textSecondary)
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = Palette.textSecondary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
