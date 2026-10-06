package com.noop.analytics

import com.noop.data.HrSample
import com.noop.data.SkinTempSample
import com.noop.protocol.DeviceFamily

/**
 * #1853: backfill the nightly ABSOLUTE skin temperature (`skinTempC`) for nights the 21-night
 * `analyzeRecent` rescore window never reaches.
 *
 * `skinTempC` is written only by the scoring pass, which covers a rolling 21-night window. Nights already
 * outside that window when the column shipped were never revisited, and nothing walks them since — so a
 * night is missing its absolute if it fell outside every window that ran afterwards, regardless of whether
 * its raw `skinTempSample` rows are still on disk (they are: `skinTempSample` has no age-based retention).
 *
 * This file holds the PURE, database-free half of the backfill: the session-attribution rule and the
 * per-night plan. The walker that reads from / writes to the store lives beside the screen that runs it.
 *
 * Twin of Swift `SkinTempBackfill` (`Packages/StrandAnalytics/Sources/StrandAnalytics/SkinTempBackfill.swift`),
 * and the three rules below are reproduced verbatim from it or the two would be wrong in the same ways:
 *
 * 1. **Attribute sessions by END.** The night window is 54 h wide (30 h before local midnight through the
 *    next), so it contains the PREVIOUS night as well. The engine's rule is
 *    `matched = allSessions.filter { tsInDay(it.end) }`; handing the funnel the whole window averages two
 *    nights into one temperature and stores it as the wrong day's.
 *
 * 2. **Page the candidates.** A plain "oldest N" returns the same nights every pass, and the oldest are
 *    the ones most likely to have lost their raw samples — the sweep latches on page one and never reaches
 *    the newer nights that can fill.
 *
 * 3. **The WHOOP 4.0 anchor comes from the CURRENT window.** It is learned window-wide, and the engine's
 *    note says that is safe because the offset "cancels in the deviation" — true for a delta, not for an
 *    absolute. A re-learned anchor puts backfilled nights on a different offset from the stored ones and the
 *    chart plots two scales as one line. **No anchor ⇒ decline, never the global default.**
 *
 * No oracle was run for this port: the Swift twin needs a Swift toolchain, which this environment does not
 * have. The port follows the Swift source line for line and mirrors `StrandTests/SkinTempBackfillTests.swift`
 * case for case, which is the strongest check available here — and the per-night VALUE is not re-derived at
 * all: [computeNight] delegates to the same [AnalyticsEngine.skinTempFunnel] the scoring pass uses, so the
 * arithmetic is shared rather than duplicated.
 */
object SkinTempBackfill {

    /**
     * One night the backfill is considering: the day key, the night's read window (from/to in unix
     * seconds), and the device owner whose raw streams should be read. The walker builds these from
     * `DailyMetric` rows that have a `skinTempDevC` but no `skinTempC`; [computeNight] decides which
     * sessions and samples belong to each.
     */
    data class NightCandidate(
        /** `yyyy-MM-dd` (local-day key, matching `DailyMetric.day`). */
        val day: String,
        /** Unix seconds: dayStart - the stream-read lookback. */
        val from: Long,
        /** Unix seconds: dayStart + 24 h (or now, for today). */
        val to: Long,
        /** The device id whose raw streams wrote this night. */
        val owner: String,
    )

    /** The result of attempting one night's backfill. */
    data class NightResult(
        val day: String,
        /**
         * The re-derived absolute (°C), or null when the night declined (no anchor, too few worn samples,
         * no sessions). The walker writes `skinTempC` ONLY when this is non-null.
         */
        val skinTempC: Double?,
        /** Why the night declined, for the diagnostic report. null when it filled. */
        val declineReason: String? = null,
    )

    /**
     * Rule 1: attribute sessions by END timestamp. A session belongs to [dayStart]'s day iff its END falls
     * in `[dayStart, dayStart + 86400)`.
     *
     * `dayStart` is the LOCAL-midnight unix seconds for the day key (the same `dayStart` the engine's
     * per-day loop computes). Pure and deterministic.
     */
    fun sessionsForDay(sessions: List<DetectedSleep>, dayStart: Long): List<DetectedSleep> {
        val dayEnd = dayStart + 86_400L
        return sessions.filter { it.end >= dayStart && it.end < dayEnd }
    }

