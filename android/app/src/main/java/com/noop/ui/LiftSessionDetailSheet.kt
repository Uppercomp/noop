package com.noop.ui

import com.noop.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noop.analytics.LiftMetrics
import com.noop.data.LiftMuscle
import com.noop.data.LiftSessionRow
import com.noop.data.LiftSetEntity
import com.noop.data.WorkoutRow
import com.noop.data.primary
import com.noop.data.secondaries
import com.noop.data.toMetricsRow
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

// One finished session, read back in full: every set as performed, the session figures, and how each
// exercise compares with the last time you did it.
//
// This is the screen the whole feature exists to produce. A log book that cannot show you what you lifted
// last week is a diary.
//
// EVERY FIGURE IS ARITHMETIC THE USER CAN REDO BY HAND from the sets listed on the same screen — that is
// the design constraint, and it is why there is no single composite "workout score". The maths lives in
// [LiftMetrics] (pure, unit-tested); this file only lays it out.
//
// Effort is shown BESIDE the lifting figures and is never computed from them: it is whatever NOOP measured
// from heart rate over the session's window, filled in by the engine's own rescore pass.
//
// Kotlin twin of `Strand/Screens/LiftSessionDetailSheet.swift`.

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiftSessionDetailSheet(
    vm: AppViewModel,
    session: LiftSessionRow,
    system: UnitSystem,
    onChanged: () -> Unit,
    onClose: () -> Unit,
) {
    val repo = vm.liftLog
    val scope = rememberCoroutineScope()

    var sets by remember { mutableStateOf<List<LiftSetEntity>>(emptyList()) }
    var workout by remember { mutableStateOf<WorkoutRow?>(null) }
    var previousVolume by remember { mutableStateOf<Map<String, Double>>(emptyMap()) }
    var sessionRpe by remember { mutableStateOf(session.sessionRpe) }
    var loaded by remember { mutableStateOf(false) }
    var confirmingDelete by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableStateOf(0) }

    LaunchedEffect(session.id, reloadKey) {
        val rows = repo.sets(session.id)
        sets = rows
        // Re-read rather than trusting the row this sheet was opened with: a correction elsewhere (or
        // the edit sheet) must be what the figures are computed from.
        sessionRpe = repo.sessions(session.deviceId, session.startTs, session.startTs)
            .firstOrNull { it.id == session.id }?.sessionRpe ?: session.sessionRpe

        // The `workout` row this session is pinned to, by that table's own natural key.
        workout = vm.repo.workouts(session.deviceId, session.startTs - 1, session.startTs + 1, limit = 10)
            .firstOrNull { it.startTs == session.startTs && it.sport == session.sport }

        // Previous volume per exercise, for the "vs last time" line.
        val previous = HashMap<String, Double>()
        for (name in rows.filter { LiftMetrics.isPerformed(it.reps) }.map { it.exercise }.distinct()) {
            val before = repo.lastSets(session.deviceId, name, before = session.startTs)
            LiftMetrics.volumeLoadKg(before.map { it.toMetricsRow() })?.let { previous[name] = it }
        }
        previousVolume = previous
        loaded = true
    }

    val durationSec = session.endTs?.let { maxOf(0L, it - session.startTs).toInt() } ?: 0
    // The sets that were performed. A set at zero reps was discarded or skipped: it stays in the store so
    // Edit sets can fill it in, and out of everything this screen shows.
    val performed = sets.filter { LiftMetrics.isPerformed(it.reps) }
    val workingSetCount = performed.count { !it.isWarmup }

    // Asked BEFORE the detail content is emitted. A sheet's content is a Column with a bounded height, so
    // emitting this screen AND the edit sheet leaves the second one zero height — it renders, is laid out
    // at nothing, and the button looks dead. (The same shape works in `LiftSessionScreen` because its
    // finish branch returns before the session content.)
    if (editing) {
        LiftSessionEditSheet(
            vm = vm,
            session = session,
            system = system,
            onSaved = {
                // The edits rewrite the rows this screen is showing, so everything is recomputed from them
                // rather than patched in place.
                reloadKey++
                onChanged()
            },
            onClose = { editing = false },
        )
        return
    }

    ScreenScaffold(
        title = uiString(R.string.l10n_live_screen_session_f7f1997c),
        subtitle = sessionSubtitle(session),
        leading = { SheetTextLink(uiString(R.string.l10n_components_close_bbfa773e), onClick = onClose) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.sectionGap)) {
            if (!loaded) {
                NoopCard {
                    Text(uiString(R.string.l10n_lift_reading_the_session_75cf8f06), style = NoopType.footnote, color = Palette.textSecondary)
                }
            } else {
                // MARK: Figures
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    SectionHeader(uiString(R.string.l10n_lift_this_session_48c2a13d), overline = uiString(R.string.l10n_lift_figures_55c78353))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
                        verticalArrangement = Arrangement.spacedBy(Metrics.space10),
                        maxItemsInEachRow = 2,
                    ) {
                        // Weight x reps, summed. Exact, but only meaningful against the SAME program run
                        // again: 100 kg of leg press is not 100 kg of squat, so the total across different
                        // exercises compares nothing. The per-exercise figure below, with its delta
                        // against last time, is the form that answers "am I progressing" — this one is the
                        // tally.
                        DetailTile(
                            label = uiString(R.string.l10n_lift_volume_3b18e8e3),
                            value = LiftFormat.weight(
                                LiftMetrics.volumeLoadKg(performed.map { it.toMetricsRow() }),
                                system = system,
                            ),
                            caption = uiString(
                                R.string.l10n_lift_lld_working_sets_compare_when_you_repeat_db0bf3b3,
                                workingSetCount,
                            ),
                            modifier = Modifier.weight(1f),
                        )
                        DetailTile(
                            label = uiString(R.string.l10n_lift_session_load_e1a45588),
                            value = LiftMetrics.sessionLoad(sessionRpe, durationSec)?.let { "${it.toInt()}" } ?: "—",
                            caption = sessionLoadCaption(sessionRpe, durationSec).text(),
                            modifier = Modifier.weight(1f),
                        )
                        DetailTile(
                            label = uiString(R.string.l10n_lift_effort_8c974bc6),
                            // Measured from heart rate over the session's window, never from the sets.
                            value = workout?.strain?.let { LiftFormat.trim(it) } ?: "—",
                            caption = uiString(R.string.l10n_lift_measured_from_heart_rate_65b504c3),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }

                // MARK: Per exercise, with every set
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    SectionHeader(uiString(R.string.l10n_lift_exercises_4dc503da), overline = uiString(R.string.l10n_lift_as_performed_96ca5018))
                    if (performed.isEmpty()) {
                        NoopCard {
                            Text(
                                uiString(R.string.l10n_lift_no_sets_were_performed_discarded_sets_st_ab3cf222),
                                style = NoopType.footnote,
                                color = Palette.textSecondary,
                            )
                        }
                    }
                    LiftMetrics.perExercise(performed.map { it.toMetricsRow() }).forEach { summary ->
                        ExerciseSummaryCard(
                            summary = summary,
                            rows = performed.filter { it.exercise == summary.exercise }.sortedBy { it.ord },
                            previous = previousVolume[summary.exercise],
                            system = system,
                        )
                    }
                }

                if (performed.isNotEmpty()) {
                    MuscleCountsForSession(performed)
                    RpeProfileCard(performed)
                }

                Text(
                    uiString(R.string.l10n_lift_lifting_figures_are_worked_out_from_the_aa980e85),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )

                NoopButton(
                    text = uiString(R.string.l10n_lift_edit_sets_5ef5fa0f),
                    kind = NoopButtonKind.Secondary,
                    fullWidth = true,
                    onClick = { editing = true },
                )

                // MARK: Delete
                //
                // Removes the paired `workout` row TOO. A lift session writes one so the training lands in
                // Workouts and Today like any other, and the analytics engine fills its strain from the
                // heart rate measured over that window. Leaving it behind would keep the day's Effort
                // inflated by a session the user just said did not happen — which is worse than not being
                // able to delete at all, because it would look like the delete worked.
                NoopButton(
                    text = uiString(R.string.ground_truth_delete_session),
                    leadingIcon = Icons.Outlined.Delete,
                    kind = NoopButtonKind.Secondary,
                    fullWidth = true,
                    onClick = { confirmingDelete = true },
                )
            }
        }
    }

    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(uiString(R.string.ground_truth_delete_session_title), style = NoopType.headline, color = Palette.textPrimary) },
            text = {
                Text(
                    uiString(
                        R.string.l10n_lift_lld_recorded_sets_will_be_removed_and_so_440497d2,
                        performed.count { !it.isWarmup },
                    ),
                    style = NoopType.body,
                    color = Palette.textSecondary,
                )
            },
            confirmButton = {
                SheetTextLink(uiString(R.string.ground_truth_delete_confirm), color = Palette.statusCritical) {
                    confirmingDelete = false
                    scope.launch {
                        repo.deleteSession(session.id)          // cascades to its sets
                        workout?.let { vm.repo.deleteWorkout(it) }
                        onChanged()
                        onClose()
                    }
                }
            },
            dismissButton = { SheetTextLink(uiString(R.string.ground_truth_cancel)) { confirmingDelete = false } },
            containerColor = Palette.surfaceOverlay,
        )
    }
}

