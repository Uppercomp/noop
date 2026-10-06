package com.noop.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import com.noop.R
import com.noop.analytics.SleepDebt
import com.noop.analytics.SleepDebtLedger
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

// MARK: - Formatting helpers (mirror SleepView.swift)

internal fun pct(minutes: Double, total: Double): Int =
    if (total > 0.0) (minutes / total * 100.0).roundToInt() else 0

internal fun pctValue(v: Double?): String = v?.let { "${it.roundToInt()}%" } ?: "—"

/** "+12% vs typical" / "−0.4 rpm vs typical" — the latest-vs-mean caption every tile carries. */
internal fun vsTypical(latest: Double?, typical: Double?, suffix: String, decimals: Int = 0): String {
    if (latest == null || typical == null || typical == 0.0) return "vs typical - "
    val diff = latest - typical
    val sign = if (diff >= 0) "+" else "−"
    val mag = abs(diff)
    val num = if (decimals == 0) "${mag.roundToInt()}" else String.format(java.util.Locale.US, "%.${decimals}f", mag)
    return uiString(R.string.l10n_sleep_formatting_vs_typical_df64ed57, sign, num, suffix)
}

/** #1946: a carried prior-day value is stamped "Carried · <date>" instead of "vs typical", so it is
 *  never passed off as tonight's read. Falls through to [vsTypical] when the value is today's own
 *  (or there is no value). Mirror EXACTLY in Swift. */
internal fun tileCaption(
    latestDay: String?, latest: Double?, typical: Double?,
    suffix: String, decimals: Int = 0,
): String {
    Metric.carriedMetricCaption(latestDay, latest)?.let { caption ->
        // Resolve the DisplayText.Resource here so tileCaption stays String-returning for the
        // SparkTile call sites. uiString reads the process Application resources, so this is
        // locale-aware without being a @Composable.
        return when (caption) {
            is DisplayText.Resource -> uiString(caption.id, *caption.args.toTypedArray())
            is DisplayText.Dynamic -> caption.value
            is DisplayText.Prefixed -> caption.prefix + caption.inner.plain()
        }
    }
    return vsTypical(latest, typical, suffix, decimals)
}

/**
 * The debt tile's caption, deliberately NOT the same wording as [debtHeadline]'s "On target": this one
 * answers "how does tonight compare to the need", so a value inside the deadband still reads as being
 * under the need rather than as having hit it.
 *
 * A [DisplayText] because the three phrases are catalog copy (de "vs. Bedarf"/"Unter Bedarf"/"Im Ziel")
 * and this helper is pinned by a JVM test with no Application attached.
 */
internal fun debtCaption(debt: Double?): DisplayText {
    if (debt == null) return DisplayText.Resource(R.string.l10n_sleep_formatting_vs_need_6d942bfe)
    return if (debt < SleepDebt.ON_TARGET_BAND_MIN) {
        DisplayText.Resource(R.string.l10n_sleep_formatting_on_target_412a8343)
    } else {
        DisplayText.Resource(R.string.l10n_sleep_formatting_below_need_85eb30c0)
    }
}

internal fun debtColor(debt: Double?): Color = when {
    debt == null -> Palette.textPrimary
    debt < SleepDebt.ON_TARGET_BAND_MIN -> Palette.statusPositive
    debt < 60.0 -> Palette.statusWarning
    else -> Palette.statusCritical
}

// MARK: - Sleep-debt ledger formatting (mirror SleepView.swift)

/**
 * "≈2h 10m" magnitude headline — leading "≈" because it's an accumulated estimate. Reads
 * "On target" inside the deadband so a few stray minutes don't show as debt.
 */
internal fun debtHeadline(ledger: SleepDebtLedger): DisplayText =
    if (ledger.magnitudeMin < SleepDebt.ON_TARGET_BAND_MIN) {
        DisplayText.Resource(R.string.l10n_sleep_formatting_on_target_412a8343)
    } else {
        // "≈2h 10m": an approximation sign and a duration, which iOS interpolates rather than translates.
        DisplayText.Prefixed("≈", durationText(ledger.magnitudeMin))
    }

