package com.noop.notif

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.noop.R
import com.noop.ui.LiftSessionController
import com.noop.ui.UnitPrefs
import com.noop.ui.appLaunchIntent
import com.noop.ui.plain

/**
 * The running gym session, as a notification you can read with the phone locked.
 *
 * WHY THIS EXISTS. The session bar lives in the app, so seeing how long is left of a rest means unlocking,
 * finding NOOP and opening it — which, at the rack with a phone in a pocket, is the whole time the rest
 * lasts. iOS answers this with a Lock Screen banner (a Live Activity); Android's answer is a notification,
 * which is where a phone-locked readout belongs on this platform.
 *
 * THE COUNTDOWN IS RENDERED BY THE SYSTEM, NOT BY US. `setUsesChronometer` hands the tick to the system UI,
 * so the clock stays right while the app is backgrounded, killed, or doing nothing — and, unlike a
 * once-a-second refresh, it costs no wakeups at all. That is why there is no Android twin of the Apple
 * `LiftBannerPushPolicy`: that policy exists to budget ActivityKit updates, and there is nothing here to
 * budget. The notification is posted when the session's WORDS change (a tap, a stage change, a finish) and
 * not otherwise.
 *
 * Rest counts DOWN and everything else counts up — the same rule `LiftRunningClock` follows on screen, so
 * the two cannot disagree about what a clock is doing.
 *
 * NO LIVE HEART RATE. The in-app surfaces show `AppViewModel.bpm`, which is a smoothed median computed in
 * the UI layer; there is no process-level equivalent to read here, and posting the raw per-beat number would
 * put a different figure on the Lock Screen than the one in the app. A number that looks live and is not is
 * worse than no number, so the readout is deliberately the session's state alone.
 */
object LiftSessionNotifier {
    private const val CHANNEL_ID = "noop_lift_session"

    /** 4201 = ongoing connection, 4202 = illness watch, 4203 = inactivity. */
    private const val NOTIF_ID = 4204

    /**
     * Post (or refresh) the session's notification, or clear it when there is no session.
     *
     * Called on every session mutation AND on teardown, so the notification's existence follows the
     * session's — one entry point rather than a show and a hide that can drift apart.
     */
    @SuppressLint("MissingPermission") // guarded by areNotificationsEnabled() + runCatching
    fun update(context: Context, session: LiftSessionController) {
        val shown = session.presentation(
            UnitPrefs.system(context),
            context.getString(R.string.l10n_lift_session_f7f1997c),
        )
        if (shown == null || !session.isActive) {
            clear(context)
            return
        }
        val engine = session.engine ?: run { clear(context); return }

        // Defensive: never let a notify() throw (revoked POST_NOTIFICATIONS, OEM quirk) break a session.
        runCatching {
            if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
            ensureChannel(context)

            // The same three lines the in-app bar draws, from the same resolution — `plain()` because a
            // notification is built outside any composition.
            val status = shown.status.plain()
            val next = shown.next.plain()
            val state = shown.detail?.let { "${status} — ${it.plain()}" } ?: status
            val lines = listOf(shown.exercise, state, next).joinToString("\n")

            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_heart)
                .setContentTitle(shown.exercise)
                .setContentText(state)
                .setSubText(next)
                .setStyle(NotificationCompat.BigTextStyle().bigText(lines))
                .setContentIntent(
                    android.app.PendingIntent.getActivity(
                        context, 6,
                        appLaunchIntent(context),
                        android.app.PendingIntent.FLAG_IMMUTABLE or
                            android.app.PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
                // A running session is not something to swipe away: it ends when the user ends it.
                .setOngoing(true)
                // Every update REPLACES the last one, so it must not re-alert each time.
                .setOnlyAlertOnce(true)
                .setSilent(true)
                .setShowWhen(true)
                .setUsesChronometer(true)
                .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                .setPriority(NotificationCompat.PRIORITY_LOW)

            // Rest counts down to its end; everything else counts up from the stage's start.
            val restEndsAt = shown.restEndsAt
            if (restEndsAt != null) {
                builder.setWhen(restEndsAt * 1000L)
                builder.setChronometerCountDown(true)
            } else {
                builder.setWhen(shown.stageStartedAt * 1000L)
                builder.setChronometerCountDown(false)
            }

            NotificationManagerCompat.from(context).notify(NOTIF_ID, builder.build())
        }
    }

    /** Drop the notification. Safe to call when there is none. */
    fun clear(context: Context) {
        runCatching { NotificationManagerCompat.from(context).cancel(NOTIF_ID) }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        runCatching {
            val mgr = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (mgr.getNotificationChannel(CHANNEL_ID) != null) return
            mgr.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, "Gym session",
                    // LOW: this is a readout, not an alert. It must never make a sound, and it shares the
                    // shade with everything else without demanding attention of its own.
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "A running Lift Log session, with how long is left of the rest."
                    setShowBadge(false)
                },
            )
        }
    }
}
