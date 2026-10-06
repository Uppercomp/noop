package com.noop.ui

import com.noop.R
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Pins the Compare screen's composed captions against the iOS twin (`Strand/Screens/CompareView.swift`).
 *
 * Both sentences are whole-phrase variants per count there, and the Android versions were Kotlin-stitched
 * English: the range caption read "30 readings across 3 · the last 7 days" whatever the language, and the
 * overlay subtitle never mentioned the gesture that reveals a real value, which iOS's own `inspectHint`
 * line does ("tap or drag for real values" on a touch device).
 */
class CompareCopyTest {

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
    fun rangeCaptionCountsReadingsAcrossSeriesAndNestsThePhrase() {
        assertEquals(
            DisplayText.Resource(
                R.string.l10n_compare_screen_lld_readings_across_lld_dfcfe5bb,
                listOf(30, 3, DisplayText.Resource(CompareRange.Week.phraseRes)),
            ),
            compareRangeCaption(total = 30, seriesCount = 3, widened = false, range = CompareRange.Week),
        )
        assertEquals("%1\$d readings across %2\$d · %3\$s", english("l10n_compare_screen_lld_readings_across_lld_dfcfe5bb"))
        assertEquals(
            DisplayText.Resource(
                R.string.l10n_compare_screen_s_1_reading_across_lld_61f778db,
                listOf(1, 2, DisplayText.Resource(CompareRange.Year.phraseRes)),
            ),
            compareRangeCaption(total = 1, seriesCount = 2, widened = false, range = CompareRange.Year),
        )
        assertEquals("1 reading across %1\$d · %2\$s", english("l10n_compare_screen_s_1_reading_across_lld_61f778db"))
    }

    @Test
    fun rangeCaptionFlagsAWidenInItsOwnPhrase() {
        assertEquals(
            DisplayText.Resource(
                R.string.l10n_compare_screen_lld_readings_across_lld_sparse_widened_27763792,
                listOf(4, 2, DisplayText.Resource(CompareRange.All.phraseRes)),
            ),
            compareRangeCaption(total = 4, seriesCount = 2, widened = true, range = CompareRange.All),
        )
        assertEquals(
            "%1\$d readings across %2\$d · %3\$s · sparse widened",
            english("l10n_compare_screen_lld_readings_across_lld_sparse_widened_27763792"),
        )
    }

    /** The overlay subtitle names the window AND the gesture, in the twin's two whole-phrase variants. */
    @Test
    fun overlaySubtitleCarriesTheWindowAndTheInspectHint() {
        val hint = DisplayText.Resource(R.string.l10n_compare_screen_tap_or_drag_for_real_values_3e56560b)
        assertEquals(
            DisplayText.Resource(
                R.string.l10n_compare_screen_each_line_min_max_normalized_within_7cf9ef5b,
                listOf(DisplayText.Resource(CompareRange.Month.phraseRes), hint),
            ),
            compareOverlaySubtitle(widened = false, range = CompareRange.Month),
        )
        assertEquals(
            DisplayText.Resource(
                R.string.l10n_compare_screen_min_max_normalized_sparse_series_widened_fedc6850,
                listOf(DisplayText.Resource(CompareRange.Month.phraseRes), hint),
            ),
            compareOverlaySubtitle(widened = true, range = CompareRange.Month),
        )
        assertEquals("tap or drag for real values", english("l10n_compare_screen_tap_or_drag_for_real_values_3e56560b"))
    }

    /** The range words are the catalog's, not Kotlin's: label AND the sentence phrase both resolve. */
    @Test
    fun rangeWordsAreResourceBacked() {
        assertEquals(
            listOf(7, 30, 90, 180, 365, null),
            CompareRange.entries.map { it.days },
        )
        assertEquals("the last 7 days", english("l10n_compare_screen_the_last_7_days_02bc1e5f"))
        assertEquals("1 year", english("l10n_compare_screen_s_1_year_afe36da6"))
        assertEquals("30 days", english("l10n_compare_screen_s_30_days_7d4278a8"))
    }
}
