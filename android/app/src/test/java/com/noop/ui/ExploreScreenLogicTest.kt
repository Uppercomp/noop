package com.noop.ui

import com.noop.R
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Locale

/**
 * Pins the Explore (metric explorer) screen's window set and its window captions against the iOS twin
 * (`Strand/Screens/MetricExplorerView.swift`).
 *
 * What this covers was live on Android as hardcoded English: the range pills and — worse, because it sits
 * inside translated sentences — the range NAME fed to "N readings · …", "vs prev …" and the hero subtitle,
 * so a German app read "90 Messwerte · quarter". It also pins the window SET, because iOS offers eight
 * windows (2W and 3W included) where Android offered six.
 */
class ExploreScreenLogicTest {

    private val savedLocale = Locale.getDefault()
    @Before fun pinLocale() { Locale.setDefault(Locale.US) }
    @After fun restoreLocale() { Locale.setDefault(savedLocale) }

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

    @Test
    fun theWindowSetMatchesTheIOSTwin() {
        assertEquals(
            listOf(7, 14, 21, 30, 90, 180, 365, null),
            ExploreRange.entries.map { it.days },
        )
        // "quarter" is the twin's own word for the 90-day window (not "3 months"), and "all time" for ALL.
        assertEquals("quarter", english("l10n_trends_explore_screen_quarter_7fa1f943"))
        assertEquals("all time", english("l10n_trends_explore_screen_all_time_5ae5c422"))
        assertEquals("2W", english("l10n_trends_explore_screen_s_2w_5b6401e2"))
    }

    /**
     * The auto-widen chain is deliberately the twin's own list, which OMITS the short week windows: those
     * widen straight to ALL. Pinned because a "sensible" reordering would silently change the caption the
     * widen path prints.
     */
    @Test
    fun wideningFollowsTheIOSTwinIncludingItsShortWindowShortcut() {
        assertEquals(
            listOf(ExploreRange.Week, ExploreRange.Month, ExploreRange.Quarter, ExploreRange.Half, ExploreRange.Year, ExploreRange.All),
            ExploreRange.Week.widening,
        )
        assertEquals(listOf(ExploreRange.All), ExploreRange.TwoWeeks.widening)
        assertEquals(listOf(ExploreRange.All), ExploreRange.ThreeWeeks.widening)
        assertEquals(listOf(ExploreRange.All), ExploreRange.All.widening)
    }

    @Test
    fun rangeCaptionCountsReadingsAndNestsTheLocalisedName() {
        assertEquals(DisplayText.Dynamic("—"), exploreRangeCaption(0, fellBack = false, range = ExploreRange.Month, loaded = true))
        assertEquals(
            DisplayText.Resource(R.string.l10n_apple_health_screen_s_1_reading_f666b026, listOf(1, DisplayText.Resource(ExploreRange.Week.nameRes))),
            exploreRangeCaption(1, fellBack = false, range = ExploreRange.Week, loaded = true),
        )
        assertEquals(
            DisplayText.Resource(
                R.string.l10n_apple_health_screen_lld_readings_sparse_widened_to_a404132d,
                listOf(9, DisplayText.Resource(ExploreRange.Year.nameRes)),
            ),
            exploreRangeCaption(9, fellBack = true, range = ExploreRange.Year, loaded = true),
        )
        assertEquals("%1\$d readings · %2\$s", english("l10n_apple_health_screen_lld_readings_b7cee48b"))
    }

    /** The hero subtitle's widened form puts the count LAST — the twin's own word order. */
    @Test
    fun heroSubtitleUsesTheTwinWordOrder() {
        assertEquals(
            DisplayText.Resource(
                R.string.l10n_apple_health_screen_lld_readings_b7cee48b,
                listOf(30, DisplayText.Resource(ExploreRange.Month.nameRes)),
            ),
            exploreHeroSubtitle(30, fellBack = false, range = ExploreRange.Month),
        )
        assertEquals(
            DisplayText.Resource(
                R.string.l10n_trends_explore_screen_sparse_widened_to_lld_readings_22d2aee1,
                listOf(DisplayText.Resource(ExploreRange.Quarter.nameRes), 4),
            ),
            exploreHeroSubtitle(4, fellBack = true, range = ExploreRange.Quarter),
        )
        assertEquals(
            "Sparse, widened to %1\$s · %2\$d readings",
            english("l10n_trends_explore_screen_sparse_widened_to_lld_readings_22d2aee1"),
        )
    }

    /** Explore's dates are in the ACTIVE app language (unlike the per-source pages' fixed English). */
    @Test
    fun asOfCaptionReadsTheDayInTheActiveLanguage() {
        assertEquals("14 Aug 2026", exploreLongDay("2026-08-14"))
        assertEquals(
            DisplayText.Resource(R.string.l10n_apple_health_screen_as_of_daedbd14, listOf("14 Aug 2026")),
            exploreAsOfCaption("2026-08-14"),
        )
        assertEquals(DisplayText.Dynamic("—"), exploreAsOfCaption(null))
        assertEquals("as of %1\$s", english("l10n_apple_health_screen_as_of_daedbd14"))
    }

    /** The range's own name is what the pills-adjacent sentences print; it must be a resource, not text. */
    @Test
    fun everyRangeCarriesLocalisedLabelsAndNames() {
        for (range in ExploreRange.entries) {
            assertTrue("label for $range", range.labelRes != 0)
            assertTrue("name for $range", range.nameRes != 0)
        }
        assertEquals("week", english("l10n_apple_health_screen_week_c0ee32d8"))
        assertEquals("6 months", english("l10n_apple_health_screen_s_6_months_cd75b86f"))
    }
}
