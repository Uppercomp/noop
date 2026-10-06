package com.noop.ui

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsRun
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Brush
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.ui.graphics.vector.ImageVector
import com.noop.R
import com.noop.analytics.BatteryEstimator
import com.noop.analytics.ConnectionReadout
import com.noop.analytics.DisplayReadout
import com.noop.analytics.ImportReadout
import com.noop.analytics.SleepReadout
import com.noop.analytics.StepsReadout
import com.noop.analytics.TestReadout
import com.noop.analytics.WorkoutsReadout
import com.noop.data.GravitySample
import com.noop.data.HrSample
import com.noop.testcentre.TestDomain
import com.noop.testcentre.TestMode
import java.util.Locale
import kotlin.math.roundToInt

/** Inputs shared by the Test Centre row and its report: the exported tagged log, plus the small set of
 * live values that are not log-derived. Repository-backed samples are loaded only while their row is on. */
internal data class TestCentreLiveSnapshot(
    /** #1468 follow-up: the log as LINES. Every consumer here filters by domain tag, so the joined
     *  string this replaced was built only to be split again. */
    val logLines: List<String> = emptyList(),
    /**
     * This mode's lines, already filtered out of [logLines] by the caller.
     *
     * Null means "not pre-filtered", and [rows] then does the tagging itself exactly as before, so a
     * caller that has no pass of its own keeps working. The Test Centre screen supplies it because it
     * filters ALL domains in one pass over the archive, instead of every visible row running the tag
     * Regex over every line for itself.
     */
    val domainLogLines: List<String>? = null,
    val nowUnix: Long = System.currentTimeMillis() / 1_000,
    val connected: Boolean = false,
    val batteryPct: Double? = null,
    val batteryEstimate: BatteryEstimator.Estimate? = null,
    val hrSamples: List<HrSample> = emptyList(),
    val gravitySamples: List<GravitySample> = emptyList(),
)

/**
 * A readout ROW as a pair of [DisplayText]s rather than resolved Strings.
 *
 * The label and the "nothing yet" notes are translated phrases; the value beside them is computed text
 * (a formatted figure, a parsed log field) that iOS does not translate either. Keeping the two kinds
 * apart is what lets [TestCentreLiveReadouts.rows] stay pure — it runs in JVM tests with no Application
 * attached, so it cannot resolve a resource itself — while the row still renders translated on device.
 */
internal data class LiveReadoutRow(val id: String, val label: DisplayText, val value: DisplayText)

/** The Connection domain's bespoke extras: rows to append after the spec rows, plus the amber 1970/71
 *  RTC warning iOS renders underneath them (null when the clock looks sane, or when neither clock signal
 *  has been seen yet — the parser never fabricates a fault). */
internal data class ConnectionExtras(
    val rows: List<LiveReadoutRow>,
    val rtcWarning: DisplayText?,
)

/** A translated phrase: what the iOS twin ships in its String Catalog, so both say the same words. */
private fun phrase(@StringRes id: Int): DisplayText = DisplayText.Resource(id)

/** Computed text — a formatted figure or a parsed log field, which is never a translated phrase. */
private fun computed(value: String): DisplayText = DisplayText.Dynamic(value)

/** Observable inputs an active row subscribes to. Null/false means no collector or clock exists. */
internal data class LiveReadoutRefreshSources(
    val observeLogRevision: Boolean = false,
    val observeSleepSampleRevision: Boolean = false,
    val observeBatteryRevision: Boolean = false,
    val connectionClockEveryMs: Long? = null,
)

internal object TestCentreLiveRefreshPolicy {
    fun sources(mode: TestMode, active: Boolean): LiveReadoutRefreshSources {
        if (!active) return LiveReadoutRefreshSources()
        return LiveReadoutRefreshSources(
            observeLogRevision = mode.liveReadout.isNotEmpty(),
            observeSleepSampleRevision = mode.domain == TestDomain.SLEEP,
            observeBatteryRevision = mode.domain == TestDomain.BATTERY,
            connectionClockEveryMs = 1_000L.takeIf { mode.domain == TestDomain.CONNECTION },
        )
    }
}

