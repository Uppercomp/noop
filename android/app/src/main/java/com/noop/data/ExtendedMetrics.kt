package com.noop.data

import com.noop.analytics.EnergyBalance

/** Android presentation adapter over existing portable tables. No new stored physiological scores. */
object ExtendedMetrics {
    const val BALANCE = "energy_balance"
    const val INTAKE = "calories_in"
    const val BURN = "total_kcal"
    const val OXYGEN = "spo2_candidate"

    suspend fun series(repo: WhoopRepository, strap: String, key: String, from: String, to: String): List<Pair<String, Double>> {
        if (key == OXYGEN) return repo.metricSeriesComputedUnion(strap, key, from, to).map { it.day to it.value }
        val hc = repo.metricSeries("health-connect", INTAKE, from, to).associate { it.day to it.value }
        val csv = repo.metricSeries("nutrition-csv", INTAKE, from, to).associate { it.day to it.value }
        val intake = csv + hc
        if (key == INTAKE) return intake.toSortedMap().toList()
        val days = repo.daysMerged(strap).filter { it.day >= from && it.day <= to }
        val imported = repo.appleDaily("apple-health", from, to) + repo.appleDaily("health-connect", from, to)
        val burn = days.mapNotNull { d -> d.activeKcalEst?.let { d.day to it } }.toMap().toMutableMap()
        imported.forEach { d ->
            // Active energy alone is not whole-day expenditure.
            if (d.activeKcal != null && d.basalKcal != null) burn[d.day] = d.activeKcal + d.basalKcal
        }
        repo.metricSeries("health-connect", BURN, from, to).forEach { burn[it.day] = it.value }
        if (key == BURN) return burn.toSortedMap().toList()
        if (key == BALANCE) return intake.mapNotNull { (day, kcal) ->
            EnergyBalance.net(kcal, burn[day])?.let { day to it }
        }.sortedBy { it.first }
        return emptyList()
    }
}
