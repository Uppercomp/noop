package com.noop.ui

import com.noop.R
import com.noop.data.AppleDaily
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Pins the Apple Health per-source page's WINDOWING and COPY against the iOS twin
 * (`Strand/Screens/AppleHealthView.swift`), which is the reference for both.
 *
 * Two classes of defect this covers were live on Android before this test existed:
 *
 *  * the window was a ROW COUNT (`takeLast(n)`), not the day distance iOS measures from the latest point.
 *    On the sparse series the data contract calls out (weekly weight / body fat) a "Week" window showed
 *    points from months ago, and because such a window could never come back empty the auto-widen path —
 *    and with it the "sparse, widened to …" caption — was unreachable.
 *  * every caption/sentence was composed in English in Kotlin, so the page showed English inside an
 *    otherwise translated app while iOS showed the catalog's translated sentence.
 *
 * The English wording is asserted from the shipped default resources (the idiom MainSleepReasonCopyTest
 * and AdvertisingNameProbeTest already use), because that English is what the iOS catalog key is
 * byte-identical TO.
 */
class AppleHealthScreenLogicTest {

    private fun point(day: String, value: Double = 1.0) = HealthPoint(day, value)

    private fun row(day: String) = AppleDaily(deviceId = "apple-health", day = day)

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

    private val rangeSummaryPlural = "l10n_apple_health_screen_lld_days_5a3d1088"
    private val rangeSummarySingular = "l10n_apple_health_screen_s_1_day_dbc46f50"
    private val rangeSummaryWidened = "l10n_apple_health_screen_lld_days_some_sparse_series_widened_30e17ab4"
    private val rangeNoteWidened = "l10n_apple_health_screen_lld_readings_sparse_widened_to_a404132d"
    private val noDataSentence =
        "l10n_apple_health_screen_steps_heart_sleep_body_composition_and_v_672ccc32"

    // MARK: - Windowing

    /** The window is a DAY DISTANCE from the latest point, so an older outlier inside the row budget drops. */
    @Test
    fun windowIsDayDistanceNotRowCount() {
        val series = listOf(point("2026-06-01")) +
            (14..20).map { point("2026-08-%02d".format(it)) }
        val week = series.windowFor(SourceRange.Week)
        assertEquals(7, week.size)
        assertEquals("2026-08-14", week.first().day)
        assertEquals("2026-08-20", week.last().day)
    }

    /** Anchored to the LATEST point, never to "now": a window over stale history still holds its rows. */
    @Test
    fun windowIsAnchoredToTheLatestPoint() {
        val series = listOf(point("2020-01-01"), point("2020-01-03"))
        val week = series.windowFor(SourceRange.Week)
        assertEquals(listOf("2020-01-01", "2020-01-03"), week.map { it.day })
    }

    /** ALL ignores the window entirely; a malformed LATEST day leaves no anchor, so the window is empty. */
    @Test
    fun allKeepsEverythingAndAMalformedLatestDayLeavesNoAnchor() {
        val series = listOf(point("2026-08-14"), point("not-a-day"))
        assertEquals(2, series.windowFor(SourceRange.All).size)
        // iOS anchors a window on `latestDate(key)` and returns [] when that day will not parse, so this
        // is the twin's behaviour rather than an accident: an unreadable anchor means no window at all.
        assertEquals(emptyList<HealthPoint>(), series.windowFor(SourceRange.Week))
    }

    /**
     * A window is anchored ON the latest point, so that point is always inside it: a sparse series shows
     * its single newest reading under the selected range and does NOT claim a widen. That is the iOS twin's
     * behaviour too — its own `fellBack` branch needs a window that comes back empty, which an ascending
     * series anchored on its own latest point cannot produce.
     */
    @Test
    fun sparseSeriesKeepsItsLatestPointUnderTheSelectedRange() {
        val series = listOf(point("2026-06-01"), point("2026-08-14"))
        assertEquals(listOf("2026-08-14"), series.windowFor(SourceRange.Week).map { it.day })
        val resolved = resolve(series, SourceRange.Week)
        assertEquals(SourceRange.Week, resolved.effective)
        assertEquals(1, resolved.rows.size)
        assertEquals(false, resolved.fellBack)
    }

    /** The one path that does widen: no window can produce rows, so the key falls back to ALL and says so. */
    @Test
    fun anUnreadableAnchorFallsBackToAll() {
        val series = listOf(point("2026-06-01"), point("not-a-day"))
        val resolved = resolve(series, SourceRange.Week)
        assertEquals(SourceRange.All, resolved.effective)
        assertEquals(2, resolved.rows.size)
        assertEquals(true, resolved.fellBack)
    }

    /** An EMPTY series stays on the requested range, so the card reads "0 readings" rather than a false widen. */
    @Test
    fun emptySeriesNeverClaimsAWiden() {
        val resolved = resolve(emptyList(), SourceRange.Month)
        assertEquals(SourceRange.Month, resolved.effective)
        assertEquals(0, resolved.rows.size)
        assertEquals(false, resolved.fellBack)
    }

    /** Per-day rows window the same way (the header span and range caption read THESE, not a series). */
    @Test
    fun perDayRowsWindowByDayDistance() {
        val rows = (1..20).map { row("2026-08-%02d".format(it)) }
        val week = appleHealthWindowedRows(rows, SourceRange.Week)
        assertEquals(7, week.size)
        assertEquals("2026-08-14", week.first().day)
        assertEquals(20, appleHealthWindowedRows(rows, SourceRange.All).size)
    }

