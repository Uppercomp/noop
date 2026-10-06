package com.noop.ui

import com.noop.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noop.data.LiftExerciseRow
import com.noop.data.LiftLogRepository
import com.noop.data.LiftMuscle
import com.noop.data.LiftVocabularyFullException
import com.noop.data.primary
import com.noop.data.secondaries
import kotlinx.coroutines.launch

// What the two exercise pickers share: the program line editor and (later) the running session's Add
// exercise sheet. The user's own exercise names offered back, a name remembered with its muscles, and the
// muscle classification itself — one copy of each, so the two pickers cannot drift about what a name is
// or which muscles it works.
//
// Kotlin twin of `Strand/Screens/LiftExercisePicking.swift`.

/**
 * The user's own exercise names (`liftExercise`), as the pickers read and write them.
 *
 * NOOP SHIPS NO EXERCISE CATALOGUE — deliberately. The user types whatever they call the movement and it
 * is remembered here with the muscle group they gave it, then offered back next time. A shipped mapping
 * of common exercises to muscles would be both a permanent maintenance burden and a correctness claim
 * NOOP has no business making about someone else's technique.
 */
object LiftExerciseVocabulary {

    /**
     * Names matching what has been typed so far, minus an exact match (no point suggesting the thing
     * already in the box), most recently used first as the store orders them. Capped — this is a hint,
     * not a browser.
     */
    fun suggestions(
        vocabulary: List<LiftExerciseRow>,
        matching: String,
        limit: Int = 6,
    ): List<LiftExerciseRow> {
        val query = matching.trim().lowercase()
        if (query.isEmpty()) return vocabulary.take(limit)
        return vocabulary
            .filter { it.name.lowercase().contains(query) && it.name.lowercase() != query }
            .take(limit)
    }

    /**
     * Remember [name] with its muscles, so it is offered back next time. The store's upsert is keyed on
     * `(deviceId, name)`, so a known name is updated — its muscles, and when it was last used — never
     * duplicated.
     *
     * @throws LiftVocabularyFullException for a NEW name once the vocabulary is full, which the caller
     *   explains rather than dropping the name silently.
     */
    suspend fun remember(
        repo: LiftLogRepository,
        deviceId: String,
        name: String,
        primary: LiftMuscle?,
        secondaries: List<LiftMuscle>,
        known: List<LiftExerciseRow>,
        now: Long = System.currentTimeMillis() / 1000L,
    ) {
        val existing = known.firstOrNull { it.name == name }
        repo.rememberExercises(
            listOf(
                com.noop.data.liftExerciseEntity(
                    id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    deviceId = deviceId,
                    name = name,
                    primaryMuscle = primary,
                    secondaryMuscles = secondaries,
                    createdAt = existing?.createdAt ?: now,
                    lastUsedTs = now,
                ),
            ),
        )
    }

    /**
     * Secondaries in the vocabulary's canonical order rather than a `Set`'s iteration order, so the stored
     * list is stable between saves instead of reshuffling on every edit.
     */
    fun ordered(secondaries: Set<LiftMuscle>, excluding: LiftMuscle?): List<LiftMuscle> =
        LiftMuscle.ordered.filter { it in secondaries && it != excluding }
}

/** One remembered exercise as a picker lists it: its name, and the muscles it is known by. */
@Composable
fun LiftExerciseSuggestionLabel(row: LiftExerciseRow, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Text(row.name, style = NoopType.body, color = Palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            LiftMuscleSummary.line(row.primary, row.secondaries),
            style = NoopType.caption,
            color = Palette.textTertiary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The muscle classification: the primary muscle (a direct set) and the muscles an exercise also works
 * (half a set each). Asked once per exercise and remembered with its name; leaving it unset is allowed —
 * an unclassified exercise still counts toward volume and session load, it simply claims no muscle it was
 * never assigned.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LiftMusclePicker(
    primary: LiftMuscle?,
    onPrimary: (LiftMuscle?) -> Unit,
    secondaries: Set<LiftMuscle>,
    onSecondaries: (Set<LiftMuscle>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
        SectionHeader(uiString(R.string.l10n_lift_muscles_18dba71d), overline = uiString(R.string.l10n_lift_counted_once_per_exercise_f3949e61))
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Overline(uiString(R.string.l10n_lift_primary_a9a96ec0))
                    PrimaryMuscleMenu(
                        primary = primary,
                        // Setting a primary that is also ticked as a secondary drops it from the
                        // secondaries: one muscle can never be credited twice for the same set.
                        onSelect = { muscle ->
                            onPrimary(muscle)
                            if (muscle != null && muscle in secondaries) onSecondaries(secondaries - muscle)
                        },
                    )
                }

                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Overline(uiString(R.string.l10n_lift_also_works_counted_as_half_a_set_bdd2bb65))
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        // iOS uses `GridItem(.adaptive(minimum: 104))`, which lands on three columns in
                        // a phone-width card; three per row with a `weight` reproduces both the count and
                        // the way each chip stretches to fill its column.
                        maxItemsInEachRow = 3,
                    ) {
                        LiftMuscle.ordered.forEach { muscle ->
                            if (muscle != primary) {
                                SecondaryMuscleChip(
                                    muscle = muscle,
                                    on = muscle in secondaries,
                                    modifier = Modifier.weight(1f),
                                    onToggle = {
                                        onSecondaries(
                                            if (muscle in secondaries) secondaries - muscle
                                            else secondaries + muscle,
                                        )
                                    },
                                )
                            }
                        }
                    }
                }

                Text(
                    uiString(R.string.l10n_lift_direct_sets_count_once_indirect_sets_cou_347a1124),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                )
            }
        }
    }
}

