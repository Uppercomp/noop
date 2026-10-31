package com.noop.analytics

/** Logged intake minus total expenditure; absent intake is never treated as zero food. */
object EnergyBalance {
    fun net(intake: Double?, totalBurn: Double?): Double? =
        if (intake != null && totalBurn != null && intake.isFinite() && totalBurn.isFinite() &&
            intake >= 0 && totalBurn > 0) intake - totalBurn else null
}