    // MARK: - Composed captions

    @Test
    fun rangeSummaryPicksTheCountVariantAndNestsTheRangeName() {
        val plural = DisplayText.Resource(
            R.string.l10n_apple_health_screen_lld_days_5a3d1088,
            listOf(3, DisplayText.Resource(SourceRange.Quarter.nameRes)),
        )
        assertEquals(plural, sourceRangeSummary(3, widened = false, range = SourceRange.Quarter))
        assertEquals("%1\$d days · %2\$s", english(rangeSummaryPlural))

        val singular = DisplayText.Resource(
            R.string.l10n_apple_health_screen_s_1_day_dbc46f50,
            listOf(1, DisplayText.Resource(SourceRange.Week.nameRes)),
        )
        assertEquals(singular, sourceRangeSummary(1, widened = false, range = SourceRange.Week))
        assertEquals("1 day · %1\$s", english(rangeSummarySingular))

        val widened = DisplayText.Resource(
            R.string.l10n_apple_health_screen_lld_days_some_sparse_series_widened_30e17ab4,
            listOf(2, DisplayText.Resource(SourceRange.Half.nameRes)),
        )
        assertEquals(widened, sourceRangeSummary(2, widened = true, range = SourceRange.Half))
        assertEquals("%1\$d days · %2\$s · some sparse series widened", english(rangeSummaryWidened))
    }

    @Test
    fun chartNoteCountsReadingsNotDays() {
        assertEquals(
            DisplayText.Resource(
                R.string.l10n_apple_health_screen_lld_readings_b7cee48b,
                listOf(12, DisplayText.Resource(SourceRange.Month.nameRes)),
            ),
            sourceRangeNote(12, widened = false, range = SourceRange.Month),
        )
        assertEquals(
            DisplayText.Resource(
                R.string.l10n_apple_health_screen_s_1_reading_f666b026,
                listOf(1, DisplayText.Resource(SourceRange.Week.nameRes)),
            ),
            sourceRangeNote(1, widened = false, range = SourceRange.Week),
        )
        assertEquals(
            DisplayText.Resource(
                R.string.l10n_apple_health_screen_s_1_reading_sparse_widened_to_c686d83b,
                listOf(1, DisplayText.Resource(SourceRange.Year.nameRes)),
            ),
            sourceRangeNote(1, widened = true, range = SourceRange.Year),
        )
        assertEquals("%1\$d readings · sparse, widened to %2\$s", english(rangeNoteWidened))
    }

    /** The header span carries dates in the iOS twin's `en_US_POSIX` form, whatever the device language. */
    @Test
    fun spanSubtitleUsesTheWindowedSpanInTheAppleDateFormat() {
        val rows = (1..20).map { row("2026-08-%02d".format(it)) }
        assertEquals(
            DisplayText.Resource(
                R.string.l10n_apple_health_screen_lld_days_5a3d1088,
                listOf(7, "14 Aug 2026 → 20 Aug 2026"),
            ),
            appleHealthSpanSubtitle(rows, SourceRange.Week),
        )
    }

    @Test
    fun spanSubtitleCollapsesASingleDayAndFallsBackToTheReadsLocallySentence() {
        assertEquals(
            DisplayText.Resource(
                R.string.l10n_apple_health_screen_lld_days_5a3d1088,
                listOf(1, "14 Aug 2026"),
            ),
            appleHealthSpanSubtitle(listOf(row("2026-08-14")), SourceRange.Week),
        )
        assertEquals(
            DisplayText.Resource(R.string.l10n_apple_health_screen_steps_heart_sleep_body_composition_and_v_672ccc32),
            appleHealthSpanSubtitle(emptyList(), SourceRange.Week),
        )
        assertEquals(
            "Steps, heart, sleep, body composition and VO₂ max, read locally on this phone.",
            english(noDataSentence),
        )
    }

    @Test
    fun asOfCaptionPrintsTheAppleDateFormat() {
        assertEquals(
            DisplayText.Resource(R.string.l10n_apple_health_screen_as_of_daedbd14, listOf("14 Aug")),
            sourceAsOfCaption("2026-08-14"),
        )
        assertEquals("as of %1\$s", english("l10n_apple_health_screen_as_of_daedbd14"))
    }

    /** Every range's three labels are the catalog's, and the pill order is W / M / 3M / 6M / 1Y / ALL. */
    @Test
    fun rangesCarryTheirCatalogLabels() {
        assertEquals(
            listOf(7, 30, 90, 180, 365, null),
            SourceRange.entries.map { it.days },
        )
        assertEquals("7 DAYS", english("l10n_apple_health_screen_s_7_days_ba6e98ec"))
        assertEquals("ALL TIME", english("l10n_apple_health_screen_all_time_85c78b60"))
        assertEquals("3 months", english("l10n_apple_health_screen_s_3_months_b9e4e6ff"))
        // The widening search is this range plus every LARGER one, ascending — Week never widens DOWN.
        assertEquals(
            listOf(SourceRange.Month, SourceRange.Quarter, SourceRange.Half, SourceRange.Year, SourceRange.All),
            SourceRange.Month.widening,
        )
    }
}
