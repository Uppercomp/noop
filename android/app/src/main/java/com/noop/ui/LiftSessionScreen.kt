package com.noop.ui

import com.noop.R
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.RemoveCircleOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.data.LiftLogRepository
import com.noop.data.LiftPlanItem
import com.noop.data.LiftSessionEngine
import com.noop.data.LiftSetCarry
import com.noop.data.LiftSlot

// The workout sheet: every exercise and every set of the session, on one scrollable page.
//
// WHY A SHEET AND NOT A WIZARD. The first version showed one set at a time and walked the plan in order.
// In a real gym that fails twice over: you cannot see what is coming, and you cannot move on when a
// machine is occupied. So every set is a row, any pending row can be started, and finished rows stay on
// screen with what you lifted.
//
// COLOUR CARRIES STATE, so you can find your place at a glance from arm's length:
//   green   the set you are working now
//   amber   the rest that follows it
//   done    a completed set, with a check; numbers nobody typed stay grey
//
// The session itself lives in `LiftSessionController`, ABOVE this screen. Dismissing this sheet
// minimises it to the bottom bar; the clock, the strap gesture and the buzzes all keep running, because
// a workout outlives the screen you happen to be looking at.
//
// Kotlin twin of `Strand/Screens/LiftSessionView.swift`.

/**
 * The session sheet body. Hosted inside a [androidx.compose.material3.ModalBottomSheet] by
 * [LiftLogScreen].
 *
 * @param onFinished called once the session has been written, so the hub can reload.
 */
