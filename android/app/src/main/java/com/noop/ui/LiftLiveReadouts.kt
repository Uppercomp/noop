package com.noop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import com.noop.R
import kotlinx.coroutines.delay

// The two numbers on the session's surfaces that change on their own — the running clock and the live heart
// rate — each as its own small composable, so a tick or a heartbeat redraws that one number and nothing
// around it.
//
// They used to be read by the screens that show them: the whole session sheet watched the session's
// once-a-second tick AND the strap's live state, which changes with every log line and every beat. So the
// sheet — a hundred text fields — was redrawn several times a second for as long as it was open, even with
// NOOP off screen, and iOS killed NOOP for background CPU four times in one gym session.
//
// Kotlin twin of `Strand/Screens/LiftLiveReadouts.swift`.

/**
 * A running clock that ticks by itself while it is shown and costs nothing while it is not: the effect
 * below only runs while this composable is in the tree, and it re-arms to the next whole second rather
 * than drifting.
 *
 * [seconds] turns the current unix second into what the clock reads, and the format is [elapsedClock] —
 * `M:SS` up to an hour, `H:MM:SS` past it.
 */
@Composable
fun LiftRunningClock(
    seconds: (Long) -> Long,
    modifier: Modifier = Modifier,
    style: TextStyle = NoopType.bodyNumber,
    color: Color = Palette.textPrimary,
) {
    var nowSec by remember { mutableLongStateOf(System.currentTimeMillis() / 1000L) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000L - System.currentTimeMillis() % 1000L)
            nowSec = System.currentTimeMillis() / 1000L
        }
    }
    Text(elapsedClock(seconds(nowSec)), style = style, color = color, modifier = modifier)
}

/** How the live heart rate is drawn. */
enum class LiftHeartRateStyle {
    /** Heart and number in caption size — the minimised bar. */
    Compact,

    /** The number alone, under the sheet's "HR" label. */
    Plain,
}

/**
 * The live heart rate: the smoothed, spike-filtered bpm every screen shows, never the raw per-beat number.
 *
 * ALWAYS shown, a dash when the strap is not reading: an earlier version hid it with no reading, and the
 * first thing that produced was "there is no HR in the minimised tab" — an absent readout is
 * indistinguishable from an absent feature, while a dash says the strap is not reading, which is something
 * to act on. Display only: nothing here feeds a score; Effort stays what the strap measured.
 */
@Composable
fun LiftHeartRate(
    bpm: Int?,
    style: LiftHeartRateStyle,
    textStyle: TextStyle = NoopType.bodyNumber,
    modifier: Modifier = Modifier,
) {
    val tint = if (bpm == null) Palette.textTertiary else Palette.metricRose
    val value = bpm?.toString() ?: "—"
    when (style) {
        LiftHeartRateStyle.Compact ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(3.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = modifier.semantics {
                    contentDescription = if (bpm == null) {
                        uiString(R.string.l10n_lift_heart_rate_410aa15c)
                    } else {
                        uiString(R.string.l10n_lift_heart_rate_lld_cf47c90c, bpm)
                    }
                },
            ) {
                Icon(
                    Icons.Filled.Favorite,
                    contentDescription = null,
                    tint = tint,
                    modifier = Modifier.size(10.dp),
                )
                Text(value, style = NoopType.captionNumber, color = tint)
            }

        LiftHeartRateStyle.Plain ->
            Text(
                value,
                style = textStyle,
                color = tint,
                modifier = modifier.semantics {
                    contentDescription = if (bpm == null) {
                        uiString(R.string.l10n_lift_heart_rate_410aa15c)
                    } else {
                        uiString(R.string.l10n_lift_heart_rate_lld_cf47c90c, bpm)
                    }
                },
            )
    }
}
