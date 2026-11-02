package com.noop.ui

import android.app.Application
import androidx.room.Room
import com.noop.data.*
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class MetricLibraryTest {
    @Test fun everyNormalMetricCanBePinnedToEitherTodaySectionExactlyOnce() {
        assertEquals(MetricLibrary.all.size, MetricLibrary.all.map { it.key }.distinct().size)
        for (metric in MetricLibrary.all) {
            assertEquals("Key Metrics: ${metric.key}", 1, KeyMetric.entries.count { it.catalogKey() == metric.key })
            assertEquals("Your cards: ${metric.key}", 1, DashboardCard.entries.count { it.catalogKey() == metric.key })
        }
        assertFalse(MetricLibrary.all.any { it.key in setOf("energy_kcal", "spo2_candidate", "steps_est", "hrv_rr_overcount") })
    }

    @Test fun aliasesRestoreOldSelectionsWithoutDuplicateMetrics() {
        val restored = parseCompareSelection("my-whoop:energy_kcal,health-connect:total_kcal,my-whoop:rhr", 2, 4)!!
        assertEquals(listOf("total_kcal", "rhr"), restored.map { it.key })
        assertEquals("spo2", CompareCatalog.byId("my-whoop:spo2_candidate")!!.key)
        assertEquals(listOf("total_kcal"), parseCompareSelection("my-whoop:energy_kcal,health-connect:total_kcal", 2, 4)!!.map { it.key })
        assertNull(CompareCatalog.byId("unknown:recovery"))
        assertEquals("steps", CompareCatalog.byId("my-whoop:steps_est")!!.key)
    }

    @Test fun searchingUsesNamesAndCommonOxygenAndEnergyTerms() {
        assertTrue(MetricLibrary.matches("Blood oxygen · SpO₂", "sp02"))
        assertTrue(MetricLibrary.matches("Blood oxygen", "spo2"))
        assertTrue(MetricLibrary.matches("Energy balance (estimated)", "deficit"))
        assertTrue(MetricLibrary.matches("Calories In", "intake"))
        assertFalse(MetricLibrary.matches("Body fat", "oxygen"))
        assertTrue(MetricLibrary.matches("Heart rate variability", "hrv"))
    }

    @Test fun displayPreferencesUseExistingConvertersAcrossTheCatalog() {
        // Preserve the existing project conversion coefficient (2.20462), shared with the other views.
        assertEquals(154.3234, MetricLibrary.displayValue("weight", 70.0, UnitSystem.IMPERIAL, TemperatureUnit.CELSIUS, EffortScale.HUNDRED), 0.000001)
        assertEquals(-1.8, MetricLibrary.displayValue("skin_temp", -1.0, UnitSystem.METRIC, TemperatureUnit.FAHRENHEIT, EffortScale.HUNDRED), 0.000001)
        assertEquals("10.5 /21", MetricLibrary.find("strain")!!.format(50.0, UnitSystem.METRIC, TemperatureUnit.CELSIUS, EffortScale.WHOOP))
    }

    @Test fun optionalMetricsRoundTripWithoutChangingExistingDefaults() {
        val original = listOf(KeyMetric.HRV, KeyMetric.RESTING_HR)
        assertEquals(original, KeyMetricPrefs.decodeEnabled(KeyMetricPrefs.encode(original)))
        val selected = listOf(KeyMetric.LIB_CALORIES_IN, KeyMetric.LIB_ENERGY_BALANCE, KeyMetric.HRV)
        assertEquals(selected, KeyMetricPrefs.decodeEnabled(KeyMetricPrefs.encode(selected)))
        assertFalse(KeyMetric.LIB_ENERGY_BALANCE in KeyMetric.defaultOrder)
        assertFalse(DashboardCard.LIB_ENERGY_BALANCE in DashboardCard.defaultSelection)
    }

    @Test fun snapshotsKeepTheirActualDateAndFoodIsNeverCarriedFromAnotherDay() {
        val points = listOf("2026-11-03" to 45.0, "2026-10-31" to 40.0, "2026-10-29" to 39.0)
        assertEquals("2026-10-31" to 40.0, MetricLibrary.readingForDay("fitness_age", points, "2026-11-02"))
        assertNull(MetricLibrary.readingForDay("weight", points, "2026-10-20"))
        assertNull(MetricLibrary.readingForDay("calories_in", points, "2026-11-02"))
    }

    @Test fun intakeBalanceAndTotalUseTheSameDataAndActiveEnergyCannotBorrowTotal() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WhoopDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = WhoopRepository(db); val day = "2026-11-02"
            repo.upsertDailyMetrics(listOf(DailyMetric("my-whoop-noop", day, activeKcalEst = 2500.0)))
            repo.upsertMetricSeries(listOf(MetricSeriesRow("health-connect", day, "calories_in", 1800.0),
                MetricSeriesRow("health-connect", day, "total_kcal", 9000.0)))
            suspend fun read(k: String) = MetricLibrary.series(repo, "my-whoop", k, day, day, false)
            assertEquals(listOf(day to 1800.0), read("calories_in"))
            assertEquals(listOf(day to 2500.0), read("total_kcal"))
            assertEquals(read("total_kcal"), read("energy_kcal"))
            assertEquals(listOf(day to -700.0), read("energy_balance"))
            assertTrue(read("active_kcal").isEmpty())
            repo.upsertAppleDaily(listOf(AppleDaily("apple-health", day, activeKcal = 400.0)))
            assertEquals(listOf(day to 400.0), read("active_kcal"))
            assertEquals(listOf(day to 2500.0), read("total_kcal"))
        } finally { db.close() }
    }

    @Test fun oxygenUsesOneFallbackPolicyWithMeasuredValuesFirstAndEstimateGateRespected() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WhoopDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = WhoopRepository(db); val day = "2026-11-02"
            repo.upsertMetricSeries(listOf(MetricSeriesRow("my-whoop-noop", day, "spo2_candidate", 96.0)))
            assertTrue(MetricLibrary.series(repo, "my-whoop", "spo2", day, day, false).isEmpty())
            assertEquals(listOf(day to 96.0), MetricLibrary.series(repo, "my-whoop", "spo2", day, day, true))
            repo.upsertDailyMetrics(listOf(DailyMetric("my-whoop", day, spo2Pct = 98.0)))
            assertEquals(listOf(day to 98.0), MetricLibrary.series(repo, "my-whoop", "spo2", day, day, true))
        } finally { db.close() }
    }

    @Test fun averageStepsReuseRecordedDayWindowAndNeverFillMissingDaysWithZero() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WhoopDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = WhoopRepository(db)
            repo.upsertDailyMetrics(listOf(DailyMetric("my-whoop-noop", "2026-10-31", steps = 1000),
                DailyMetric("my-whoop-noop", "2026-11-02", steps = 3000)))
            assertEquals(listOf("2026-11-02" to 2000.0), MetricLibrary.series(repo, "my-whoop", "steps_average_30", "2026-11-02", "2026-11-02", false))
        } finally { db.close() }
    }

    @Test fun macrosUseHealthConnectWithCsvFallbackAndSourceSpecificDeletion() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WhoopDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = WhoopRepository(db); val day = "2026-11-02"
            repo.upsertMetricSeries(listOf(MetricSeriesRow("nutrition-csv", day, "protein_g", 80.0),
                MetricSeriesRow("health-connect", day, "protein_g", 90.0)))
            assertEquals(listOf(day to 90.0), MetricLibrary.series(repo, "my-whoop", "protein_g", day, day, false))
            repo.replaceImportedMetricWindow("health-connect", "protein_g", day, day, emptyList())
            assertEquals(listOf(day to 80.0), MetricLibrary.series(repo, "my-whoop", "protein_g", day, day, false))
            assertTrue(MetricLibrary.series(repo, "my-whoop", "fat_g", day, day, false).isEmpty())
        } finally { db.close() }
    }

    @Test fun foodOnlyDaysAreVisibleAndDoNotDoubleCountBodyRowsOrOtherSources() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WhoopDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = WhoopRepository(db)
            repo.upsertMetricSeries(listOf(MetricSeriesRow("health-connect", "2026-11-01", "calories_in", 1800.0),
                MetricSeriesRow("health-connect", "2026-11-02", "protein_g", 90.0),
                MetricSeriesRow("nutrition-csv", "2026-10-30", "calories_in", 2000.0)))
            repo.upsertAppleDaily(listOf(AppleDaily("health-connect", "2026-11-01", weightKg = 70.0)))
            assertEquals(2, repo.healthConnectImportedDaysCount())
        } finally { db.close() }
    }

    @Test fun nutritionCsvWeighInsAreDiscoverableWithoutReplacingHealthMeasurements() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WhoopDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = WhoopRepository(db); val day = "2026-11-02"
            repo.upsertMetricSeries(listOf(MetricSeriesRow("nutrition-csv", day, "weight", 70.0)))
            assertEquals(listOf(day to 70.0), MetricLibrary.series(repo, "my-whoop", "weight", day, day, false))
            repo.upsertMetricSeries(listOf(MetricSeriesRow("health-connect", day, "weight", 71.0)))
            assertEquals(listOf(day to 71.0), MetricLibrary.series(repo, "my-whoop", "weight", day, day, false))
        } finally { db.close() }
    }

    @Test fun metricOnlyWritesNotifyScreensEvenWhenDailyScoresDoNotChange() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WhoopDatabase::class.java).allowMainThreadQueries().build()
        try {
            db.openHelper.writableDatabase
            val repo = WhoopRepository(db)
            val ready = CompletableDeferred<Unit>()
            val observed = async(Dispatchers.Default) {
                repo.metricChanges.onEach { if (it == 0L) ready.complete(Unit) }.take(2).toList()
            }
            withTimeout(5000) { ready.await() }
            repo.upsertMetricSeries(listOf(MetricSeriesRow("health-connect", "2026-11-02", "calories_in", 1800.0)))
            val changes = withTimeout(5000) { observed.await() }
            assertEquals(0L, changes.first())
            assertTrue(changes.last() > 0)
        } finally { db.close() }
    }
}
