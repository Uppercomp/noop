package com.noop.ui

import com.noop.analytics.AnalyticsEngine
import com.noop.analytics.IntelligenceEngine
import com.noop.analytics.SkinTempBackfill
import com.noop.analytics.StreamReadCap
import com.noop.protocol.Whoop4SkinTemp
import com.noop.data.WhoopRepository
import com.noop.protocol.DeviceFamily
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * #1853: the I/O half of the skin-temp absolute backfill — pages through the nights outside the 21-night
 * rescore window, reads each night's raw streams, and FILLS the missing absolute.
 *
 * Twin of Swift `SkinTempBackfillWalker` (`Strand/Data/SkinTempBackfillWalker.swift`). The pure plan lives
 * in [SkinTempBackfill]; this file only reads, calls it, and writes.
 *
 * Non-negotiable: the write is [WhoopRepository.fillSkinTempC], a fill-only UPDATE that can only fill a
 * NULL. It never upserts a rebuilt row, so `skinTempDevC` and every other scored field are untouched.
 *
 * The day's OWNER is resolved through [IntelligenceEngine.resolveDayOwnerForBackfill], the same resolver the
 * scoring pass uses rather than a second precedence rule here: a night's raw streams must come from the
 * device that wrote them, and the two callers must not be able to name different owners for one day.
 *
 * No oracle was run for this port (no Swift toolchain in this environment): the plan is mirrored case for
 * case and the per-night VALUE comes from the shared funnel, so what remains unverified by an oracle is the
 * store I/O, which the device check exercises.
 */
object SkinTempBackfillRunner {

    /** One night that filled — the day and the re-derived absolute. */
    data class FilledNight(val day: String, val skinTempC: Double)

    /** One night that declined — the day and the reason, for the diagnostic report. */
    data class DeclinedNight(val day: String, val reason: String)

    /** The result of one backfill pass (one or more pages). */
    data class BackfillResult(
        val filled: List<FilledNight>,
        val declined: List<DeclinedNight>,
        /** Nights skipped because no raw `skinTempSample` rows exist for the window — the data is gone,
         *  not the backfill's fault. */
        val noRawData: List<String>,
        /** The window anchor that was used (null for a 5/MG, or when the current window could not learn
         *  one). */
        val windowAnchorRaw: Double?,
        /** True when the pass reached the end rather than hitting the page budget. */
        val reachedEnd: Boolean,
    ) {
        val filledCount: Int get() = filled.size
        val declinedCount: Int get() = declined.size
        val noRawDataCount: Int get() = noRawData.size
    }

    /**
     * Local-midnight unix seconds for a `yyyy-MM-dd` day key. The key is LOCAL, so midnight is the UTC
     * midnight of `(dayStart + tzOffset)`: `dayString(ts, offset) == day` ⇔ `(ts + offset) ∈ [dayStart,
     * +86400)`, so `dayStart = utcMidnight(day) - tzOffset`.
     *
     * Pure, so the day maths is testable without a clock or a store. An unparseable key returns 0 rather
     * than throwing — the same tolerance the Swift twin has, and the candidate query only ever supplies
     * keys the store itself wrote.
     */
    fun localMidnightUnix(day: String, tzOffsetSeconds: Long): Long {
        val parts = day.split('-')
        if (parts.size != 3) return 0L
        val y = parts[0].toIntOrNull() ?: return 0L
        val m = parts[1].toIntOrNull() ?: return 0L
        val d = parts[2].toIntOrNull() ?: return 0L
        return runCatching {
            LocalDate.of(y, m, d).atStartOfDay(ZoneOffset.UTC).toEpochSecond() - tzOffsetSeconds
        }.getOrDefault(0L)
    }

    /** `yyyy-MM-dd` for a unix timestamp at [tzOffsetSeconds] east of UTC. */
    fun dayKey(nowSeconds: Long, tzOffsetSeconds: Long): String =
        LocalDate.ofEpochDay(Math.floorDiv(nowSeconds + tzOffsetSeconds, 86_400L)).toString()

    /**
     * Every night the backfill can fill: computed rows with a deviation (the night was scored) but no
     * absolute (the column was null), oldest-first so the sweep reaches the newer nights.
     *
     * Pure apart from the single read, so the candidate SHAPE is testable.
     */
    fun candidatesFrom(
        rows: List<com.noop.data.DailyMetric>,
        tzOffsetSeconds: Long,
        nowSeconds: Long,
        /** The imported (raw-stream) device id the night's samples are expected under. The re-resolved
         *  per-night owner supersedes it; this is the candidate's own record of where its data should be. */
        owner: String,
    ): List<SkinTempBackfill.NightCandidate> = rows
        .filter { it.skinTempDevC != null && it.skinTempC == null }
        .sortedBy { it.day }
        .map { row ->
            val dayStart = localMidnightUnix(row.day, tzOffsetSeconds)
            SkinTempBackfill.NightCandidate(
                day = row.day,
                from = dayStart - StreamReadCap.LOOKBACK_SECONDS,
                // Don't read past now for today's partial night.
                to = minOf(dayStart + 86_400L, nowSeconds),
                owner = owner,
            )
        }