/** The primary muscle, chosen from a menu grouped by region — the twin of iOS's `Menu` + `Section`s. */
@Composable
private fun PrimaryMuscleMenu(primary: LiftMuscle?, onSelect: (LiftMuscle?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val label = primary?.displayName ?: uiString(R.string.l10n_lift_not_classified_ae925ff0)
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable { open = true }
                .padding(vertical = 6.dp)
                .semantics { contentDescription = uiString(R.string.l10n_lift_primary_muscle_e8e3ec53) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = NoopType.body,
                color = if (primary == null) Palette.textTertiary else Palette.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.KeyboardArrowDown,
                contentDescription = null,
                tint = Palette.textTertiary,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text(uiString(R.string.l10n_lift_not_classified_ae925ff0), style = NoopType.body, color = Palette.textPrimary) },
                onClick = {
                    onSelect(null)
                    open = false
                },
            )
            LiftMuscle.Region.entries.forEach { region ->
                MuscleMenuSectionHeader(region.displayName)
                LiftMuscle.inRegion(region).forEach { muscle ->
                    DropdownMenuItem(
                        text = { Text(muscle.displayName, style = NoopType.body, color = Palette.textPrimary) },
                        onClick = {
                            onSelect(muscle)
                            open = false
                        },
                    )
                }
            }
        }
    }
}

/** A non-clickable section label inside the muscle menu. */
@Composable
private fun MuscleMenuSectionHeader(text: String) {
    Text(
        text,
        style = NoopType.overline,
        color = Palette.textTertiary,
        modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun SecondaryMuscleChip(
    muscle: LiftMuscle,
    on: Boolean,
    modifier: Modifier = Modifier,
    onToggle: () -> Unit,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (on) Palette.effortColor.copy(alpha = 0.14f) else Palette.surfaceRaised)
            .clickable(onClick = onToggle)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            muscle.displayName,
            style = NoopType.caption,
            color = if (on) Palette.effortColor else Palette.textSecondary,
        )
    }
}

/**
 * Add an exercise the RUNNING SESSION does not have — the same name field, remembered vocabulary and muscle
 * classification as the program line editor, because a movement is classified once and the two must not
 * disagree about what a name is.
 *
 * Kotlin twin of `Strand/Screens/LiftSessionExerciseSheet.swift`. It writes nothing itself: the parent
 * (`LiftSessionController.addExercise`) owns the session, and finishing asks whether the program keeps the
 * new line.
 */
@Composable
fun LiftSessionExerciseSheet(
    repo: LiftLogRepository,
    deviceId: String,
    onCancel: () -> Unit,
    onAdd: (name: String, primary: LiftMuscle?, secondaries: List<LiftMuscle>) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var exercise by remember { mutableStateOf("") }
    var primary by remember { mutableStateOf<LiftMuscle?>(null) }
    var secondaries by remember { mutableStateOf<Set<LiftMuscle>>(emptySet()) }
    val vocabulary = remember { mutableStateListOf<LiftExerciseRow>() }
    var vocabularyFullLimit by remember { mutableStateOf<Int?>(null) }

    LaunchedEffect(Unit) {
        vocabulary.clear()
        vocabulary.addAll(repo.exercises(deviceId))
    }

    val suggestions = LiftExerciseVocabulary.suggestions(vocabulary, exercise)

    ScreenScaffold(
        title = uiString(R.string.l10n_lift_add_exercise_64404808),
        subtitle = uiString(R.string.l10n_lift_type_any_name_you_like_noop_remembers_it_6a414fde),
        leading = { SheetTextLink(uiString(R.string.ground_truth_cancel), onClick = onCancel) },
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
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .plainClickable {
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

            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                NoopButton(
                    text = uiString(R.string.l10n_add_device_wizard_add_61cc55aa),
                    kind = NoopButtonKind.Primary,
                    enabled = exercise.trim().isNotEmpty(),
                    modifier = Modifier.width(160.dp),
                    onClick = {
                        val name = exercise.trim()
                        scope.launch {
                            // Remembered with the same call the program editor uses, so the classification
                            // is a one-time answer whichever screen asked for it.
                            try {
                                LiftExerciseVocabulary.remember(
                                    repo = repo,
                                    deviceId = deviceId,
                                    name = name,
                                    primary = primary,
                                    secondaries = LiftExerciseVocabulary.ordered(secondaries, primary),
                                    known = vocabulary,
                                )
                            } catch (full: LiftVocabularyFullException) {
                                vocabularyFullLimit = full.limit
                                return@launch
                            }
                            onAdd(name, primary, LiftExerciseVocabulary.ordered(secondaries, primary))
                        }
                    },
                )
            }
        }
    }

    vocabularyFullLimit?.let {
        androidx.compose.material3.AlertDialog(
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
