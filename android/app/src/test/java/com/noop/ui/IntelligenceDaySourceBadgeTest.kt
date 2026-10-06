package com.noop.ui

import com.noop.R
import com.noop.data.WhoopRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Pins the By-Day source badge (Sleep overhaul §2.6). The card used to hard-code "NOOP-computed" on
 * EVERY row — even days an import won the dashboard merge — so a user couldn't tell a strap-scored
 * night from an imported one. The badge now derives from the merged DailyMetric's WINNING deviceId:
 *   - computed "<id>-noop"         → "On-device"
 *   - imported WHOOP export        → "Whoop"
 *   - apple-health / health-connect → "Apple Health"
 * Brand wording matches macOS IntelligenceEngine.DaySource.badge. Mirrors WorkoutSourceLabelTest.
 *
 * The expectations are RESOURCES, not words: `daySourceBadge` is a pure helper a JVM test calls with no
 * Application attached, so it returns the label the caller resolves (see [DisplayText]). Asserting the
 * resource also pins that the label is TRANSLATED — a raw "On-device" could not be.
 */
class IntelligenceDaySourceBadgeTest {

    private val onDevice = DisplayText.Resource(R.string.today_source_on_device)
    private val whoop = DisplayText.Resource(R.string.today_source_whoop)
    private val appleHealth = DisplayText.Resource(R.string.nav_apple_health)

    @Test
    fun computedNoopRow_isOnDevice() {
        assertEquals(onDevice, daySourceBadge("my-whoop-noop").first)
    }

    @Test
    fun anyNoopSuffix_isOnDevice() {
        // A non-default strap id keeps the "-noop" computed suffix convention.
        assertEquals(onDevice, daySourceBadge("strap-abc123-noop").first)
    }

    @Test
    fun whoopImportRow_isWhoop() {
        // The merged row keeps the imported "my-whoop" id when a WHOOP export wins the merge.
        assertEquals(whoop, daySourceBadge("my-whoop").first)
    }

    @Test
    fun appleHealthRow_isAppleHealth() {
        assertEquals(appleHealth, daySourceBadge(WhoopRepository.APPLE_HEALTH_SOURCE).first)
    }

    @Test
    fun healthConnectRow_isAppleHealth() {
        // Health Connect is the Android twin of Apple Health — same badge, not a Whoop fall-through.
        assertEquals(appleHealth, daySourceBadge(WhoopRepository.HEALTH_CONNECT_SOURCE).first)
    }

    @Test
    fun anOuraRingIsNamedOura() {
        // The ring provides its own hypnogram and banks under an "oura-<uuid>" id; it is named, not
        // folded into the generic "Whoop" the else-branch would give a non-"-noop" id.
        assertEquals(DisplayText.Dynamic("Oura"), daySourceBadge("oura-2f1c").first)
    }

    @Test
    fun computedTintDiffersFromImportTint() {
        // Computed rows keep the charge tint; imports use the accent tint so they stand out.
        assertEquals(Palette.chargeColor, daySourceBadge("my-whoop-noop").second)
        assertEquals(Palette.accent, daySourceBadge("my-whoop").second)
    }

    @Test
    fun noBadgeLabelCarriesAnEmDash() {
        for (id in listOf("my-whoop-noop", "my-whoop", "apple-health", "health-connect")) {
            val label = daySourceBadge(id).first
            val words = if (label is DisplayText.Dynamic) label.value else ""
            assertFalse("a badge label must not be a dash: $label", words.contains("—"))
        }
    }
}
