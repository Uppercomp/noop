package com.noop.notif

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import com.noop.NoopApplication
import com.noop.R
import com.noop.analytics.RecoveryForecaster
import com.noop.analytics.RestScorer
import com.noop.ui.NoopPrefs
import com.noop.ui.appLaunchIntent
import java.time.*
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

object DailyReportSchedule {
    private fun prefs(ctx: Context) = NoopPrefs.of(ctx)
    fun minute(ctx: Context, evening: Boolean) = prefs(ctx).getInt(if (evening) "report.eveningMinute" else "report.morningMinute", if (evening) 21 * 60 else 8 * 60).coerceIn(0, 1439)
    fun eveningEnabled(ctx: Context) = prefs(ctx).getBoolean("report.eveningEnabled", true)
    fun set(ctx: Context, evening: Boolean, minute: Int? = null, enabled: Boolean? = null) {
        val edit = prefs(ctx).edit()
        minute?.let { edit.putInt(if (evening) "report.eveningMinute" else "report.morningMinute", it.coerceIn(0, 1439)) }
        enabled?.let { if (evening) edit.putBoolean("report.eveningEnabled", it) else edit.putBoolean(NoopPrefs.KEY_REPORT_MORNING, it) }
        edit.apply()
        ensure(ctx, replace = true)
    }
    fun next(now: ZonedDateTime, minute: Int): ZonedDateTime {
        val target = now.toLocalDate().atTime(minute.coerceIn(0, 1439) / 60, minute.coerceIn(0, 1439) % 60).atZone(now.zone)
        return if (target.isAfter(now)) target else now.toLocalDate().plusDays(1)
            .atTime(minute.coerceIn(0, 1439) / 60, minute.coerceIn(0, 1439) % 60).atZone(now.zone)
    }
    fun ensure(ctx: Context, replace: Boolean = false) {
        val manager = WorkManager.getInstance(ctx)
        for (evening in listOf(false, true)) {
            val name = "noop_daily_report_$evening"
            val enabled = if (evening) eveningEnabled(ctx) else NoopPrefs.morningReportEnabled(ctx)
            if (!enabled) { manager.cancelUniqueWork(name); continue }
            val now = ZonedDateTime.now()
            val request = OneTimeWorkRequestBuilder<DailyReportWorker>()
                .setInputData(workDataOf("evening" to evening))
                .setInitialDelay(Duration.between(now, next(now, minute(ctx, evening))).toMillis(), TimeUnit.MILLISECONDS).build()
            manager.enqueueUniqueWork(name, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request)
        }
    }
}

class DailyReportClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) { DailyReportSchedule.ensure(context, replace = true) }
}

class DailyReportWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val evening = inputData.getBoolean("evening", false)
        try {
            if (evening && !DailyReportSchedule.eveningEnabled(ctx) || !evening && !NoopPrefs.morningReportEnabled(ctx)) return Result.success()
            val app = ctx as NoopApplication
            val today = LocalDate.now().toString()
            val days = app.repository.daysMerged(app.activeDeviceId).filter { it.day <= today }
            val day = days.lastOrNull { it.day == today }
            if (!evening) {
                // Do not silently report an old night as this morning. A late sync can still invoke the existing deduped gate.
                if (day != null) ScheduledReportNotifier.onMorning(ctx, day.day, day.recovery?.roundToInt(), RestScorer.restFromDaily(day)?.roundToInt())
            } else if (NoopPrefs.of(ctx).getString("report.lastEvening", null) != today && day != null) {
                val recent = days.filter { it.day >= LocalDate.now().minusDays(14).toString() }
                val forecast = RecoveryForecaster.forecast(recent.mapNotNull { it.recovery }, recent.mapNotNull { it.strain }, day.strain,
                    plannedSleepHours = RecoveryForecaster.defaultNeedHours)
                val effort = day.strain?.let { "Effort ${it.roundToInt()}/100" } ?: "Effort unavailable"
                val predicted = forecast?.let { "Tomorrow's Charge estimate ${it.charge.roundToInt()} (${it.low.roundToInt()}–${it.high.roundToInt()}), assuming ${it.plannedSleepHours} h sleep." }
                    ?: "Tomorrow's Charge estimate needs at least ${RecoveryForecaster.minBaselineNights} recent scored nights."
                if (postEvening(ctx, effort, predicted)) NoopPrefs.of(ctx).edit().putString("report.lastEvening", today).apply()
            }
        } finally {
            // APPEND keeps the successor when this same named worker is still RUNNING.
            val enabled = if (evening) DailyReportSchedule.eveningEnabled(ctx) else NoopPrefs.morningReportEnabled(ctx)
            if (!isStopped && enabled) {
                val now = ZonedDateTime.now()
                val request = OneTimeWorkRequestBuilder<DailyReportWorker>().setInputData(workDataOf("evening" to evening))
                    .setInitialDelay(Duration.between(now, DailyReportSchedule.next(now, DailyReportSchedule.minute(ctx, evening))).toMillis(), TimeUnit.MILLISECONDS).build()
                WorkManager.getInstance(ctx).enqueueUniqueWork("noop_daily_report_$evening", ExistingWorkPolicy.APPEND_OR_REPLACE, request)
            }
        }
        return Result.success()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun postEvening(ctx: Context, effort: String, forecast: String): Boolean {
        if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return false
        val channel = "noop_scheduled_reports"
        val manager = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(NotificationChannel(channel, "Daily reports", NotificationManager.IMPORTANCE_DEFAULT))
        if (manager.getNotificationChannel(channel)?.importance == NotificationManager.IMPORTANCE_NONE) return false
        val intent = PendingIntent.getActivity(ctx, 4210, appLaunchIntent(ctx), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        NotificationManagerCompat.from(ctx).notify(4210, NotificationCompat.Builder(ctx, channel).setSmallIcon(R.drawable.ic_stat_heart)
            .setContentTitle("Your day: $effort").setContentText(forecast).setStyle(NotificationCompat.BigTextStyle().bigText(forecast))
            .setContentIntent(intent).setAutoCancel(true).build())
        return true
    }
}