/** Short tag beside the headline: the recurrence never creates a positive surplus. */
internal fun debtTag(ledger: SleepDebtLedger): DisplayText = when {
    ledger.magnitudeMin < SleepDebt.ON_TARGET_BAND_MIN ->
        DisplayText.Resource(R.string.l10n_sleep_formatting_balanced_6217e69f)
    ledger.isDebt -> DisplayText.Resource(R.string.l10n_sleep_formatting_sleep_debt_cbdd4bd3)
    else -> DisplayText.Resource(R.string.l10n_sleep_formatting_balanced_6217e69f)
}

/**
 * Plain-English read of the actionable addition to the next night's target.
 *
 * `@Composable` because the sentence is COMPOSED the way iOS composes it: the span ("the last 3
 * nights") is itself a localized phrase interpolated into a sentence that is not one resource, so the
 * parts have to be resolved where an Application is reachable. The wording is iOS's, including its
 * answer for a surplus night — `SleepDebtLedgerCard.debtRead` returns the "met your need" line there
 * too, which is why this no longer has an Android-only "Nicely ahead." variant.
 */
@Composable
internal fun debtRead(ledger: SleepDebtLedger): String {
    val nights = ledger.nightCount
    val span = if (nights == 1) {
        uiString(R.string.l10n_sleep_formatting_the_last_night_4af00fab)
    } else {
        uiString(R.string.l10n_sleep_formatting_the_last_lld_nights_f4d3b9f8, nights)
    }
    if (ledger.magnitudeMin < SleepDebt.ON_TARGET_BAND_MIN || !ledger.isDebt) {
        return "You've effectively met your current sleep need across $span."
    }
    val mag = durationText(ledger.magnitudeMin).text()
    return "Add about $mag to your base sleep target tonight. Meeting that complete sleep need clears " +
        "the displayed debt; recent shortfalls carry forward at a reduced weight."
}

/**
 * Color the balance by size: within-band → positive green, modest debt →
 * warning, heavier debt → critical.
 */
internal fun debtBalanceColor(ledger: SleepDebtLedger): Color = when {
    ledger.magnitudeMin < SleepDebt.ON_TARGET_BAND_MIN || !ledger.isDebt -> Palette.statusPositive
    ledger.magnitudeMin < 180.0 -> Palette.statusWarning
    else -> Palette.statusCritical
}

/** Signed "+1h 20m" / "−2h 10m" / "0m" balance copy. */
internal fun debtSigned(minutes: Double): DisplayText {
    if (abs(minutes) < 1.0) return DisplayText.Dynamic("0m")
    val sign = if (minutes >= 0.0) "+" else "−"
    return DisplayText.Prefixed(sign, durationText(abs(minutes)))
}

/**
 * The sleep screens' duration copy ("7h 12m" / "45m"), delegating to the ONE canonical implementation in
 * [durationMinutesText]: the catalog translates those formats (ru "…ч …м", zh "…小时…分"), and a second
 * copy of the format here is how the two drift apart.
 */
internal fun durationText(minutes: Double): DisplayText = durationMinutesText(max(0.0, minutes))

internal fun List<Double>.sleepAverageOrNull(): Double? =
    if (isEmpty()) null else sum() / size

/**
 * The display name of a sleep stage, resolved from the English name the rest of the sleep UI keys on.
 *
 * That English name is an IDENTIFIER, not copy: it indexes [stageSharePercent], the stage colours and the
 * segment spans, and the shared `Stages` model carries it. So it cannot be translated where it is
 * declared — every lookup would break at runtime. Instead the two tables that DISPLAY a stage name (the
 * timeline rows and the stage-breakdown rows) resolve it through this ONE function, so they cannot end up
 * naming the same stage differently, which is what a per-site translation would eventually do.
 *
 * A [DisplayText] because the four names are catalog copy (de "Wach"/"Leicht"/"Tief") and a pure helper
 * cannot resolve a resource itself.
 */
internal fun stageDisplayLabel(stage: String): DisplayText = when (stage) {
    "Awake" -> DisplayText.Resource(R.string.l10n_xiaomi_band_screen_awake_2b453e3e)
    "REM" -> DisplayText.Resource(R.string.l10n_xiaomi_band_screen_rem_e56eb975)
    "Light" -> DisplayText.Resource(R.string.l10n_onboarding_screen_light_a36ef8ab)
    "Deep" -> DisplayText.Resource(R.string.l10n_xiaomi_band_screen_deep_be7a6a37)
    else -> DisplayText.Dynamic(stage)
}