@Composable
fun LiftSessionScreen(
    vm: AppViewModel,
    session: LiftSessionController,
    deviceId: String,
    system: UnitSystem,
    onClose: () -> Unit,
    onFinished: () -> Unit,
) {
    // Read so this screen recomposes on every session mutation. The engine is a mutable class whose
    // reference never changes, so reading `session.engine` alone would never fire — `mutate` is what
    // bumps this counter (see `LiftSessionController.mutate`).
    @Suppress("UNUSED_VARIABLE")
    val revision = session.revision
    val engine = session.engine

    var showingFinish by remember { mutableStateOf(false) }

    if (engine == null) {
        // Nothing running. iOS shows `ComingSoon(what: "No session running", symbol: "dumbbell")` here —
        // a card with that same title and line — so this is the same card, same words.
        ScreenScaffold(
            title = uiString(R.string.l10n_live_screen_session_f7f1997c),
            leading = { SheetTextLink(uiString(R.string.l10n_components_close_bbfa773e), onClick = onClose) },
        ) {
            NoSessionCard()
        }
        return
    }

    if (showingFinish) {
        LiftFinishSheet(
            vm = vm,
            session = session,
            deviceId = deviceId,
            onCancel = { showingFinish = false },
            onFinished = onFinished,
            onClose = onClose,
        )
        return
    }

    val repo = vm.liftLog
    // What the user has TYPED into a field, held until they leave it.
    //
    // Without this a numeric field cannot accept a decimal at all. Each binding read its text back out
    // of the engine, so every keystroke round-tripped through `LiftFormat` and was replaced by the
    // canonical rendering of the parsed value: typing "45." parsed to 45, re-rendered as "45", and the
    // point vanished as it was typed — then the next keystroke made "455". A user entering 45.5 kg
    // silently got 455 kg.
    //
    // So while a field is focused it shows exactly what was typed; the parsed value still goes to the
    // engine and to disk on every keystroke, so nothing about durability changes. The draft is dropped
    // when focus leaves and the row goes back to the canonical formatting.
    val draft = remember { mutableStateMapOf<FieldKey, String>() }
    val positions = remember { mutableStateMapOf<Int, Int>() }
    val scroll = rememberScrollState()
    val focus = LocalFocusManager.current
    var scrollTarget by remember { mutableStateOf<Int?>(null) }
    var addingExercise by remember { mutableStateOf(false) }

    // Re-read whenever the session's exercises change, so an exercise added mid-session that was done
    // before shows last time's numbers in grey, like every other line.
    val exerciseNames = engine.plan.map { it.exercise }
    LaunchedEffect(exerciseNames) { loadLastTime(repo, session, deviceId, engine) }

    // Follow the session down the sheet, but only when it moves on its own — scrolling back to read an
    // earlier exercise must not be yanked away from.
    val currentIndex = engine.currentSlot?.exerciseIndex
    LaunchedEffect(currentIndex) {
        if (currentIndex != null) positions[currentIndex]?.let { scroll.animateScrollTo(it) }
    }
    LaunchedEffect(scrollTarget) {
        val target = scrollTarget
        if (target != null) {
            positions[target]?.let { scroll.animateScrollTo(it) }
            scrollTarget = null
        }
    }

    Column(
        modifier = Modifier
            .fillMaxHeight()
            // iOS's `.dismissesKeyboardOnTap`: a tap on the sheet's background puts the keyboard away,
            // which also releases the field's draft so the row re-renders canonically. Without it the only
            // way out of the keyboard is BACK — and BACK dismisses the whole sheet, minimising a session
            // the user was in the middle of.
            .pointerInput(Unit) { detectTapGestures { focus.clearFocus() } },
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(scroll)
                .padding(horizontal = Metrics.screenPadding)
                .padding(top = 18.dp),
            verticalArrangement = Arrangement.spacedBy(Metrics.sectionGap),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    session.programName ?: uiString(R.string.l10n_lift_session_f7f1997c),
                    style = NoopType.title1,
                    color = Palette.textPrimary,
                )
                Text(
                    uiString(
                        R.string.l10n_lift_lld_of_lld_sets_done_d9efeb33,
                        engine.completedWorkingSets,
                        engine.plannedWorkingSets,
                    ),
                    style = NoopType.caption,
                    color = Palette.textSecondary,
                )
            }

            engine.plan.forEachIndexed { index, item ->
                ExerciseCard(
                    session = session,
                    engine = engine,
                    index = index,
                    item = item,
                    system = system,
                    draft = draft,
                    onPositioned = { positions[index] = it },
                )
            }

            AddExerciseRow(
                canAdd = engine.plan.size < LiftSessionEngine.maxExercises,
                onClick = { addingExercise = true },
            )
            Spacer(Modifier.height(8.dp))
        }

        ControlBar(vm = vm, session = session, engine = engine, onFinish = { showingFinish = true })
    }

    if (addingExercise) {
        LiftSessionExerciseSheet(
            repo = repo,
            deviceId = deviceId,
            onCancel = { addingExercise = false },
            onAdd = { name, primary, secondaries ->
                addingExercise = false
                if (session.addExercise(name, primary, secondaries)) {
                    // Bring the new card into view: it was appended, so it is the last one.
                    scrollTarget = (session.engine?.plan?.size ?: 1) - 1
                }
            },
        )
    }
}

/** Which field of which set a draft belongs to. */
private sealed interface FieldKey {
    data class Weight(val slot: LiftSlot) : FieldKey
    data class Reps(val slot: LiftSlot) : FieldKey
    data class Rpe(val slot: LiftSlot) : FieldKey
}

// MARK: - One exercise, with all its sets

