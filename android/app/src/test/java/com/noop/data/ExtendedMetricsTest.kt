package com.noop.data

import android.app.Application
import androidx.room.Room
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class ExtendedMetricsTest {
    @Test fun intakePrecedenceTotalBurnAndAuthoritativeDeletes() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WhoopDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = WhoopRepository(db)
            val day = "2026-10-31"
            repo.upsertDailyMetrics(listOf(DailyMetric("my-whoop-noop", day, activeKcalEst = 2500.0)))
            repo.upsertMetricSeries(listOf(MetricSeriesRow("nutrition-csv", day, "calories_in", 1900.0),
                MetricSeriesRow("health-connect", day, "calories_in", 2100.0), MetricSeriesRow("health-connect", day, "total_kcal", 2400.0)))
            assertEquals(listOf(day to -400.0), ExtendedMetrics.series(repo, "my-whoop", "energy_balance", day, day))
            // A deleted Health Connect log falls back to the separate CSV source, without erasing it.
            repo.replaceImportedMetricWindow("health-connect", "calories_in", day, day, emptyList())
            assertEquals(listOf(day to -600.0), ExtendedMetrics.series(repo, "my-whoop", "energy_balance", day, day))
            repo.deleteMetricSeries("nutrition-csv", "calories_in")
            assertTrue(ExtendedMetrics.series(repo, "my-whoop", "energy_balance", day, day).isEmpty())
            // Partial log values do not become a made-up zero-calorie day.
            assertEquals(listOf(day to 2500.0), ExtendedMetrics.series(repo, "my-whoop", "total_kcal", day, day))
        } finally { db.close() }
    }

    @Test fun historicalHealthConnectBurnCannotMaskStrapAndIsNotDeleted() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), WhoopDatabase::class.java).allowMainThreadQueries().build()
        try {
            val repo = WhoopRepository(db)
            val day = "2026-11-01"
            repo.upsertDailyMetrics(listOf(DailyMetric("my-whoop-noop", day, activeKcalEst = 2500.0)))
            repo.upsertAppleDaily(listOf(AppleDaily("health-connect", day, activeKcal = 600.0, basalKcal = 1600.0, weightKg = 70.0)))
            repo.upsertMetricSeries(listOf(MetricSeriesRow("health-connect", day, "total_kcal", 2200.0),
                MetricSeriesRow("health-connect", day, "active_kcal", 600.0),
                MetricSeriesRow("health-connect", day, "calories_in", 1900.0)))
            val visible = repo.appleDaily("health-connect", day, day).single()
            assertNull(visible.activeKcal)
            assertNull(visible.basalKcal)
            assertEquals(70.0, visible.weightKg)
            assertTrue(repo.resolvedSeries("active_kcal", "health-connect", day, day).points.isEmpty())
            assertTrue(repo.metricSeries("health-connect", "total_kcal", day, day).isEmpty())
            assertEquals(listOf(day to -600.0), ExtendedMetrics.series(repo, "my-whoop", "energy_balance", day, day))
            assertEquals(600.0, db.whoopDao().appleDaily("health-connect", day, day).single().activeKcal)
            assertEquals(2200.0, db.whoopDao().metricSeries("health-connect", "total_kcal", day, day).single().value, 0.0)
            assertEquals(2500.0, repo.days("my-whoop-noop").single().activeKcalEst)
        } finally { db.close() }
    }
}