private fun sessionSubtitle(session: LiftSessionRow): String {
    val date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(session.startTs * 1000L))
    val name = session.programName
    return if (!name.isNullOrEmpty()) "$name · $date" else date
}

/**
 * One session figure.
 *
 * Its own tile rather than the dashboard [StatTile]: that one pins an exact 96dp height and a 26sp value
 * because it anchors a sparkline row, while iOS's session tiles are content-sized at title2 in an adaptive
 * grid. Same numbers, different job.
 */
@Composable
private fun DetailTile(label: String, value: String, caption: String, modifier: Modifier = Modifier) {
    NoopCard(modifier = modifier, padding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Overline(label)
            Text(
                value,
                style = NoopType.title2,
                color = Palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(caption, style = NoopType.caption, color = Palette.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The caption must show the SAME minutes the load was computed from.
 *
 * Showing whole minutes while computing from exact seconds silently breaks the one promise this screen
 * makes: that every figure is arithmetic you can redo by hand. A 2:40 session captioned "× 2 min" invites
 * the reader to check 8 × 2 = 16 against a displayed 21 and conclude the app is making numbers up.
 */
internal fun sessionLoadCaption(rpe: Double?, durationSec: Int): DisplayText {
    if (rpe == null) return DisplayText.Resource(R.string.l10n_lift_not_rated_58efc7ea)
    val minutes = durationSec / 60.0
    return DisplayText.Resource(
        R.string.l10n_lift_rpe_min_220638b9,
        listOf(LiftFormat.trim(rpe), LiftFormat.trim(minutes)),
    )
}

@Composable
private fun ExerciseSummaryCard(
    summary: LiftMetrics.ExerciseSummary,
    rows: List<LiftSetEntity>,
    previous: Double?,
    system: UnitSystem,
) {
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
            Text(summary.exercise, style = NoopType.headline, color = Palette.textPrimary)
            rows.firstOrNull()?.let { first ->
                Text(
                    LiftMuscleSummary.line(first.primary, first.secondaries),
                    style = NoopType.caption,
                    color = Palette.textTertiary,
                )
            }

            rows.forEach { row -> SetLine(row, system) }

            HorizontalDivider(color = Palette.textTertiary.copy(alpha = 0.2f))

            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Overline(uiString(R.string.l10n_lift_best_set_98b3e530))
                    Text(bestSetText(summary, system).text(), style = NoopType.bodyNumber, color = Palette.textPrimary)
                    summary.bestEstimatedOneRepMaxKg?.let { e1rm ->
                        // "Estimated" is in the label, not a footnote: it is a formula off one set, not a
                        // measured maximum, and the word has to travel with it.
                        Text(
                            uiString(
                                R.string.l10n_lift_estimated_1rm_2e9e5fa9,
                                LiftFormat.weight(e1rm, system = system),
                            ),
                            style = NoopType.caption,
                            color = Palette.textTertiary,
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Overline(uiString(R.string.l10n_lift_volume_3b18e8e3))
                    Text(
                        LiftFormat.weight(summary.volumeKg, system = system),
                        style = NoopType.bodyNumber,
                        color = Palette.textPrimary,
                    )
                    // How this exercise's volume compares with the last session that included it. The
                    // single most useful line on the screen: progression is a comparison, not a number.
                    volumeDeltaText(summary, previous, system)?.let { delta ->
                        Text(delta.text(), style = NoopType.caption, color = deltaColor(summary, previous))
                    }
                }
            }
        }
    }
}

@Composable
private fun SetLine(row: LiftSetEntity, system: UnitSystem) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (row.isWarmup) uiString(R.string.l10n_lift_w_e2415cb7) else "${row.setIndex}",
            style = NoopType.captionNumber,
            color = if (row.isWarmup) Palette.textTertiary else Palette.effortColor,
            modifier = Modifier.width(18.dp),
        )
        Text(
            setValueText(row, system).text(),
            style = NoopType.bodyNumber,
            color = if (row.isWarmup) Palette.textSecondary else Palette.textPrimary,
        )
        Spacer(Modifier.weight(1f))
        row.rpe?.let {
            Text(
                uiString(R.string.l10n_lift_rpe_9588de8b, LiftFormat.trim(it)),
                style = NoopType.caption,
                color = Palette.textSecondary,
            )
        }
        row.restSec?.let {
            Text(LiftFormat.duration(it), style = NoopType.caption, color = Palette.textTertiary)
        }
    }
}