@Composable
private fun ExerciseCard(
    session: LiftSessionController,
    engine: LiftSessionEngine,
    index: Int,
    item: LiftPlanItem,
    system: UnitSystem,
    draft: MutableMap<FieldKey, String>,
    onPositioned: (Int) -> Unit,
) {
    NoopCard(modifier = Modifier.onGloballyPositioned { onPositioned(it.positionInParent().y.toInt()) }) {
        Column(
            // iOS `VStack(spacing: NoopMetrics.rowSpacing)` — a literal 10, not a space index.
            verticalArrangement = Arrangement.spacedBy(Metrics.space10),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(item.exercise, style = NoopType.headline, color = Palette.textPrimary)
                Text(
                    LiftMuscleSummary.line(item.primaryMuscle, item.secondaryMuscles),
                    style = NoopType.caption,
                    color = Palette.textTertiary,
                )
            }

            item.note?.takeIf { it.isNotEmpty() }?.let { note ->
                Text(
                    note,
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                    // Belt and braces with the entry cap: the sets are what this screen is for, and a
                    // note must never be able to push them off it.
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Palette.metricAmber.copy(alpha = 0.12f))
                        .padding(10.dp),
                )
            }

            ColumnHeadings(system)

            engine.slotsForExercise(index).forEach { slot ->
                SetRow(session, engine, slot, system, draft)
                // The rest belongs BETWEEN two sets, because that is where it happens.
                if (isRestingAfter(engine, slot)) RestBand(engine)
            }

            SetCountRow(session, engine, index, item)
        }
    }
}

/** Width of the set-number column, shared by the heading and every row so the number sits under its label. */
private val SetColumnWidth = 34.dp

/** Width of the trailing tick column, mirrored by a spacer in the heading row. */
private val TickColumnWidth = 30.dp

@Composable
private fun ColumnHeadings(system: UnitSystem) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.width(SetColumnWidth), contentAlignment = Alignment.Center) { Overline(uiString(R.string.l10n_lift_set_448ab73b)) }
        Overline(if (system == UnitSystem.IMPERIAL) uiString(R.string.l10n_lift_lb_75ebff0d) else uiString(R.string.l10n_lift_kg_aee5f633), modifier = Modifier.weight(1f))
        Overline(uiString(R.string.l10n_lift_reps_702045f3), modifier = Modifier.weight(1f))
        Overline(uiString(R.string.l10n_lift_rpe_b845d311), modifier = Modifier.weight(1f))
        Spacer(Modifier.width(TickColumnWidth))
    }
}

// MARK: - One set row