    /**
     * The window anchor for the current scoring window, learned window-wide from the ACTIVE strap's raw
     * skin samples (rule 3). Only a WHOOP 4.0 needs one; a 5/MG passes null and proceeds on the centidegree
     * path. Null also means "could not learn one", which [SkinTempBackfill.computeNight] turns into a
     * decline for a 4.0 rather than a global-anchor fallback.
     */
    private suspend fun windowAnchor(
        repo: WhoopRepository,
        ownerSource: IntelligenceEngine.DayOwnerSource?,
        activeId: String,
        nowLocalMidnight: Long,
        maxDays: Int,
    ): Double? {
        val family = ownerSource?.skinTempFamily(activeId) ?: DeviceFamily.WHOOP5
        if (family != DeviceFamily.WHOOP4) return null
        val scanFrom = nowLocalMidnight - (maxDays - 1).toLong() * 86_400L - StreamReadCap.LOOKBACK_SECONDS
        val scanTo = nowLocalMidnight + 18 * 3_600L
        val windowSkin = runCatching {
            repo.skinTempSamples(activeId, scanFrom, scanTo, StreamReadCap.SKIN)
        }.getOrDefault(emptyList())
        return Whoop4SkinTemp.deviceAnchorRaw(windowSkin.map { it.raw })
    }

    /**
     * Run the full backfill: resolve the window anchor, then page through the candidates until a page comes
     * back short (the end) or [maxPages] is reached.
     *
     * [maxDays] mirrors the scoring pass's window (21). [maxPages] caps the pass (10 pages × 50 nights).
     */
    suspend fun runBackfill(
        repo: WhoopRepository,
        ownerSource: IntelligenceEngine.DayOwnerSource?,
        importedDeviceId: String,
        tzOffsetSeconds: Long,
        nowSeconds: Long,
        maxDays: Int = 21,
        maxPages: Int = 10,
        pageSize: Int = 50,
    ): BackfillResult {
        val computedId = repo.computedDeviceId(importedDeviceId)
        val nowLocalMidnight = localMidnightUnix(dayKey(nowSeconds, tzOffsetSeconds), tzOffsetSeconds)
        val rows = runCatching {
            repo.dailyMetrics(computedId, "0000-01-01", "9999-12-31")
        }.getOrDefault(emptyList())
        val candidates = candidatesFrom(rows, tzOffsetSeconds, nowSeconds, importedDeviceId)
        val anchor = windowAnchor(repo, ownerSource, importedDeviceId, nowLocalMidnight, maxDays)

        val filled = ArrayList<FilledNight>()
        val declined = ArrayList<DeclinedNight>()
        val noRawData = ArrayList<String>()
        var reachedEnd = false
        for (page in 0 until maxPages) {
            val pageCandidates = SkinTempBackfill.page(candidates, page, pageSize)
            if (pageCandidates.isEmpty()) {
                reachedEnd = true
                break
            }
            for (candidate in pageCandidates) {
                // The owner is resolved per night, exactly as the scoring pass does, so a night whose raw
                // streams were written by a different device than the active strap is read from the right
                // one (or declines honestly).
                val owner = IntelligenceEngine.resolveDayOwnerForBackfill(
                    repo = repo,
                    ownerSource = ownerSource,
                    day = candidate.day,
                    from = candidate.from,
                    to = candidate.to,
                    importedDeviceId = importedDeviceId,
                )
                val family = ownerSource?.skinTempFamily(owner) ?: DeviceFamily.WHOOP5
                val tolerance = ownerSource?.skinTempWornToleranceSec(owner) ?: 0L

                val skin = runCatching {
                    repo.skinTempSamples(owner, candidate.from, candidate.to, StreamReadCap.SKIN)
                }.getOrDefault(emptyList())
                if (skin.isEmpty()) {
                    noRawData += candidate.day
                    continue
                }
                val hr = runCatching {
                    repo.hrSamplesForDevice(owner, candidate.from, candidate.to, StreamReadCap.HR)
                }.getOrDefault(emptyList())
                val sessions = runCatching {
                    repo.sleepSessionsForDevice(owner, candidate.from, candidate.to, limit = 4_000)
                }.getOrDefault(emptyList()).mapNotNull { AnalyticsEngine.sleepSessionFromProvided(it) }

                val dayStart = localMidnightUnix(candidate.day, tzOffsetSeconds)
                val result = SkinTempBackfill.computeNight(
                    candidate = candidate,
                    sessions = sessions,
                    hr = hr,
                    skinTemp = skin,
                    family = family,
                    windowAnchorRaw = anchor,
                    dayStart = dayStart,
                    wornToleranceSec = tolerance,
                )
                val abs = result.skinTempC
                if (abs != null) {
                    // Fill-only: can only fill a NULL, never overwrite a measured value.
                    runCatching { repo.fillSkinTempC(computedId, candidate.day, abs) }
                    filled += FilledNight(candidate.day, abs)
                } else {
                    declined += DeclinedNight(candidate.day, result.declineReason ?: "unknown")
                }
            }
            if (pageCandidates.size < pageSize) {
                reachedEnd = true
                break
            }
        }
        return BackfillResult(
            filled = filled,
            declined = declined,
            noRawData = noRawData,
            windowAnchorRaw = anchor,
            reachedEnd = reachedEnd,
        )
    }

    /**
     * The one diagnostic line the Test Centre shows and logs, mirroring the Swift call site's wording so a
     * report from either platform reads the same.
     */
    fun reportLine(result: BackfillResult): String {
        val anchorNote = if (result.windowAnchorRaw != null) {
            "anchor from current window"
        } else {
            "no 4.0 anchor (5/MG or too few worn samples)"
        }
        val endNote = if (result.reachedEnd) "reached end" else "hit page budget"
        return "skin-temp backfill (#1853): filled ${result.filledCount}, " +
            "declined ${result.declinedCount}, no raw data ${result.noRawDataCount} " +
            "($anchorNote, $endNote)"
    }
}
