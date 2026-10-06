package com.noop.ui

import android.content.Context
import android.content.SharedPreferences
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The "moments" a strap double-tap marks — the store iOS keeps under its `moments` UserDefaults key and
 * shows as the double-tap card's "Recent moments" list (`AutomationsView.swift:154-187`).
 *
 * Android had no store at all, and said so in `AppViewModel.markMoment`: "Android has no separate moments
 * store yet, so — matching the iOS markMoment's user-visible effect (a buzz + a greppable log line) — this
 * writes a timestamped line into the shareable strap log and buzzes once." The log line is kept (it is what
 * a bug report needs); this adds the list the user can actually see, and a way to clear it.
 *
 * Deliberately NOT part of `.noopbak`: the backup whitelist is a byte-identical cross-platform contract
 * (`BackupSettings.swift` ↔ `BackupSettingsCodec`), and these are ephemeral markers, not measured data.
 *
 * Bounded at [MAX]: iOS lets its array grow, but an unbounded string in SharedPreferences is a slow leak on
 * a store this app rewrites on every double-tap. The newest [MAX] are kept and the oldest fall off.
 */
class MomentStore(private val prefs: SharedPreferences) {

    /** Marked moments, oldest first, epoch MILLIS. */
    fun load(): List<Long> = decode(prefs.getString(KEY, null))

    fun save(moments: List<Long>) {
        prefs.edit().putString(KEY, encode(moments)).apply()
    }

    companion object {
        const val MAX = 50
        private const val PREFS = "noop_moments"
        private const val KEY = "moments"

        /** Parse the stored form. Anything unparseable is dropped rather than throwing: a corrupt pref must
         *  not take the Automations screen down. */
        fun decode(raw: String?): List<Long> {
            if (raw.isNullOrBlank()) return emptyList()
            return raw.split(',').mapNotNull { it.trim().toLongOrNull() }
        }

        fun encode(moments: List<Long>): String = moments.joinToString(",")

        /** Append [ts] and keep the newest [cap]. Pure, so the cap rule is unit-testable. */
        fun append(existing: List<Long>, ts: Long, cap: Int = MAX): List<Long> =
            (existing + ts).takeLast(cap)

        /** The newest [show] moments, NEWEST FIRST — the order the card lists them in. */
        fun recent(moments: List<Long>, show: Int = 5): List<Long> = moments.takeLast(show).reversed()

        fun from(context: Context): MomentStore =
            MomentStore(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))
    }
}

/**
 * One moment as the card prints it: "Tue 3 Oct · 18:42", or "… · 6:42 pm" on a 12-hour clock.
 *
 * iOS's `momentFormatter` is the twin, including the clock preference: it resolves the pattern's "j"
 * template so a reader on a 12-hour device sees 12-hour times (#337). Android reads
 * [ClockPrefs.uses24Hour], the same preference every other time on the screen honours.
 */
internal fun momentLabel(tsMillis: Long, uses24Hour: Boolean): String {
    val pattern = if (uses24Hour) "EEE d MMM · HH:mm" else "EEE d MMM · h:mm a"
    return DateTimeFormatter.ofPattern(pattern, Locale.getDefault())
        .format(Instant.ofEpochMilli(tsMillis).atZone(ZoneId.systemDefault()))
}
