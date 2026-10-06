package com.noop.ui

import com.noop.R

// MARK: - The Compare screen's composed captions (pure, so a JVM test can pin them)
//
// Kept out of CompareScreen.kt so a unit test can reach them without initialising that file's statics, and
// because both are whole-phrase builders: the iOS twin writes them as `String(localized:)` variants per
// count, so a translator never sees a sentence stitched together from a plural.

/**
 * The caption under the range pills: "N readings across M · <range phrase>", with a trailing
 * " · sparse widened" when a series had to widen (`CompareView.rangeCaption`).
 */
internal fun compareRangeCaption(
    total: Int,
    seriesCount: Int,
    widened: Boolean,
    range: CompareRange,
): DisplayText {
    val phrase = DisplayText.Resource(range.phraseRes)
    val id = when {
        widened && total == 1 -> R.string.l10n_compare_screen_s_1_reading_across_lld_sparse_widened_45c43244
        widened -> R.string.l10n_compare_screen_lld_readings_across_lld_sparse_widened_27763792
        total == 1 -> R.string.l10n_compare_screen_s_1_reading_across_lld_61f778db
        else -> R.string.l10n_compare_screen_lld_readings_across_lld_dfcfe5bb
    }
    return DisplayText.Resource(id, listOf(total, seriesCount, phrase))
}

/**
 * The normalized overlay's subtitle: how to read the real numbers behind a 0-1 scaled axis, naming the
 * window and the gesture that reveals a value. iOS carries the same two variants, its `inspectHint` being
 * "tap or drag for real values" on a touch device.
 */
internal fun compareOverlaySubtitle(widened: Boolean, range: CompareRange): DisplayText {
    val id = if (widened) {
        R.string.l10n_compare_screen_min_max_normalized_sparse_series_widened_fedc6850
    } else {
        R.string.l10n_compare_screen_each_line_min_max_normalized_within_7cf9ef5b
    }
    return DisplayText.Resource(
        id,
        listOf(
            DisplayText.Resource(range.phraseRes),
            DisplayText.Resource(R.string.l10n_compare_screen_tap_or_drag_for_real_values_3e56560b),
        ),
    )
}