    /**
     * Rule 3: the WHOOP 4.0 anchor MUST come from the current scoring window. If no per-device anchor could
     * be learned, the night DECLINES — the global anchor is NOT a fallback, because a re-learned anchor
     * puts backfilled nights on a different offset from the stored ones and the chart plots two scales as
     * one line.
     *
     * For a 5/MG the anchor is always null and the night proceeds (the centidegree path needs none).
     */
    fun resolveAnchor(family: DeviceFamily, windowAnchorRaw: Double?): Double? = when (family) {
        DeviceFamily.WHOOP4 -> windowAnchorRaw
        DeviceFamily.WHOOP5 -> null
    }

    /**
     * Compute one night's backfill absolute, applying all three rules. Pure and deterministic; the walker
     * supplies the raw inputs and writes the result.
     *
     * @param sessions ALL sleep sessions in the 54 h window (the walker reads them once); rule 1 filters to
     *   this day's by end timestamp.
     * @param windowAnchorRaw the per-device WHOOP 4.0 anchor learned from the CURRENT scoring window
     *   (rule 3). null for a 5/MG, or when the current window could not learn one.
     * @param dayStart the LOCAL-midnight unix seconds for `candidate.day`.
     * @param wornToleranceSec the worn-gate timestamp tolerance (0 for WHOOP, > 0 for a ring).
     */
    fun computeNight(
        candidate: NightCandidate,
        sessions: List<DetectedSleep>,
        hr: List<HrSample>,
        skinTemp: List<SkinTempSample>,
        family: DeviceFamily,
        windowAnchorRaw: Double?,
        dayStart: Long,
        wornToleranceSec: Long = 0,
    ): NightResult {
        // Rule 1: attribute sessions by END. Don't hand the funnel the whole 54 h window.
        val matched = sessionsForDay(sessions, dayStart)
        if (matched.isEmpty()) {
            return NightResult(candidate.day, skinTempC = null, declineReason = "no sleep session ends on this day")
        }
        // Rule 3: the WHOOP 4.0 anchor comes from the current window. No anchor ⇒ decline.
        val anchor = resolveAnchor(family, windowAnchorRaw)
        if (family == DeviceFamily.WHOOP4 && anchor == null) {
            return NightResult(
                candidate.day,
                skinTempC = null,
                declineReason = "no per-device 4.0 anchor from the current window " +
                    "(rule 3: decline, not global fallback)",
            )
        }
        // Run the SAME funnel the scoring pass uses — identical inputs, identical result, no second
        // derivation that could disagree. Note the funnel's own `anchorRaw = null` means "use the global
        // anchor", which is exactly why rule 3 declines ABOVE this call rather than relying on it.
        val diag = AnalyticsEngine.skinTempFunnel(
            sessions = matched,
            hr = hr,
            skinTemp = skinTemp,
            family = family,
            anchorRaw = anchor,
            wornToleranceSec = wornToleranceSec,
        )
        diag.mean?.let { mean -> return NightResult(candidate.day, skinTempC = mean) }
        // Decline with the funnel's own explanation so the diagnostic report is self-attesting.
        val reason = if (diag.kept < diag.minSamples) {
            "only ${diag.kept}/${diag.minSamples} worn samples"
        } else {
            "funnel returned no mean"
        }
        return NightResult(candidate.day, skinTempC = null, declineReason = reason)
    }

    /**
     * Rule 2: page the candidates so the sweep does not latch on the oldest N (which are the most likely to
     * have lost their raw samples). Returns the candidates for one page, oldest-first within the page. The
     * walker calls this repeatedly with increasing [page] (0-based) until a page comes back empty.
     *
     * [pageSize] caps the per-pass cost (one sleep-window sample read per night).
     */
    fun page(candidates: List<NightCandidate>, page: Int, pageSize: Int = 50): List<NightCandidate> {
        val start = page * pageSize
        if (start >= candidates.size) return emptyList()
        return candidates.subList(start, minOf(start + pageSize, candidates.size))
    }
}
