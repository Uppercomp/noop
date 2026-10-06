package com.noop.ui

import com.noop.R
import com.noop.analytics.SleepStageTotals
import com.noop.data.SleepSession
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.TimeZone

/**
 * Pins the VERBATIM "why this is your main sleep" copy (COMPONENT 1) the Sleep screen renders for each
 * foundation [SleepStageTotals.MainNightReason] branch, and the {DUR} fill. The strings are a hard
 * cross-platform contract — they MUST be byte-identical to iOS SleepView.mainSleepReasonText.
 *
 * Two things are asserted per branch, because the copy now lives in a resource: WHICH sentence the helper
 * chose (the resource and its arguments — a pure helper a JVM test calls cannot resolve one itself), and
 * the ENGLISH wording in `values/strings.xml`, which is what the iOS twin is byte-identical TO. That keeps
 * the reword guard the test was written for (the strings.xml read is the idiom AdvertisingNameProbeTest
 * and StepsMotionCacheTest already use in this repo).
 *
 * [mainSleepReasonText] resolves the reason via [SleepStageTotals.mainNightSelection] using
 * `uiTzOffsetSec()` (the device default tz), so the timezone is pinned to UTC here — making the local
 * time-of-day of each block's midpoint equal its UTC time-of-day, exactly matching the `offsetSec = 0L`
 * foundation fixtures in MainNightConsistencyTest. One case per reason branch + the empty-day null.
 */
class MainSleepReasonCopyTest {

    private val saved = TimeZone.getDefault()
    @Before fun pinUtc() { TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }
    @After fun restore() { TimeZone.setDefault(saved) }

    /** An arbitrary fixed UTC midnight (ref % 86400 == 0) so local == UTC under the pinned tz. */
    private val refMidnight = 1_749_513_600L
    private fun atHour(hour: Int): Long = refMidnight + hour * 3_600L
    private fun sod(hour: Int, min: Int): Long = hour * 3_600L + min * 60L
    private fun block(start: Long, durSec: Long) =
        SleepSession(deviceId = "my-whoop-noop", startTs = start, endTs = start + durSec)

    /** The English value of a resource, read from the shipped default resources. */
    private fun english(name: String): String {
        var root = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (!File(root, "app/src/main/res/values/strings.xml").isFile) {
            root = root.parentFile ?: error("strings.xml not found from ${System.getProperty("user.dir")}")
        }
        val xml = File(root, "app/src/main/res/values/strings.xml").readText()
        val match = Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(xml)
            ?: error("no English value for $name")
        return match.groupValues[1].replace("\\'", "'")
    }

    private val onlyBlockKey = "l10n_sleep_screen_this_is_your_only_sleep_block_today_fa7e6772"
    private val longestKey = "l10n_sleep_screen_picked_as_your_main_sleep_because_it_was_a93dabf2"
    private val longestNearUsualKey = "l10n_sleep_screen_picked_as_your_main_sleep_because_it_was_393f2937"
    private val alignedKey = "l10n_sleep_screen_picked_as_your_main_sleep_because_it_sta_9de516d3"

    @Test
    fun emptyDayHasNoReason() {
        assertNull(mainSleepReasonText(emptyList(), null))
    }

    /** onlyBlock: a single block, with {DUR} filled from its asleep span (7h 12m — unused by this branch). */
    @Test
    fun onlyBlockCopy() {
        val night = atHour(23) - 86_400L
        val reason = mainSleepReasonText(listOf(block(night, 7 * 3600L + 12 * 60L)), null)
        assertEquals(DisplayText.Resource(R.string.l10n_sleep_screen_this_is_your_only_sleep_block_today_fa7e6772), reason)
        assertEquals("This is your only sleep block today.", english(onlyBlockKey))
    }

    /** longest (cold-start, null habitual): the longest block wins on duration; {DUR} = 6h 0m. */
    @Test
    fun longestColdStartCopy() {
        val a = atHour(22) - 86_400L
        val b = atHour(23) - 86_400L + 1_800L
        val blocks = listOf(block(a, 3 * 3600L), block(b, 6 * 3600L))
        val reason = mainSleepReasonText(blocks, null) // null habitual = cold-start
        assertEquals(
            DisplayText.Resource(
                R.string.l10n_sleep_screen_picked_as_your_main_sleep_because_it_was_a93dabf2,
                listOf(DisplayText.Resource(R.string.l10n_source_page_copy_lldh_lldm_05a73922, listOf(6, 0))),
            ),
            reason,
        )
        assertEquals(
            "Picked as your main sleep because it was your longest block (%1\$s).",
            english(longestKey),
        )
    }

    /** longestNearUsual: learned habitual present, the longest block is also bonus-aligned; {DUR} = 6h 0m. */
    @Test
    fun longestNearUsualCopy() {
        val habitual = sod(3, 0)
        val night = atHour(23) - 86_400L // 6h, mid 02:00 — longest AND ~1h from the 03:00 habitual
        val nap = atHour(15)             // 1h afternoon, far from the habitual
        val blocks = listOf(block(nap, 1 * 3600L), block(night, 6 * 3600L))
        val reason = mainSleepReasonText(blocks, habitual)
        assertEquals(
            DisplayText.Resource(
                R.string.l10n_sleep_screen_picked_as_your_main_sleep_because_it_was_393f2937,
                listOf(DisplayText.Resource(R.string.l10n_source_page_copy_lldh_lldm_05a73922, listOf(6, 0))),
            ),
            reason,
        )
        assertEquals(
            "Picked as your main sleep because it was your longest block (%1\$s), near your usual bedtime.",
            english(longestNearUsualKey),
        )
    }

    /** alignedToUsual: the alignment bonus (not raw duration) flipped the pick toward a shorter, well-timed
     *  night, so the copy drops the duration and leads with the timing. */
    @Test
    fun alignedToUsualCopy() {
        val habitual = sod(3, 0)
        val afternoon = atHour(13) // 5h, mid 15:30, bonus 0 (duration-only winner)
        val night = atHour(1)      // 4h, mid 03:00, bonus 90 → score winner
        val blocks = listOf(block(afternoon, 5 * 3600L), block(night, 4 * 3600L))
        val reason = mainSleepReasonText(blocks, habitual)
        assertEquals(
            DisplayText.Resource(R.string.l10n_sleep_screen_picked_as_your_main_sleep_because_it_sta_9de516d3),
            reason,
        )
        assertEquals("Picked as your main sleep because it started near your usual sleep time.", english(alignedKey))
    }
}
