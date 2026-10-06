package com.noop.ui

import com.noop.R
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit tests for [relativeAgo], the pure helper behind the Live "History synced N ago" sync-status
 * line (B6 sync-status surfacing). Buckets to just-now / minutes / hours / days; clamps future times.
 *
 * The expectations are RESOURCES, not words: a localised helper cannot return a resolved String in a
 * JVM test (resolving one needs the Application), and asserting the resource pins which sentence the
 * helper chose — the decision — while the translations live in `strings.xml`.
 */
class RelativeAgoTest {

    private val now = 1_781_000_000L

    private fun ago(sec: Long) = relativeAgo(now - sec, now)

    private fun minutes(n: Long) =
        DisplayText.Resource(R.string.l10n_live_screen_lld_min_ago_78433dfd, listOf(n))

    private fun hours(n: Long) =
        DisplayText.Resource(R.string.l10n_live_screen_lld_h_ago_64748239, listOf(n))

    private fun days(n: Long) =
        DisplayText.Resource(R.string.l10n_live_screen_lld_d_ago_8ead2956, listOf(n))

    private val justNow = DisplayText.Resource(R.string.l10n_live_screen_just_now_a7f8e7cc)

    @Test fun underAMinuteIsJustNow() {
        assertEquals(justNow, ago(0))
        assertEquals(justNow, ago(59))
    }

    @Test fun minutes() {
        assertEquals(minutes(1), ago(60))
        assertEquals(minutes(5), ago(5 * 60))
        assertEquals(minutes(59), ago(59 * 60))
    }

    @Test fun hours() {
        assertEquals(hours(1), ago(3600))
        assertEquals(hours(23), ago(23 * 3600))
    }

    @Test fun days() {
        assertEquals(days(1), ago(86_400))
        assertEquals(days(3), ago(3 * 86_400))
    }

    @Test fun futureTimestampClampsToJustNow() {
        // A strap-clock skew could put lastSyncAt slightly in the future; never render negative.
        assertEquals(justNow, relativeAgo(now + 500, now))
    }
}