/** Exhaustive presentation mapping for every declarative [TestMode.liveReadout] id. Unknown active ids
 * fail loudly instead of silently disappearing; inactive modes return before parsing or formatting. */
internal object TestCentreLiveReadouts {
    val mappedIds: Set<String> = setOf(
        "hrDensityNow", "gravityCoverageNow", "lastNightGateFired",
        "connectionUptime", "reconnectCount", "lastOffloadResult",
        "lastSessionSummary", "deviceMetricsNow", "lastImportSummary",
        "stepsToday", "calibrationState",
        "currentSoc", "estimateDaysLeft", "slopeSource",
        "lastChargeBreakdown", "lastHrvComputation",
    )

    fun rows(mode: TestMode, active: Boolean, snapshot: TestCentreLiveSnapshot): List<LiveReadoutRow> {
        if (!active) return emptyList()
        val tail by lazy { snapshot.domainLogLines ?: taggedTail(snapshot.logLines, mode.id) }
        return mode.liveReadout.map { id ->
            require(id in mappedIds) { "Unmapped Test Centre liveReadout id: $id" }
            when (id) {
                "hrDensityNow" -> LiveReadoutRow(
                    id, phrase(R.string.l10n_test_centre_live_readouts_hr_density_per_min_6a33dd58),
                    if (snapshot.hrSamples.isEmpty()) {
                        phrase(R.string.l10n_test_centre_live_readouts_no_live_hr_yet_6b257d1e)
                    } else {
                        computed(String.format(Locale.US, "%.1f", SleepReadout.hrDensityPerMinute(snapshot.hrSamples)))
                    },
                )
                "gravityCoverageNow" -> LiveReadoutRow(
                    id, phrase(R.string.l10n_test_centre_live_readouts_gravity_coverage_bd26652d),
                    if (snapshot.gravitySamples.isEmpty()) {
                        phrase(R.string.l10n_test_centre_live_readouts_no_live_gravity_yet_7cd9c290)
                    } else {
                        computed(
                            String.format(
                                Locale.US, "%.0f%%",
                                SleepReadout.gravityCoverageFraction(snapshot.gravitySamples, snapshot.hrSamples) * 100,
                            ),
                        )
                    },
                )
                "lastNightGateFired" -> LiveReadoutRow(
                    id, phrase(R.string.l10n_test_centre_live_readouts_last_gate_fired_d04f8d0d),
                    SleepReadout.lastGateFired(tail)?.let { computed(it) }
                        ?: phrase(R.string.l10n_test_centre_live_readouts_no_night_yet_63cfa828),
                )
                "connectionUptime" -> LiveReadoutRow(
                    id, phrase(R.string.l10n_test_centre_live_readouts_connection_uptime_de91fdcc),
                    if (snapshot.connected) computed(ConnectionReadout.uptimeLabel(tail, snapshot.nowUnix))
                    else phrase(R.string.l10n_test_centre_live_readouts_not_connected_394b08fe),
                )
                "reconnectCount" -> LiveReadoutRow(
                    id, phrase(R.string.l10n_test_centre_live_readouts_reconnects_this_run_f505ad8e),
                    computed(ConnectionReadout.reconnectCount(tail).toString()),
                )
                "lastOffloadResult" -> LiveReadoutRow(
                    id, phrase(R.string.l10n_test_centre_live_readouts_last_offload_result_737cf27a),
                    ConnectionReadout.lastOffloadResult(tail)?.let { computed(it) }
                        ?: phrase(R.string.l10n_test_centre_live_readouts_no_offload_yet_60e9e982),
                )
                "lastSessionSummary" -> LiveReadoutRow(
                    id, phrase(R.string.l10n_test_centre_live_readouts_last_session_0563ca3a),
                    WorkoutsReadout.lastSessionSummary(tail)?.let { computed(it) }
                        ?: phrase(R.string.l10n_test_centre_live_readouts_no_session_yet_10e221f4),
                )
                "deviceMetricsNow" -> LiveReadoutRow(
                    id, phrase(R.string.l10n_test_centre_live_readouts_device_metrics_fa245f0c),
                    DisplayReadout.deviceMetricsNow(tail)?.let { computed(it) } ?: computed("reading..."),
                )
                "lastImportSummary" -> LiveReadoutRow(
                    id, phrase(R.string.l10n_test_centre_live_readouts_last_import_0890005d),
                    ImportReadout.lastImportSummary(tail)?.let { computed(it) }
                        ?: phrase(R.string.l10n_test_centre_live_readouts_no_import_yet_88f6aeca),
                )
                "stepsToday" -> LiveReadoutRow(
                    id, phrase(R.string.l10n_test_centre_live_readouts_steps_today_4b6a3875),
                    StepsReadout.stepsToday(tail)?.let { computed(it.toString()) }
                        ?: phrase(R.string.l10n_test_centre_live_readouts_no_estimate_yet_dc555edb),
                )
                "calibrationState" -> LiveReadoutRow(
                    id, phrase(R.string.l10n_test_centre_live_readouts_calibration_f5ed075f),
                    StepsReadout.calibrationState(tail)?.let { computed(it) }
                        ?: phrase(R.string.l10n_test_centre_live_readouts_no_calibration_yet_56549481),
                )
                "currentSoc" -> LiveReadoutRow(
                    id, phrase(R.string.l10n_test_centre_live_readouts_current_charge_b6b0b3d7),
                    snapshot.batteryPct?.let { computed("${it.roundToInt()}%") } ?: computed("--"),
                )
                "estimateDaysLeft" -> LiveReadoutRow(
                    id, phrase(R.string.l10n_test_centre_live_readouts_estimated_runtime_left_14063bee),
                    snapshot.batteryEstimate?.let { computed(BatteryEstimator.label(it.remainingHours)) }
                        ?: computed("--"),
                )
                "slopeSource" -> LiveReadoutRow(
                    id, phrase(R.string.l10n_test_centre_live_readouts_slope_source_67e5b09b),
                    computed(snapshot.batteryEstimate?.source?.name?.lowercase(Locale.US) ?: "--"),
                )
                "lastChargeBreakdown" -> LiveReadoutRow(
                    id, phrase(R.string.l10n_test_centre_live_readouts_last_charge_breakdown_aaf97483),
                    TestReadout.lastChargeBreakdown(tail)?.let { computed(it) }
                        ?: phrase(R.string.l10n_test_centre_live_readouts_no_night_scored_yet_6303be93),
                )
                "lastHrvComputation" -> LiveReadoutRow(
                    id, phrase(R.string.l10n_test_centre_live_readouts_last_hrv_reading_a189df09),
                    TestReadout.lastHrvComputation(tail)?.let { computed(it) }
                        ?: phrase(R.string.l10n_test_centre_live_readouts_no_reading_yet_21a0b40c),
                )
                else -> error("mapped id has no renderer: $id")
            }
        }
    }