@Composable
private fun SetRow(
    session: LiftSessionController,
    engine: LiftSessionEngine,
    slot: LiftSlot,
    system: UnitSystem,
    draft: MutableMap<FieldKey, String>,
) {
    val recorded = engine.recordedSet(slot)
    val isWorking = engine.stage == LiftSessionEngine.Stage.Working(slot)
    val warmup = session.isWarmup(slot)
    val entered = session.enteredValues(slot)

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(rowBackground(isWorking = isWorking, done = recorded != null))
            .padding(vertical = 6.dp, horizontal = 8.dp),
    ) {
        // The set number IS the warm-up toggle. Warm-ups are excluded from volume and from the
        // per-muscle counts, so being unable to mark one silently inflates the single figure the whole
        // feature rests on — it has to be reachable in one tap, without leaving the row.
        Box(
            modifier = Modifier
                .width(SetColumnWidth)
                .clickable { session.setWarmup(slot, !warmup) }
                .semantics {
                    contentDescription = if (warmup) {
                        uiString(R.string.l10n_lift_warm_up_set_tap_to_make_it_a_working_set_420a0b59)
                    } else {
                        uiString(R.string.l10n_lift_set_lld_tap_to_mark_it_a_warm_up_25988360, slot.setIndex)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (warmup) uiString(R.string.l10n_lift_w_e2415cb7) else "${slot.setIndex}",
                style = NoopType.captionNumber,
                color = when {
                    warmup -> Palette.metricAmber
                    isWorking -> Palette.textPrimary
                    else -> Palette.textSecondary
                },
            )
        }

        SetNumberField(
            value = draft[FieldKey.Weight(slot)] ?: entered.weightKg?.let { display(it, system) } ?: "",
            ghost = session.carry(slot).weightKg?.let { display(it, system) } ?: "—",
            keyboard = KeyboardType.Decimal,
            draftKey = FieldKey.Weight(slot),
            draft = draft,
            modifier = Modifier.weight(1f),
        ) { typed ->
            session.updateSet(
                slot,
                weightKg = LiftFormat.number(typed)?.let { LiftFormat.kilograms(fromDisplay = it, system = system) },
                reps = entered.reps,
                rpe = entered.rpe,
                isWarmup = warmup,
            )
        }

        SetNumberField(
            value = draft[FieldKey.Reps(slot)] ?: entered.reps?.toString() ?: "",
            ghost = session.carry(slot).reps?.toString() ?: "—",
            keyboard = KeyboardType.Number,
            draftKey = FieldKey.Reps(slot),
            draft = draft,
            modifier = Modifier.weight(1f),
        ) { typed ->
            session.updateSet(
                slot,
                weightKg = entered.weightKg,
                reps = typed.trim().toIntOrNull(),
                rpe = entered.rpe,
                isWarmup = warmup,
            )
        }

        SetNumberField(
            value = draft[FieldKey.Rpe(slot)] ?: entered.rpe?.let { LiftFormat.trim(it) } ?: "",
            ghost = ghostRpe(engine, slot),
            keyboard = KeyboardType.Decimal,
            draftKey = FieldKey.Rpe(slot),
            draft = draft,
            modifier = Modifier.weight(1f),
        ) { typed ->
            session.updateSet(
                slot,
                weightKg = entered.weightKg,
                reps = entered.reps,
                rpe = LiftFormat.number(typed),
                isWarmup = warmup,
            )
        }

        // The tick both REPORTS and ACTS: filled when the set is done, and tappable to start this set
        // when it is not — which is how you jump to a different exercise.
        Box(
            modifier = Modifier
                .width(TickColumnWidth)
                .clickable { session.start(slot) }
                .semantics {
                    contentDescription = if (recorded == null) {
                        uiString(R.string.l10n_lift_start_this_set_214128c1)
                    } else {
                        uiString(R.string.l10n_lift_redo_this_set_14070670)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (recorded == null) Icons.Filled.RadioButtonUnchecked else Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = if (recorded == null) Palette.textTertiary else Palette.statusPositive,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Green = working now, faint = done, clear = still to come.
 *
 * Deliberately no amber case. Tinting the just-finished SET amber said the wrong thing: the set is over,
 * and what is running is the gap after it. The rest is drawn as its own band between the two set rows
 * instead — see [RestBand].
 */
private fun rowBackground(isWorking: Boolean, done: Boolean): Color = when {
    isWorking -> Palette.statusPositive.copy(alpha = 0.20f)
    done -> Palette.surfaceRaised.copy(alpha = 0.5f)
    else -> Color.Transparent
}

private fun isRestingAfter(engine: LiftSessionEngine, slot: LiftSlot): Boolean {
    val stage = engine.stage
    return stage is LiftSessionEngine.Stage.Resting && stage.slot == slot
}

/**
 * The running rest, drawn as an amber band sitting BETWEEN the set that ended and the set that follows —
 * which is literally where a rest is.
 *
 * It replaces tinting the finished set's row amber. That read as "this set is amber" when the set was
 * already done, and from across a gym floor it was not obvious which gap was running. A band in the gap is
 * unambiguous at a glance, which is the whole requirement: you are looking at this from a bench, not
 * reading it.
 */
@Composable
private fun RestBand(engine: LiftSessionEngine) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Palette.metricAmber.copy(alpha = 0.22f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
    ) {
        Overline(uiString(R.string.l10n_lift_rest_period_2f21d820), color = Palette.metricAmber)
        Spacer(Modifier.weight(1f))
        LiftRunningClock(
            seconds = { engine.restRemaining(it.toInt())?.toLong() ?: 0L },
            style = NoopType.captionNumber,
            color = Palette.metricAmber,
        )
    }
}

/**
 * Add one more set, or drop the last planned one — at the END of the exercise, because that is where the
 * question comes up: you have done what was written down and have one more in you, or you have not.
 *
 * **Both buttons change this session only.** Whether the program keeps the new count is asked when the
 * session is finished: a program is a plan for next time, and one extra set on a good day is not always a
 * new plan.
 */
@Composable
private fun SetCountRow(
    session: LiftSessionController,
    engine: LiftSessionEngine,
    index: Int,
    item: LiftPlanItem,
) {
    val canAdd = item.targetSets < LiftSessionEngine.maxSetsPerExercise
    val canRemove = engine.canRemoveSet(fromExercise = index)

    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 2.dp, start = 8.dp, end = 8.dp),
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .clickable(enabled = canAdd) { session.addSet(toExercise = index) }
                .semantics {
                    contentDescription = uiString(R.string.l10n_lift_add_a_set_to_b9d5d907, item.exercise)
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
                .width(TickColumnWidth)
                .clickable(enabled = canRemove) { session.removeSet(fromExercise = index) }
                .semantics {
                    contentDescription = uiString(
                        R.string.l10n_lift_remove_the_last_set_from_5c65e3d6,
                        item.exercise,
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.RemoveCircleOutline,
                contentDescription = null,
                // Dimmed rather than gone: the pair reads as one control, and a minus that disappears once
                // the last set is done looks like a feature that broke.
                tint = if (canRemove) Palette.textSecondary else Palette.textTertiary.copy(alpha = 0.4f),
                modifier = Modifier.size(17.dp),
            )
        }
    }
}

/**
 * Add an exercise the program does not have — at the END of the sheet, after everything planned, because
 * that is where it goes: the program's lines keep their order, and the new one is tapped to start whenever
 * the lifter gets to it. Finishing asks whether the program keeps it; until then it changes this session
 * only, like the set-count buttons.
 */
@Composable
private fun AddExerciseRow(canAdd: Boolean, onClick: () -> Unit) {
    val tint = if (canAdd) Palette.effortColor else Palette.textTertiary
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .drawBehind {
                // A dashed outline: this row ADDS something rather than being one, so it must not read as
                // another card.
                drawRoundRect(
                    color = Palette.textTertiary.copy(alpha = 0.35f),
                    cornerRadius = CornerRadius(Metrics.cardRadius.toPx()),
                    style = Stroke(
                        width = 1.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx())),
                    ),
                )
            }
            .clickable(enabled = canAdd, onClick = onClick)
            .padding(vertical = 14.dp),
    ) {
        Spacer(Modifier.weight(1f))
        Icon(Icons.Filled.AddCircle, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(8.dp))
        Text(uiString(R.string.l10n_lift_add_exercise_64404808), style = NoopType.body, color = tint)
        Spacer(Modifier.weight(1f))
    }
}

