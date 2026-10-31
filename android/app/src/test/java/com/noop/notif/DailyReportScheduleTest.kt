package com.noop.notif

import java.time.ZonedDateTime
import org.junit.Assert.*
import org.junit.Test

class DailyReportScheduleTest {
    @Test fun beforeAtAndAfterScheduledMinute() {
        val before = ZonedDateTime.parse("2026-10-31T07:59:00+05:30[Asia/Kolkata]")
        assertEquals("2026-10-31T08:00+05:30[Asia/Kolkata]", DailyReportSchedule.next(before, 480).toString())
        assertEquals("2026-11-01T08:00+05:30[Asia/Kolkata]", DailyReportSchedule.next(before.plusMinutes(1), 480).toString())
    }
    @Test fun localScheduleSurvivesDstChange() {
        val now = ZonedDateTime.parse("2026-10-31T21:01:00-04:00[America/New_York]")
        val next = DailyReportSchedule.next(now, 21 * 60)
        assertEquals(21, next.hour)
        assertEquals(25 * 60 - 1, java.time.Duration.between(now, next).toMinutes().toInt())
    }
    @Test fun tomorrowKeepsRequestedTimeAfterSpringGap() {
        val now = ZonedDateTime.parse("2026-03-08T04:00:00-04:00[America/New_York]")
        val next = DailyReportSchedule.next(now, 150)
        assertEquals("2026-03-09T02:30-04:00[America/New_York]", next.toString())
    }
}
