package com.noop.ui

import com.noop.R

import android.app.TimePickerDialog
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.noop.notif.DailyReportSchedule

@Composable
internal fun DailyReportControls() {
    val ctx = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    revision
    for (evening in listOf(false, true)) {
        val minute = DailyReportSchedule.minute(ctx, evening)
        Row {
            TextButton(onClick = {
                TimePickerDialog(ctx, { _, h, m -> DailyReportSchedule.set(ctx, evening, h * 60 + m); revision++ }, minute / 60, minute % 60, ClockPrefs.uses24Hour(ctx)).show()
            }) { Text((if (evening) "Evening report · " else "Morning report · ") + java.time.LocalTime.of(minute / 60, minute % 60).format(
                java.time.format.DateTimeFormatter.ofPattern(com.noop.analytics.ClockFormat.hourMinutePattern(ClockPrefs.uses24Hour(ctx)), java.util.Locale.getDefault()))) }
            if (evening) Switch(checked = DailyReportSchedule.eveningEnabled(ctx), onCheckedChange = { DailyReportSchedule.set(ctx, true, enabled = it); revision++ })
        }
    }
    Text(uiString(R.string.feature_dailyreportcontrols_8), style = NoopType.footnote, color = Palette.textSecondary)
    TextButton(onClick = {
        ctx.startActivity(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName).putExtra(Settings.EXTRA_CHANNEL_ID, "noop_strap_connection"))
    }) { Text(uiString(R.string.feature_dailyreportcontrols_9)) }
    Text(uiString(R.string.feature_dailyreportcontrols_10), style = NoopType.footnote, color = Palette.textSecondary)
}