internal fun setValueText(row: LiftSetEntity, system: UnitSystem): DisplayText {
    val reps = row.reps?.toString() ?: "—"
    val kg = row.weightKg
    // Bodyweight work: reps alone, with no fabricated tonnage behind it.
    if (kg == null) return DisplayText.Resource(R.string.l10n_lift_reps_515a3a8f, listOf(reps))
    return DisplayText.Dynamic("${LiftFormat.weight(kg, system = system)} × $reps")
}

internal fun bestSetText(summary: LiftMetrics.ExerciseSummary, system: UnitSystem): DisplayText {
    val reps = summary.bestReps?.toString() ?: return DisplayText.Dynamic("—")
    val kg = summary.bestWeightKg ?: return DisplayText.Resource(R.string.l10n_lift_reps_515a3a8f, listOf(reps))
    return DisplayText.Dynamic("${LiftFormat.weight(kg, system = system)} × $reps")
}

internal fun volumeDeltaText(
    summary: LiftMetrics.ExerciseSummary,
    previous: Double?,
    system: UnitSystem,
): DisplayText? {
    val now = summary.volumeKg ?: return null
    val before = previous ?: return null
    if (before <= 0.0) return null
    val delta = now - before
    if (kotlin.math.abs(delta) < 0.5) {
        return DisplayText.Resource(R.string.l10n_lift_same_as_last_time_ac5840c1)
    }
    val sign = if (delta > 0) "+" else "−"
    // "… vs last time" is plain interpolation on the Apple side and is not in its String Catalog, so it
    // stays unlocalised here — the words around the number match because they match there.
    return DisplayText.Dynamic("$sign${LiftFormat.weight(kotlin.math.abs(delta), system = system)} vs last time")
}

