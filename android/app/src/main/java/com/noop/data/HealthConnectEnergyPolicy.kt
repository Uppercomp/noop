package com.noop.data

/** This Android fork imports food energy, but keeps expenditure under NOOP/WHOOP authority. */
internal object HealthConnectEnergyPolicy {
    fun isExcluded(source: String, key: String): Boolean = source == "health-connect" &&
        key in setOf("active_kcal", "energy_kcal", "total_kcal", "basal_kcal")

    // Read-side only: older imports remain intact in the database and in backups.
    fun daily(row: AppleDaily): AppleDaily = if (row.deviceId == "health-connect")
        row.copy(activeKcal = null, basalKcal = null) else row
}
