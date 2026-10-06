package com.noop.ui

import com.noop.R
import com.noop.data.SleepSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Pins the Mi Band per-source page against its iOS twin (`Strand/Screens/XiaomiBandView.swift`): the page's
 * chart/header ORDER, the header span shape that page alone uses, and the hypnogram decode.
 *
 * The page is mostly the shared per-source chrome, so what is specific — and what this test exists for — is
 * exactly the three places the iOS twin DIFFERS from the Apple Health page: the subtitle carries no day
 * count and is not windowed, the window caption under the pills is the range's name alone, and the page
 * decodes imported Mi sleep stages into the hypnogram.
 */
class XiaomiBandScreenLogicTest {

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

    // MARK: - The page's shape

    @Test
    fun theChartKeysAreTheOnesTheImporterWrites() {
        assertEquals(
            listOf(
                "steps", "distance_m", "energy_kcal", "intensity_min",
                "rhr", "avg_hr", "max_hr", "spo2",
                "sleep_total_min", "sleep_deep_min", "sleep_rem_min", "sleep_light_min", "sleep_score",
                "stress", "vitality",
            ),
            XIAOMI_SERIES_KEYS,
        )
    }

    /** Headers land before their group, and the hypnogram card sits between Blood oxygen and the Sleep header. */
    @Test
    fun pageItemsFollowTheIOSTwinOrder() {
        val items = xiaomiPageItems()
        val keys = items.filterIsInstance<XiaomiPageItem.Chart>().map { it.key }
        assertEquals(
            listOf(
                "rhr", "avg_hr", "max_hr", "spo2",
                "sleep_total_min", "sleep_deep_min", "sleep_rem_min", "sleep_score",
                "steps", "distance_m", "energy_kcal", "intensity_min",
                "stress", "vitality",
            ),
            keys,
        )
        val headers = items.filterIsInstance<XiaomiPageItem.Header>()
        assertEquals(
            listOf(
                R.string.l10n_apple_health_screen_heart_vitals_b789ad36,
                R.string.nav_sleep,
                R.string.l10n_apple_health_screen_activity_energy_1c075b75,
                R.string.l10n_xiaomi_band_screen_wellbeing_a85b236d,
            ),
            headers.map { it.titleRes },
        )
        // The overlines are the twin's own second tuple element: "Cardiac", "Rest", "Movement", and the
        // one English-only "Body energy" (which Apple renders as a plain String there).
        assertEquals(
            listOf(
                R.string.l10n_apple_health_screen_cardiac_a0148244,
                R.string.trends_rest,
                R.string.timeline_metric_movement,
                R.string.l10n_xiaomi_band_screen_body_energy_64d0f810,
            ),
            headers.map { it.overlineRes },
        )
        // The hypnogram is the LAST item of the Cardiac group: after Blood oxygen, before "Sleep".
        val hypnogramAt = items.indexOf(XiaomiPageItem.Hypnogram)
        val sleepHeaderAt = items.indexOfFirst { it is XiaomiPageItem.Header && it.titleRes == R.string.nav_sleep }
        assertEquals("spo2", (items[hypnogramAt - 1] as XiaomiPageItem.Chart).key)
        assertEquals(hypnogramAt + 1, sleepHeaderAt)
    }

    // MARK: - The span subtitle (no day count, not windowed)

    @Test
    fun spanSubtitleIsTheWholeSpanWithNoDayCount() {
        assertEquals(
            DisplayText.Dynamic("14 Aug 2026 → 2 Oct 2026"),
            xiaomiSpanSubtitle(listOf("2026-10-02", "2026-08-14", "2026-09-01")),
        )
        assertEquals(DisplayText.Dynamic("14 Aug 2026"), xiaomiSpanSubtitle(listOf("2026-08-14")))
    }

    /** With nothing loaded the page names what it reads — and that sentence carries NO placeholder. */
    @Test
    fun spanSubtitleFallsBackToTheReadsLocallySentence() {
        assertEquals(
            DisplayText.Resource(R.string.l10n_xiaomi_band_screen_steps_heart_rate_sleep_spo_and_stress_im_8df17cc1),
            xiaomiSpanSubtitle(emptyList()),
        )
        val englishSentence = english("l10n_xiaomi_band_screen_steps_heart_rate_sleep_spo_and_stress_im_8df17cc1")
        assertTrue(
            "the device noun is baked in; an Apple %@ left here would throw when resolved",
            !englishSentence.contains("%@") && !englishSentence.contains("%lld"),
        )
        assertTrue(englishSentence.endsWith("read locally on this phone."))
    }

    // MARK: - The window caption (the range's name, not a day count)

    @Test
    fun rangeSummaryIsJustTheRangeNameUnlessASeriesWidened() {
        assertEquals(
            DisplayText.Resource(SourceRange.Quarter.nameRes),
            xiaomiRangeSummary(widened = false, range = SourceRange.Quarter),
        )
        assertEquals(
            DisplayText.Resource(
                R.string.l10n_xiaomi_band_screen_some_sparse_series_widened_bcc732b8,
                listOf(DisplayText.Resource(SourceRange.Week.nameRes)),
            ),
            xiaomiRangeSummary(widened = true, range = SourceRange.Week),
        )
        assertEquals(
            "%1\$s · some sparse series widened",
            english("l10n_xiaomi_band_screen_some_sparse_series_widened_bcc732b8"),
        )
    }