internal fun deltaColor(summary: LiftMetrics.ExerciseSummary, previous: Double?): Color {
    val now = summary.volumeKg ?: return Palette.textTertiary
    val before = previous ?: return Palette.textTertiary
    if (before <= 0.0) return Palette.textTertiary
    return when {
        now > before -> Palette.statusPositive
        now < before -> Palette.textSecondary
        else -> Palette.textTertiary
    }
}

// MARK: - Sets per muscle

@Composable
private fun MuscleCountsForSession(performed: List<LiftSetEntity>) {
    val counts = LiftMetrics.muscleCounts(performed.map { it.toMetricsRow() })
    val ordered = LiftMuscle.ordered.filter { (counts.fractional[it] ?: 0.0) > 0.0 }
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(uiString(R.string.l10n_lift_sets_per_muscle_2836360c), overline = uiString(R.string.l10n_lift_this_session_estimated_d27b04f1))
        if (ordered.isEmpty()) {
            NoopCard {
                Text(
                    uiString(R.string.l10n_lift_none_of_these_exercises_has_a_muscle_gro_755517c6),
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                )
            }
        } else {
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ordered.forEach { muscle ->
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(muscle.displayName, style = NoopType.body, color = Palette.textPrimary)
                            Spacer(Modifier.weight(1f))
                            Text(
                                LiftFormat.trim(counts.fractional[muscle] ?: 0.0),
                                style = NoopType.bodyNumber,
                                color = Palette.effortColor,
                            )
                            Text(
                                componentText(counts, muscle).text(),
                                style = NoopType.caption,
                                color = Palette.textTertiary,
                            )
                        }
                    }
                    Text(
                        uiString(R.string.l10n_lift_counted_from_the_muscles_you_assigned_ea_2a476329),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/** "4 direct · 2 indirect" — so the total above is inspectable rather than asserted. */
internal fun componentText(counts: LiftMetrics.MuscleCounts, muscle: LiftMuscle): DisplayText {
    val direct = counts.direct[muscle] ?: 0
    val indirect = counts.indirect[muscle] ?: 0
    return when {
        direct > 0 && indirect > 0 ->
            DisplayText.Resource(R.string.l10n_lift_lld_direct_lld_indirect_7b1cc7c4, listOf(direct, indirect))
        direct > 0 -> DisplayText.Resource(R.string.l10n_lift_lld_direct_741a1af5, listOf(direct))
        else -> DisplayText.Resource(R.string.l10n_lift_lld_indirect_f24bf95b, listOf(indirect))
    }
}

// MARK: - RPE profile

@Composable
private fun RpeProfileCard(performed: List<LiftSetEntity>) {
    val profile = LiftMetrics.rpeProfile(performed.map { it.toMetricsRow() })
    Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(
            uiString(R.string.l10n_lift_how_hard_it_felt_28d7069a),
            overline = uiString(R.string.l10n_lift_rpe_b845d311),
        )
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ProfileRow(uiString(R.string.l10n_lift_mean_rpe_c6bca439), profile.mean?.let { LiftFormat.trim(it) } ?: "—")
                ProfileRow(
                    uiString(
                        R.string.l10n_lift_sets_at_rpe_or_above_eb4af844,
                        LiftFormat.trim(profile.threshold),
                    ),
                    "${profile.setsAtOrAboveThreshold}",
                )
                if (profile.unratedSets > 0) {
                    Text(
                        uiString(
                            R.string.l10n_lift_lld_working_sets_weren_t_rated_so_the_me_dcc7f7cf,
                            profile.unratedSets,
                            profile.ratedSets,
                        ),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileRow(label: String, value: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = NoopType.body, color = Palette.textSecondary)
        Spacer(Modifier.weight(1f))
        Text(value, style = NoopType.bodyNumber, color = Palette.textPrimary)
    }
}