    /**
     * The rows iOS's Connection panel renders ON TOP OF the registry's `liveReadout` spec.
     *
     * The spec names three ids for this domain (`connectionUptime`, `reconnectCount`, `lastOffloadResult`)
     * and that list is IDENTICAL in `TestModeRegistry.kt` and its Swift twin — a tested cross-platform
     * contract. The Swift panel is not driven by the spec at all; it hand-writes seven rows
     * (`TestCentreView.swift:1084-1105`). So these live here as a bespoke addition for this one domain
     * rather than as new spec ids, which would be a registry divergence.
     *
     * "Last frame" is deliberately absent: it needs `LiveState.lastFrameAtUnix`, which Android's BLE layer
     * does not carry, and a frame age computed from anything else would be an invented number.
     *
     * [strapNewestUnix] is resolved by the CALLER through the one shared `resolveStrapClockTs`, not
     * re-derived here, so this panel and the strap log cannot disagree about which strap a clock verdict
     * describes. [batteryPct] must likewise be the CURRENT link's reading: `rtcWarning` withdraws its
     * "charge the strap" advice on an already-charged strap, and a stale 100% would suppress the advice in
     * the one case it is right — a strap that ran flat and reset its RTC.
     *
     * Pure: no Context, so it runs in JVM tests and cannot resolve a resource itself.
     */
    internal fun connectionExtras(
        tail: List<String>,
        logLines: List<String>,
        strapNewestUnix: Long?,
        batteryPct: Double?,
        cumulativeDrainedRows: Long,
    ): ConnectionExtras {
        val deviceClock = ConnectionReadout.clockCorrelatedDevice(logLines)
        val noOffloadYet = phrase(R.string.l10n_test_centre_live_readouts_no_offload_yet_60e9e982)
        return ConnectionExtras(
            rows = listOf(
                LiveReadoutRow(
                    "rowsDrainedSession",
                    phrase(R.string.test_centre_rows_drained_session),
                    ConnectionReadout.sessionRows(tail)?.let { computed(it.toString()) } ?: noOffloadYet,
                ),
                LiveReadoutRow(
                    "rowsDrainedAllTime",
                    phrase(R.string.test_centre_rows_drained_all_time),
                    computed(cumulativeDrainedRows.toString()),
                ),
                LiveReadoutRow(
                    "clockLatched",
                    phrase(R.string.test_centre_clock_latched),
                    computed(ConnectionReadout.clockLatchedLabel(deviceClock, strapNewestUnix)),
                ),
            ),
            // The plain-words remedy is a SENTENCE with the fix in it, not a label/value pair, so it is
            // carried beside the rows and rendered amber underneath — exactly as iOS does.
            rtcWarning = ConnectionReadout.rtcWarning(deviceClock, strapNewestUnix, batteryPct)
                ?.let { DisplayText.Dynamic(it) },
        )
    }

