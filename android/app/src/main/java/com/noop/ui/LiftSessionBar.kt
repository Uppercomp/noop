package com.noop.ui

import com.noop.R
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FitnessCenter
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.noop.data.LiftSessionEngine

// The running session, condensed to a bar that sits above the tab bar wherever you are in the app.
//
// WHY IT EXISTS. Dismissing the workout sheet used to end the session outright: the screen went away and
// took the strap handler and the restart timers with it, so taps and buzzes silently stopped working. Now
// the sheet MINIMISES to this bar. The session is still running — same clock, same strap gesture, same
// buzzes — and tapping the bar brings the full sheet back.
//
// It is deliberately a bar and not a badge: it has to show the one thing you need mid-workout without
// opening anything, which is how long is left of your rest.
//
// COLOUR MATCHES THE SHEET so the two read as one thing: green while working, amber while resting.
//
// Kotlin twin of `Strand/Screens/LiftSessionBar.swift`.

@Composable
fun LiftSessionBar(
    session: LiftSessionController,
    system: UnitSystem,
    bpm: Int?,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    @Suppress("UNUSED_VARIABLE")
    val revision = session.revision
    val engine = session.engine ?: return
    // `LiftSessionController.presentation` — the SAME resolution the sheet's own words come from, so the
    // bar and the sheet cannot word the session differently. Resolved once; all three lines read it.
    val shown = session.presentation(system, uiString(R.string.l10n_lift_session_f7f1997c)) ?: return
    val tint = barTint(engine)
    val shape = RoundedCornerShape(50)

    Surface(
        shape = shape,
        // The same glass family as the tab bar below it, so the two read as one floating island stack:
        // a translucent raised surface with a soft shadow, its translucency layered over the backdrop
        // that keeps scrolled content from reading as crisp text behind it.
        color = Palette.surfaceRaised.copy(alpha = BottomBarStyleStore.barAlpha),
        tonalElevation = 2.dp,
        shadowElevation = 4.dp,
        border = BorderStroke(1.dp, tint.copy(alpha = 0.35f)),
        modifier = modifier
            .fillMaxWidth()
            .widthIn(max = 480.dp)
            .clickable(onClick = onOpen)
            .semantics { contentDescription = uiString(R.string.l10n_lift_open_the_running_session_02b6b1b8) },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Filled.FitnessCenter,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(14.dp),
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                Text(
                    shown.exercise,
                    style = NoopType.caption,
                    color = Palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    shown.detail?.let { "${shown.status.text()} — ${it.text()}" } ?: shown.status.text(),
                    style = NoopType.footnote,
                    color = Palette.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // The set coming up, on one line that cuts the exercise name before the set number.
                Text(
                    shown.next.text(),
                    style = NoopType.footnote,
                    color = Palette.textTertiary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.size(6.dp))

            // Heart rate over the clock, both flush right, each its own small composable that updates
            // itself, so a beat or a tick redraws one number rather than the whole bar.
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                LiftHeartRate(bpm, LiftHeartRateStyle.Compact)

                // The clock's width comes from an invisible "00:00" in its own font — the widest a set or a
                // rest shows under an hour — so the words beside it do not shift when it gains a digit.
                // Semantics cleared: it is a width reservation, not a number to read out.
                Box {
                    Text(
                        "00:00",
                        style = NoopType.bodyNumber,
                        color = Color.Transparent,
                        modifier = Modifier.clearAndSetSemantics {},
                    )
                    LiftRunningClock(
                        seconds = { engine.restRemaining(it.toInt())?.toLong() ?: (it - engine.stageStartedAt) },
                        style = NoopType.bodyNumber,
                        color = tint,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                }
            }

            // The same action the sheet's button performs, so a set can be closed out without opening
            // anything.
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = uiString(R.string.l10n_lift_next_bc981983),
                tint = tint,
                modifier = Modifier
                    .size(26.dp)
                    .clickable { session.advance() },
            )
        }
    }
}

/**
 * Green while working, amber while resting, Effort blue otherwise — the sheet's colour language.
 *
 * Internal rather than private so `LiftSessionUiTest` can pin it against the same rule the sheet's row
 * backgrounds follow: "colour carries state" only works if the two surfaces agree about which state is
 * which colour.
 */
internal fun barTint(engine: LiftSessionEngine): Color = when (engine.stage) {
    is LiftSessionEngine.Stage.Working -> Palette.statusPositive
    is LiftSessionEngine.Stage.Resting -> Palette.metricAmber
    else -> Palette.effortColor
}