// MARK: - Ghost values
//
// The grey numbers come from ONE chain, `LiftSessionController.carry`: this exercise earlier in the
// session (set 2 almost always mirrors set 1), then the same set last session, then the program's target.
// The minimised bar reads the same chain.

/**
 * Grey RPE is the line's max RPE when the program sets one — and, like every other grey number, it is what
 * the set saves if nothing is typed over it. A previous set's own rating is a reminder when the plan sets
 * no maximum, and that one is never saved: it belongs to another set.
 */
private fun ghostRpe(engine: LiftSessionEngine, slot: LiftSlot): String {
    engine.planItem(slot)?.targetRpe?.let { return LiftFormat.trim(it) }
    return engine.previousSetInSession(slot)?.rpe?.let { LiftFormat.trim(it) } ?: "—"
}

private fun display(kg: Double, system: UnitSystem): String =
    LiftFormat.trim(LiftFormat.display(fromKilograms = kg, system = system))

/**
 * A chrome-less numeric field with a grey ghost placeholder, matching the Swift `TextField` +
 * `.textFieldStyle(.plain)`.
 *
 * A typed comma becomes a point on the way in. Android's decimal keyboard labels its separator key from the
 * DEVICE's locale — a German or French phone offers "," — so the two would otherwise disagree with the "."
 * this screen displays everywhere else.
 */