    private fun taggedTail(logLines: List<String>, domainId: String): List<String> {
        return logLines.filter { line ->
            firstDomainTag.find(line)?.groupValues?.get(1) == domainId
        }
    }

    /**
     * Every domain's lines from ONE pass over [logLines], for a caller rendering several modes at once.
     *
     * Same matcher and therefore the same grouping [taggedTail] produces one domain at a time; this
     * runs the Regex once per line rather than once per line per visible mode. Domains with no lines are
     * absent, so a caller reads `[id].orEmpty()`.
     */
    fun tagLinesByDomain(logLines: List<String>): Map<String, List<String>> {
        if (logLines.isEmpty()) return emptyMap()
        val out = HashMap<String, MutableList<String>>()
        for (line in logLines) {
            val id = firstDomainTag.find(line)?.groupValues?.get(1) ?: continue
            out.getOrPut(id) { ArrayList() }.add(line)
        }
        return out
    }

    // Match only a real leading TestDomain marker: raw test lines start with it; exported production lines
    // put it immediately after their time/date stamp. Arbitrary payload text before `[recovery]` is not a
    // stamp and cannot turn an otherwise untagged line into Recovery data.
    private val firstDomainTag = Regex(
        "^(?:(?:\\d{4}-\\d{2}-\\d{2}[ T])?\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?\\s+)?" +
            "\\[(${TestDomain.entries.joinToString("|") { Regex.escape(it.id) }})]\\s",
    )
}

/**
 * The registry's `TestMode.icon` id → the Compose vector Android draws for it.
 *
 * The field is shared with the Swift twin ("SF Symbol on Apple; mapped to a drawable id on Android"), and
 * `TestModeRegistryParityTest` keeps the two id sets in step — but Android has no drawables for these and
 * nothing read the field, so nine ids sat unused and every row rendered without its glyph. This is the
 * Android half of the same contract: the registry names WHICH icon a mode owns, this resolves it.
 *
 * Null for an id this build doesn't know, so an added mode degrades to no glyph rather than a wrong one.
 */
internal fun testModeIcon(icon: String): ImageVector? = when (icon) {
    "ic_bed" -> Icons.Filled.Bedtime
    "ic_antenna" -> Icons.Filled.SettingsInputAntenna
    "ic_run" -> Icons.AutoMirrored.Filled.DirectionsRun
    "ic_paintbrush" -> Icons.Filled.Brush
    "ic_import" -> Icons.Filled.FileDownload
    "ic_steps" -> Icons.AutoMirrored.Filled.DirectionsWalk
    "ic_battery" -> Icons.Filled.BatteryFull
    "ic_recovery" -> Icons.Filled.Favorite
    "ic_hrv" -> Icons.Filled.MonitorHeart
    else -> null
}
