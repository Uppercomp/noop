package com.noop.ai

import com.noop.data.JournalEntry
import com.noop.data.WorkoutRow
import com.noop.data.WhoopRepository
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime

/** Proposed values only. Parsing never writes; only the explicit confirmation path calls save. */
sealed class CoachLogAction {
    data class Journal(val day: String, val question: String, val yes: Boolean, val notes: String?, val value: Double?) : CoachLogAction()
    data class Workout(val start: Long, val end: Long, val sport: String, val notes: String?) : CoachLogAction()

    fun preview(): String = when (this) {
        is Journal -> "Journal · $day\n$question: ${if (yes) "Yes" else "No"}" + (value?.let { " · $it" } ?: "") + (notes?.let { "\n$it" } ?: "")
        is Workout -> "Workout · $sport\n${java.time.Instant.ofEpochSecond(start).atZone(java.time.ZoneId.systemDefault())}\n${(end - start) / 60} min" + (notes?.let { "\n$it" } ?: "")
    }
    suspend fun save(repo: WhoopRepository, strap: String) {
        when (this) {
            is Journal -> {
                require(repo.journal("noop-journal", day, day).none { it.question == question }) { "This journal answer already exists. Use Journal to review or edit it." }
                repo.upsertJournal(listOf(JournalEntry("noop-journal", day, question, yes, notes, value)))
            }
            is Workout -> {
                require(repo.workoutsUnion(strap, start, end).none { it.startTs == start && it.sport == sport }) { "This workout already exists. Use Workouts to edit it." }
                repo.saveManualWorkout(WorkoutRow(strap, start, end, sport, "manual", durationS = (end - start).toDouble(), notes = notes))
            }
        }
    }
    companion object {
        private val fence = Regex("```noop_action\\s*(\\{[\\s\\S]*?\\})\\s*```", RegexOption.IGNORE_CASE)
        fun visibleText(text: String): String = text.replace(fence, "").trim()
        fun parse(text: String, now: Long = System.currentTimeMillis() / 1000): CoachLogAction? = runCatching {
            val matches = fence.findAll(text).toList()
            require(matches.size == 1)
            val obj = JSONObject(matches.single().groupValues[1])
            fun optional(key: String) = obj.optString(key).takeIf { it.isNotBlank() && it != "null" }?.take(4000)
            when (obj.getString("kind")) {
                "journal" -> {
                    val day = LocalDate.parse(obj.getString("day"))
                    require(day <= LocalDate.now() && day >= LocalDate.now().minusYears(10))
                    val question = obj.getString("question").trim()
                    require(question.isNotBlank() && question.length <= 240 && obj.has("answeredYes"))
                    val number = if (obj.has("numericValue") && !obj.isNull("numericValue")) obj.getDouble("numericValue").also { require(it.isFinite()) } else null
                    Journal(day.toString(), question, obj.getBoolean("answeredYes"), optional("notes"), number)
                }
                "workout" -> {
                    val start = OffsetDateTime.parse(obj.getString("start")).toEpochSecond()
                    val end = OffsetDateTime.parse(obj.getString("end")).toEpochSecond()
                    val sport = obj.getString("sport").trim()
                    require(start > 0 && end > start && end - start <= 86400 && end <= now + 300 && sport.isNotBlank() && sport.length <= 100)
                    Workout(start, end, sport, optional("notes"))
                }
                else -> error("Unsupported action")
            }
        }.getOrNull()

        const val INSTRUCTIONS = """

USER-CONFIRMED LOGGING: Only when the user asks to log something they actually did, you may propose
one action in a fenced noop_action JSON block after a brief explanation. It is NOT saved yet; the app
shows a preview and requires a Confirm button. Never say saved/recorded before that confirmation.
Journal shape: {"kind":"journal","day":"YYYY-MM-DD","question":"exact journal question","answeredYes":true,"notes":"user-provided facts","numericValue":null}.
Workout shape: {"kind":"workout","start":"ISO-8601 timestamp with explicit UTC offset","end":"ISO-8601 timestamp with explicit UTC offset","sport":"Running","notes":"user-provided facts"}.
Ask for missing dates, times, offsets, duration or answers instead of guessing them. No planned/future
workouts. Do not invent calories, HR or strain. Do not put recommendations into the user's journal.
Only journal and workout creation are allowed. No automatic edits/deletion, settings changes or strap
commands. All queries remain read-only; these actions are a separate user-confirmed write boundary.
"""
    }
}
