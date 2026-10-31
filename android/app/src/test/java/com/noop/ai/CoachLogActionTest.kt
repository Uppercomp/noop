package com.noop.ai

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class CoachLogActionTest {
    private fun block(json: String) = "Please review before saving.\n```noop_action\n$json\n```"
    @Test fun journalProposalDoesNotGuessMissingAnswerOrDate() {
        val day = LocalDate.now().toString()
        val text = block("""{"kind":"journal","day":"$day","question":"Had caffeine","answeredYes":true,"notes":"one cup"}""")
        val parsed = CoachLogAction.parse(text) as CoachLogAction.Journal
        assertEquals("Had caffeine", parsed.question)
        assertEquals("one cup", parsed.notes)
        assertNull(CoachLogAction.parse(block("""{"kind":"journal","day":"$day","question":"Had caffeine"}""")))
        assertFalse(CoachLogAction.visibleText(text).contains("noop_action"))
        assertNull(CoachLogAction.parse(text + "\n" + text))
    }
    @Test fun workoutRequiresExplicitOffsetsValidDurationAndPastTime() {
        val valid = """{"kind":"workout","start":"2026-10-01T18:00:00+05:30","end":"2026-10-01T18:30:00+05:30","sport":"Running"}"""
        val parsed = CoachLogAction.parse(block(valid), Long.MAX_VALUE - 1000) as CoachLogAction.Workout
        assertEquals(1800, parsed.end - parsed.start)
        assertNull(CoachLogAction.parse(block(valid.replace("18:30", "17:30"))))
        assertNull(CoachLogAction.parse(block(valid.replace("+05:30", ""))))
        assertNull(CoachLogAction.parse(block(valid), 1))
        assertNull(CoachLogAction.parse(block("""{"kind":"delete"}""")))
    }
}
