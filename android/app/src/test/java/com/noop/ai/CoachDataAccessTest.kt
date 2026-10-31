package com.noop.ai

import android.app.Application
import android.content.Context
import com.noop.data.WhoopDatabase
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, application = Application::class)
class CoachDataAccessTest {
    @Test fun queriesAllHealthTablesWithoutWriteOrSettingsAccess() {
        val ctx = RuntimeEnvironment.getApplication()
        WhoopDatabase.get(ctx).openHelper.writableDatabase.execSQL(
            "INSERT OR REPLACE INTO metricSeries(deviceId,day,`key`,value) VALUES('test','2026-10-31','calories_in',2100)")
        ctx.getSharedPreferences("noop_prefs", Context.MODE_PRIVATE).edit().putString("api_key", "never-share")
            .putString("noop.caffeineIntakes", "health-log").commit()
        CoachDataAccess(ctx).use { access ->
            assertTrue(access.catalog().contains("ppgWaveformSample"))
            val result = JSONObject(access.query("SELECT value FROM metricSeries WHERE deviceId='test'"))
            assertEquals(2100, result.getJSONArray("rows").getJSONObject(0).getInt("value"))
            assertTrue(access.preferences("healthLogs", "noop.caffeineIntakes").contains("health-log"))
            assertFalse(access.preferences().contains("never-share"))
            for (bad in listOf("DELETE FROM dailyMetric", "SELECT 1; DROP TABLE metricSeries", "PRAGMA user_version", "WITH a AS (SELECT 1) DELETE FROM dailyMetric", "SELECT readfile('x')")) {
                assertFalse(bad, CoachDataAccess.isReadQuery(bad))
                assertTrue(runCatching { access.query(bad) }.isFailure)
            }
            assertTrue(CoachDataAccess.isReadQuery("WITH a AS (SELECT 1 AS n) SELECT n FROM a"))
            assertEquals(1, JSONObject(access.query("WITH a AS (SELECT 1 AS n) SELECT n FROM a")).getJSONArray("rows").length())
        }
    }
}
