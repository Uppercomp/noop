package com.noop.protocol

/**
 * How well this platform handles a historical record's layout version, for the strap-log line that tells a
 * user why their nights are not staging. Kotlin twin of the Swift `HistoricalLayoutSupport`.
 *
 * The two negative cases are kept apart because they are different facts with different remedies, and the
 * single line that used to cover both stated one of them wrongly. See [historicalLayoutSupport].
 */
enum class HistoricalLayoutSupport {
    /** The layout decodes and the record carries a named signal NOOP scores from. */
    SUPPORTED,

    /**
     * The layout decodes, but this record carries no per-second heart rate and no motion, so a night made
     * only of these cannot be staged. What is missing is the meaning of the channels, not the layout.
     */
    DECODES_WITHOUT_NAMED_SIGNAL,

    /** This platform has no field map for the layout at all; the record does not decode. */
    UNMAPPED,
}

/**
 * Classify a historical record's layout version. Twin of the Swift `historicalLayoutSupport`.
 *
 * #1992. This used to be one question, "did the record decode any of `heart_rate`, `gravity_x` or
 * `ppg_waveform`?", and one message, which said NOOP could not decode the layout. That is a list which has
 * to be extended by hand every time NOOP learns a layout, and twice it silently was not: #156 was v25 and
 * v26 being reported as undecodable after they had been decoding for releases.
 *
 * Simply suppressing the line for a mapped-but-unscoreable layout would be the wrong correction, because
 * the SECOND half of what it said is true: those records really do carry no heart rate or motion, and a
 * night made only of them really cannot be staged. So the question splits.
 *
 * [MAPPED_WHOOP5_HISTORICAL_VERSIONS] is the same set on both platforms, and this function answers the
 * same way on both. An earlier note here claimed Android was deliberately narrower with no v20/v21
 * historical branch, so that a v20 record was genuinely UNMAPPED on this side while Swift answered
 * DECODES_WITHOUT_NAMED_SIGNAL. That stopped being true when the v20/v21 historical dispatch was ported
 * to Kotlin, and the note then documented a divergence that no longer existed. A mapped layout that
 * carries no scoreable signal is DECODES_WITHOUT_NAMED_SIGNAL on both platforms.
 */
fun historicalLayoutSupport(
    version: Int,
    family: DeviceFamily,
    hasHeartRate: Boolean,
    hasGravity: Boolean,
    hasPpgWaveform: Boolean,
): HistoricalLayoutSupport {
    val carriesNamedSignal = hasHeartRate || hasGravity || hasPpgWaveform
    return when (family) {
        DeviceFamily.WHOOP5 ->
            if (version !in MAPPED_WHOOP5_HISTORICAL_VERSIONS) HistoricalLayoutSupport.UNMAPPED
            else if (carriesNamedSignal) HistoricalLayoutSupport.SUPPORTED
            else HistoricalLayoutSupport.DECODES_WITHOUT_NAMED_SIGNAL
        DeviceFamily.WHOOP4 ->
            if (carriesNamedSignal) HistoricalLayoutSupport.SUPPORTED else HistoricalLayoutSupport.UNMAPPED
    }
}