    // MARK: - Chart value formats (the iOS twin's per-card closures)

    @Test
    fun chartFormatsMatchTheIOSTwin() {
        assertEquals(DisplayText.Dynamic("58 bpm"), xiaomiFormat(XiaomiValueFormat.Bpm, 58.4, UnitSystem.METRIC))
        assertEquals(DisplayText.Dynamic("97%"), xiaomiFormat(XiaomiValueFormat.Spo2Percent, 97.4, UnitSystem.METRIC))
        // Duration and the "min" unit are translated (ru "…ч …м", zh "…小时…分"), so they come back as the
        // resource the reader's language resolves.
        assertEquals(
            DisplayText.Resource(R.string.l10n_source_page_copy_lldh_lldm_05a73922, listOf(7, 12)),
            xiaomiFormat(XiaomiValueFormat.Duration, 432.0, UnitSystem.METRIC),
        )
        assertEquals(
            DisplayText.Resource(R.string.l10n_source_page_copy_lldm_f8b297ab, listOf(45)),
            xiaomiFormat(XiaomiValueFormat.Duration, 45.0, UnitSystem.METRIC),
        )
        assertEquals(DisplayText.Dynamic("512"), xiaomiFormat(XiaomiValueFormat.Whole, 511.6, UnitSystem.METRIC))
        assertEquals(DisplayText.Dynamic("1,204 kcal"), xiaomiFormat(XiaomiValueFormat.Kcal, 1204.0, UnitSystem.METRIC))
        assertEquals(
            DisplayText.Resource(R.string.l10n_xiaomi_band_screen_lld_min_b6190882, listOf(42)),
            xiaomiFormat(XiaomiValueFormat.Minutes, 41.7, UnitSystem.METRIC),
        )
        // Distance follows the exercise-distance preference, not the body-measurement one.
        assertEquals(DisplayText.Dynamic("5.0 km"), xiaomiFormat(XiaomiValueFormat.Distance, 5000.0, UnitSystem.METRIC))
        assertTrue(
            (xiaomiFormat(XiaomiValueFormat.Distance, 5000.0, UnitSystem.IMPERIAL) as DisplayText.Dynamic)
                .value.contains("mi"),
        )
    }

    // MARK: - The imported night's hypnogram

    private fun stagesJSON(vararg segments: Triple<Long, Long, String>) =
        segments.joinToString(prefix = "[", postfix = "]") { (start, end, stage) ->
            """{"start":$start,"end":$end,"stage":"$stage"}"""
        }

    @Test
    fun stagesFoldWakeAndAwakeIntoOneBucketAndDropUnknownStages() {
        val json = stagesJSON(
            Triple(0L, 600L, "light"),
            Triple(600L, 1200L, "deep"),
            Triple(1200L, 1800L, "rem"),
            Triple(1800L, 2400L, "wake"),
            Triple(2400L, 3000L, "awake"),
            Triple(3000L, 3600L, "some-new-stage"),
        )
        val stages = xiaomiStages(json)
        assertNotNull(stages)
        requireNotNull(stages)
        assertEquals(10.0, stages.lightMin, 0.001)
        assertEquals(10.0, stages.deepMin, 0.001)
        assertEquals(10.0, stages.remMin, 0.001)
        assertEquals(20.0, stages.awakeMin, 0.001)
        assertEquals(30.0, stages.asleepMin, 0.001)
        assertEquals(listOf("wake", "light", "deep", "rem"), stages.strip.map { it.first })
    }

    @Test
    fun stagesAreNullWhenThereIsNoAsleepTimeOrNoSegments() {
        assertNull(xiaomiStages(null))
        // A wake-only night, and an imported minutes-dict night: neither is a hypnogram.
        assertNull(xiaomiStages(stagesJSON(Triple(0L, 600L, "wake"))))
        assertNull(xiaomiStages("""{"awake":10,"light":240,"deep":60,"rem":90}"""))
    }

    @Test
    fun lastStagedNightPicksTheMostRecentNightThatHasStages() {
        val withStages = stagesJSON(Triple(0L, 600L, "light"), Triple(600L, 1200L, "deep"))
        val dictOnly = """{"awake":10,"light":240,"deep":60,"rem":90}"""
        val older = SleepSession(deviceId = "xiaomi-band", startTs = 100L, endTs = 500L, stagesJSON = withStages)
        val newerWithout = SleepSession(deviceId = "xiaomi-band", startTs = 900L, endTs = 1000L, stagesJSON = dictOnly)
        assertEquals(older, lastStagedNight(listOf(older, newerWithout)))
        assertNull(lastStagedNight(listOf(newerWithout)))
    }
}
