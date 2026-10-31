package com.noop.ai

import android.content.Context

/** Read-only health context sharing. Credentials are never a dataset. */
object CoachSharing {
    enum class Category(val title: String) {
        DAILY("Recovery, sleep, HRV, heart rate, stress and temperature"),
        WORKOUTS("Workouts and training"),
        NUTRITION("Food logs, calories burned and energy balance"),
        BODY("Weight and body composition"),
        JOURNAL("Journal, habits and lab markers"),
        SIGNALS("Local correlations, habit and lab summaries"),
    }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("noop_coach_sharing", Context.MODE_PRIVATE)
    fun enabled(ctx: Context, category: Category): Boolean = prefs(ctx).getBoolean(category.name,
        category == Category.DAILY || category == Category.WORKOUTS)
    fun set(ctx: Context, category: Category, on: Boolean) { prefs(ctx).edit().putBoolean(category.name, on).apply() }
    private fun target(ctx: Context): String = AiKeyStore.readProvider(ctx).name + "|" + AiKeyStore.readCustomBaseUrl(ctx)
    fun full(ctx: Context): Boolean = prefs(ctx).getString("full_target", null) == target(ctx)
    internal fun matchesTarget(approved: String?, current: String, requested: String): Boolean =
        approved != null && approved == current && approved == requested
    fun authorized(ctx: Context, provider: AiProvider, baseUrl: String): Boolean = matchesTarget(
        prefs(ctx).getString("full_target", null), target(ctx), provider.name + "|" +
            if (provider == AiProvider.CUSTOM) baseUrl.trim() else AiKeyStore.readCustomBaseUrl(ctx),
    )
    fun setFull(ctx: Context, on: Boolean) {
        prefs(ctx).edit().putString("full_target", if (on) target(ctx) else null).apply()
    }
}
