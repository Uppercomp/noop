package com.noop.ui

import com.noop.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the Workouts "Src" column badge to the real stored origins. The bug (#53): Health Connect
 * workouts showed the "Apple" pill because the badge was a binary `isWhoop ? "Whoop" : "Apple"`.
 * These cases mirror exactly what the importers write:
 *   - WhoopCsvImporter      → deviceId/source "my-whoop"
 *   - AppleHealthImporter   → deviceId "apple-health", source "Apple Health"
 *   - HealthConnectImporter → deviceId/source "health-connect"
 *
 * The expectations are the VALUES the classifier returns, not words: it is `internal` and Compose-free
 * so this test can run with no Application attached, which means it returns [DisplayText].
 */
class WorkoutSourceLabelTest {

    @Test
    fun whoop_isLabelledWhoop() {
        assertEquals(DisplayText.Resource(R.string.today_source_whoop), workoutSourceLabel("my-whoop", "my-whoop"))
    }

    @Test
    fun appleHealth_isLabelledApple() {
        // deviceId "apple-health" with the human label "Apple Health" — must NOT fall through to HC.
        assertEquals(
            DisplayText.Resource(R.string.l10n_workouts_screen_apple_476432a3),
            workoutSourceLabel("apple-health", "Apple Health"),
        )
    }

    @Test
    fun healthConnect_isLabelledHC_notApple() {
        // The regression under test: this used to come back "Apple". "HC" is Android-only short copy
        // (Health Connect has no iOS twin), so it is a Dynamic value rather than a resource.
        assertEquals(DisplayText.Dynamic("HC"), workoutSourceLabel("health-connect", "health-connect"))
    }

    @Test
    fun unknownSource_fallsBackToApple() {
        assertEquals(
            DisplayText.Resource(R.string.l10n_workouts_screen_apple_476432a3),
            workoutSourceLabel("", ""),
        )
    }
}