@Composable
private fun SetNumberField(
    value: String,
    ghost: String,
    keyboard: KeyboardType,
    draftKey: FieldKey,
    draft: MutableMap<FieldKey, String>,
    modifier: Modifier = Modifier,
    onTyped: (String) -> Unit,
) {
    BasicTextField(
        value = value,
        onValueChange = { typed ->
            val text = typed.replace(',', '.')
            draft[draftKey] = text
            onTyped(text)
        },
        textStyle = NoopType.bodyNumber.copy(color = Palette.textPrimary),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        cursorBrush = SolidColor(Palette.accent),
        modifier = modifier.onFocusChanged { state ->
            // Release the draft once the user leaves the field, so the row returns to the canonical
            // formatting ("45.50" typed becomes "45.5").
            if (!state.isFocused) draft.remove(draftKey)
        },
        decorationBox = { inner ->
            if (value.isEmpty()) {
                Text(ghost, style = NoopType.bodyNumber, color = Palette.textTertiary)
            } else {
                inner()
            }
        },
    )
}

// MARK: - The control bar

/**
 * The control bar never scrolls away: at the rack the clock and the one action have to be where your thumb
 * already is.
 */
@Composable
private fun ControlBar(
    vm: AppViewModel,
    session: LiftSessionController,
    engine: LiftSessionEngine,
    onFinish: () -> Unit,
) {
    val bpm by vm.bpm.collectAsStateWithLifecycle()
    Column(
        verticalArrangement = Arrangement.spacedBy(Metrics.space10),
        modifier = Modifier
            .fillMaxWidth()
            .background(Palette.surfaceOverlay)
            .drawBehind {
                // iOS draws a 0.5pt hairline on the bar's top edge, so the pinned strip reads as a surface
                // over the scrolling sheet rather than part of it.
                drawLine(
                    color = Palette.textTertiary.copy(alpha = 0.15f),
                    start = Offset(0f, 0f),
                    end = Offset(size.width, 0f),
                    strokeWidth = 0.5.dp.toPx(),
                )
            }
            .padding(horizontal = Metrics.screenPadding)
            .padding(top = 10.dp, bottom = 14.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.Bottom) {
            LabeledReadout(uiString(R.string.l10n_lift_session_f7f1997c)) {
                LiftRunningClock(
                    seconds = { it - engine.startTs },
                    style = NoopType.bodyNumber,
                    color = Palette.textPrimary,
                )
            }
            StageClock(engine)
            LabeledReadout(uiString(R.string.l10n_lift_hr_f187928f)) { LiftHeartRate(bpm, LiftHeartRateStyle.Plain, NoopType.bodyNumber) }
            Spacer(Modifier.weight(1f))
            IconButton(
                onClick = { session.undo() },
                enabled = engine.canUndo,
                modifier = Modifier
                    .size(32.dp)
                    .semantics { contentDescription = uiString(R.string.l10n_sleep_screen_undo_39fc7212) },
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Undo,
                    contentDescription = null,
                    tint = if (engine.canUndo) Palette.textSecondary else Palette.textTertiary,
                    modifier = Modifier.size(15.dp),
                )
            }
        }

        Row(
            horizontalArrangement = Arrangement.spacedBy(Metrics.space10),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NoopButton(
                text = actionLabel(engine).text(),
                kind = NoopButtonKind.Primary,
                modifier = Modifier.weight(1f),
                onClick = { session.advance() },
            )
            NoopButton(text = uiString(R.string.l10n_lift_finish_b74bdee9), kind = NoopButtonKind.Secondary, onClick = onFinish)
        }
    }
}

/**
 * iOS `ComingSoon(what: "No session running", symbol: "dumbbell")` — the card the session sheet shows
 * when nothing is running: an accent symbol, a title and the line saying there is no session.
 */
@Composable
private fun NoSessionCard() {
    NoopCard {
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Filled.FitnessCenter,
                contentDescription = null,
                tint = Palette.accent,
                modifier = Modifier.size(20.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    uiString(R.string.l10n_lift_coming_together_ac66761d),
                    style = NoopType.headline,
                    color = Palette.textPrimary,
                )
                Text(
                    uiString(R.string.l10n_lift_no_session_running_c5ce2dcb),
                    style = NoopType.body,
                    color = Palette.textSecondary,
                )
            }
        }
    }
}

@Composable
private fun LabeledReadout(label: String, value: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
        Overline(label)
        value()
    }
}

@Composable
private fun StageClock(engine: LiftSessionEngine) {
    when (engine.stage) {
        is LiftSessionEngine.Stage.Working ->
            LabeledReadout(uiString(R.string.l10n_lift_this_set_ad5ac553)) {
                LiftRunningClock(
                    seconds = { it - engine.stageStartedAt },
                    style = NoopType.bodyNumber,
                    color = Palette.statusPositive,
                )
            }

        is LiftSessionEngine.Stage.Resting ->
            // "Rest period", never "Rest": NOOP's SLEEP metric owns the bare "Rest" key, so this label
            // rendered as the wrong German word (Erholung, recovery) when the two shared a key.
            LabeledReadout(uiString(R.string.l10n_lift_rest_period_2f21d820)) {
                LiftRunningClock(
                    seconds = { engine.restRemaining(it.toInt())?.toLong() ?: 0L },
                    style = NoopType.bodyNumber,
                    color = Palette.metricAmber,
                )
            }

        else ->
            LabeledReadout(uiString(R.string.l10n_lift_warm_up_fb1869bf)) {
                LiftRunningClock(
                    seconds = { it - engine.stageStartedAt },
                    style = NoopType.bodyNumber,
                    color = Palette.textSecondary,
                )
            }
    }
}

/**
 * What the big button says, decided from the stage — the DECISION is what is pinned by `LiftSessionUiTest`,
 * and the words are resolved at the call site (see [DisplayText]).
 */
internal fun actionLabel(engine: LiftSessionEngine): DisplayText = when (engine.stage) {
    LiftSessionEngine.Stage.Warmup -> DisplayText.Resource(R.string.l10n_lift_start_first_set_5f9bbb3b)
    is LiftSessionEngine.Stage.Working -> DisplayText.Resource(R.string.l10n_lift_set_done_d919abfe)
    is LiftSessionEngine.Stage.Resting ->
        if (engine.allCompleted) DisplayText.Resource(R.string.l10n_lift_all_sets_done_615a1502)
        else DisplayText.Resource(R.string.l10n_lift_start_next_set_c88008ec)
    LiftSessionEngine.Stage.Finished -> DisplayText.Resource(R.string.l10n_lift_saving_56a2285c)
}

// MARK: - Loading last time

/**
 * What was lifted for each of this session's exercises LAST time, by set number — the middle layer of the
 * grey numbers, handed to the controller that owns the chain.
 *
 * One query per DISTINCT exercise, not per plan line: a program that programs the same movement twice (or
 * an imported one with many lines) would otherwise re-ask the store the same question, and this runs when
 * the sheet opens.
 */
private suspend fun loadLastTime(
    repo: LiftLogRepository,
    session: LiftSessionController,
    deviceId: String,
    engine: LiftSessionEngine,
) {
    val out = HashMap<String, Map<Int, LiftSetCarry>>()
    for (exercise in engine.plan.map { it.exercise }.distinct()) {
        val bySet = HashMap<Int, LiftSetCarry>()
        for (row in repo.lastSets(deviceId, exercise, before = engine.startTs.toLong())) {
            if (row.isWarmup) continue
            bySet[row.setIndex] = LiftSetCarry(weightKg = row.weightKg, reps = row.reps)
        }
        out[exercise] = bySet
    }
    session.setLastSession(out)
}
