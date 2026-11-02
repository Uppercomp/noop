package com.noop.ui

import com.noop.analytics.HydrationStore
import com.noop.data.ExtendedMetrics
import com.noop.data.WhoopRepository
import com.noop.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.AlertDialog

/** Android presentation catalog. Aliases resolve here; stored metric keys are never rewritten. */
internal object MetricLibrary {
    fun canonical(key: String): String = when (key) {
        "energy_kcal" -> "total_kcal"
        "spo2_candidate" -> "spo2"
        "steps_est" -> "steps"
        "charge" -> "recovery"
        "effort" -> "strain"
        "rest" -> "sleep_performance"
        "restingHr" -> "rhr"
        "bloodOxygen" -> "spo2"
        "respiratory", "resp" -> "resp_rate"
        "skinTemp", "skin" -> "skin_temp"
        "calories" -> "total_kcal"
        "sleep" -> "sleep_total_min"
        "fitnessAge" -> "fitness_age"
        "stepsAverage30" -> "steps_average_30"
        else -> key
    }

    val all: List<CompareMetric> get() = CompareCatalog.all
    fun isSnapshot(key: String): Boolean = canonical(key) in setOf("weight", "body_fat", "lean_mass", "bmi", "vo2max", "vo2max_est", "fitness_age", "vitality", "body_age")
    fun readingForDay(key: String, points: List<Pair<String, Double>>, day: String): Pair<String, Double>? =
        if (isSnapshot(key)) points.filter { it.first <= day }.maxByOrNull { it.first } else points.firstOrNull { it.first == day }
    fun find(key: String): CompareMetric? = all.firstOrNull { it.key == canonical(key) }
    fun matches(title: String, query: String): Boolean {
        fun normalized(s: String) = s.lowercase().replace("₂", "2").replace("sp02", "spo2")
        val lower = normalized(title)
        val aliases = when {
            lower.contains("oxygen") || lower.contains("sauerstoff") -> " spo2 oxygen o2"
            lower.contains("balance") || lower.contains("bilanz") -> " deficit surplus intake expenditure"
            lower.contains("calories in") || lower.contains("kalorienaufnahme") -> " food intake nutrition"
            lower.contains("variability") || lower.contains("variabilit") -> " hrv"
            lower.contains("resting heart") || lower.contains("ruhepuls") -> " rhr"
            else -> ""
        }
        return normalized(title + aliases).contains(normalized(query.trim()))
    }
    fun accent(key: String): Color = when (find(key)?.category) {
        "Nutrition" -> Palette.metricAmber
        "Rest" -> Palette.restColor
        "Effort" -> Palette.effortColor
        "Mind" -> Palette.metricPurple
        else -> Palette.metricCyan
    }
    fun group(key: String): String = uiString(CompareCatalog.categoryDisplayRes(find(key)?.category ?: "Health"))

    fun displayValue(key: String, value: Double, system: UnitSystem, temperature: TemperatureUnit, effort: EffortScale): Double {
        val metric = find(key) ?: return value
        return when {
            metric.key == "strain" -> UnitFormatter.effortValue(value, effort)
            metric.unit == "kg" && system == UnitSystem.IMPERIAL -> UnitFormatter.kgToPounds(value)
            metric.unit == "°C" && temperature == TemperatureUnit.FAHRENHEIT ->
                if (metric.key == "skin_temp" && com.noop.analytics.SkinTempDisplay.kind(value) == com.noop.analytics.SkinTempDisplay.Kind.DEVIATION)
                    UnitFormatter.celsiusToFahrenheit(value) - 32.0 else UnitFormatter.celsiusToFahrenheit(value)
            else -> value
        }
    }

    suspend fun series(repo: WhoopRepository, strap: String, key: String, from: String, to: String,
                       allowOxygenEstimate: Boolean): List<Pair<String, Double>> = withContext(Dispatchers.Default) {
        readSeries(repo, strap, key, from, to, allowOxygenEstimate)
    }

    private suspend fun readSeries(repo: WhoopRepository, strap: String, key: String, from: String, to: String,
                                   allowOxygenEstimate: Boolean): List<Pair<String, Double>> {
        val k = canonical(key)
        if (k == "steps") return stepReadings(repo, strap, from, to).map { it.day to it.value }
        if (k == "stress") {
            val stored = repo.metricSeries("my-whoop", "stress", "0000-01-01", "9999-12-31")
                .associate { it.day to it.value.coerceIn(0.0, 3.0) }
            return StressModel.build(repo.daysMerged(strap), stored)?.fullTrend.orEmpty()
                .filter { it.day in from..to }.map { it.day to it.value }
        }
        if (k == "steps_average_30") {
            val start = if (from < "1900-01-01") from else java.time.LocalDate.parse(from).minusDays(29).toString()
            val readings = stepReadings(repo, strap, start, to)
            val first = readings.firstOrNull()?.day ?: return emptyList()
            val begin = java.time.LocalDate.parse(maxOf(first, from))
            val end = minOf(java.time.LocalDate.parse(to), java.time.LocalDate.now())
            return generateSequence(begin) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.mapNotNull { date ->
                val cutoff = date.minusDays(29).toString()
                rollingStepsAverage(readings.filter { it.day >= cutoff && it.day <= date.toString() }, date).first
                    ?.let { date.toString() to it }
            }.toList()
        }
        if (k in setOf("calories_in", "total_kcal", "energy_balance", "protein_g", "carbs_g", "fat_g"))
            return ExtendedMetrics.series(repo, strap, k, from, to)
        if (k == "active_kcal") {
            // Apple active energy excludes resting burn; never fill it with NOOP's TOTAL estimate.
            val daily = repo.appleDaily("apple-health", from, to).mapNotNull { d -> d.activeKcal?.let { d.day to it } }.toMap()
            val points = repo.metricSeries("apple-health", k, from, to).associate { it.day to it.value }
            return (daily + points).toSortedMap().toList()
        }
        if (k == "spo2") {
            val measured = repo.resolvedSeries("spo2", "my-whoop", from, to, strapDeviceId = strap).values.toMap()
            val candidate = if (allowOxygenEstimate) ExtendedMetrics.series(repo, strap, "spo2_candidate", from, to).toMap() else emptyMap()
            return (candidate + measured).toSortedMap().toList()
        }
        if (k == "hydration") return HydrationStore.sumByDay(
            repo.metricSeries(HydrationStore.SOURCE_ID, HydrationStore.KEY, from, to) +
                repo.metricSeries(HydrationStore.SOURCE_ID, HydrationStore.KEY_IMPORTED, from, to)
        ).toSortedMap().toList()
        val metric = find(k) ?: return emptyList()
        val resolved = repo.resolvedSeries(k, metric.source, from, to, strapDeviceId = strap).values
        if (k == "weight") {
            val nutrition = repo.metricSeries("nutrition-csv", k, from, to).associate { it.day to it.value }
            return (nutrition + resolved.toMap()).toSortedMap().toList()
        }
        return resolved
    }

    suspend fun stepReadings(repo: WhoopRepository, strap: String, from: String, to: String): List<VitalReading> {
        val real = repo.daysMerged(strap).filter { it.day in from..to }.mapNotNull { d ->
            d.steps?.let { d.day to VitalReading(d.day, it.toDouble(), "my-whoop") }
        }.toMap()
        val imported = (repo.appleDaily("apple-health", from, to) + repo.appleDaily("health-connect", from, to))
            .filter { it.steps != null }.groupBy { it.day }.mapValues { (day, rows) ->
                val row = rows.maxBy { it.steps!! }; VitalReading(day, row.steps!!.toDouble(), row.deviceId)
            }
        val estimates = repo.metricSeriesComputedUnion(strap, "steps_est", from, to)
            .associate { it.day to VitalReading(it.day, it.value, "my-whoop-noop") }
        return mergeStepsReadings(real, imported, estimates)
    }

    fun caption(metric: CompareMetric, value: Double?, day: String? = null, readingDay: String? = null): String = when {
        value == null -> uiString(R.string.metric_not_recorded_date)
        readingDay != null && readingDay != day -> uiString(R.string.metric_as_of, readingDay)
        metric.key == "energy_balance" -> {
            val label = uiString(when { value < 0 -> R.string.metric_deficit; value > 0 -> R.string.metric_surplus; else -> R.string.metric_balanced })
            uiString(if (day == java.time.LocalDate.now().toString()) R.string.metric_balance_today else R.string.metric_balance_estimate, label)
        }
        metric.key == "spo2" -> uiString(R.string.metric_oxygen_honesty)
        else -> uiString(R.string.metric_selected_date)
    }
}

internal fun KeyMetric.catalogKey(): String = libraryKey ?: MetricLibrary.canonical(raw)
internal fun DashboardCard.catalogKey(): String? = libraryKey ?: when (this) {
    DashboardCard.COACH, DashboardCard.COUPLED -> null
    DashboardCard.VO2MAX -> "vo2max_est"
    else -> MetricLibrary.canonical(raw)
}

/** The focused detail can pin to either Today section, using the same persisted order as its editors. */
@Composable
internal fun PinMetricToToday(key: String) {
    val context = LocalContext.current
    val canonical = MetricLibrary.canonical(key)
    val tile = KeyMetric.entries.firstOrNull { it.catalogKey() == canonical }
    val card = DashboardCard.entries.firstOrNull { it.catalogKey() == canonical }
    if (tile == null && card == null) return
    var open by remember { mutableStateOf(false) }
    var tiles by remember(open) { mutableStateOf(KeyMetricPrefs.enabled(context)) }
    var cards by remember(open) { mutableStateOf(DashboardCardPrefs.enabled(context)) }
    TextButton(onClick = { open = true }) { Text(uiString(R.string.metric_pin_today), color = Palette.accent) }
    if (open) AlertDialog(onDismissRequest = { open = false }, containerColor = Palette.surfaceOverlay,
        title = { Text(uiString(R.string.metric_pin_today), style = NoopType.title2) },
        text = { Column {
            MetricLibrary.find(canonical)?.let { Text(uiString(it.titleRes), style = NoopType.headline) }
            tile?.let { option -> Row(Modifier.fillMaxWidth().toggleable(value = option in tiles,
                enabled = option !in tiles || tiles.size > 1, role = Role.Checkbox,
                onValueChange = { tiles = if (it) listOf(option) + tiles else tiles - option }),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = option in tiles, enabled = option !in tiles || tiles.size > 1,
                    onCheckedChange = null)
                Text(uiString(R.string.today_section_key_metrics), style = NoopType.body)
            } }
            card?.let { option -> Row(Modifier.fillMaxWidth().toggleable(value = option in cards,
                enabled = option !in cards || cards.size > 1, role = Role.Checkbox,
                onValueChange = { cards = if (it) listOf(option) + cards else cards - option }),
                verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = option in cards, enabled = option !in cards || cards.size > 1,
                    onCheckedChange = null)
                Text(uiString(R.string.today_section_your_cards), style = NoopType.body)
            } }
        } },
        confirmButton = { TextButton(onClick = {
            KeyMetricPrefs.setEnabled(context, tiles.distinct())
            DashboardCardPrefs.setEnabled(context, cards.distinct())
            open = false
        }) { Text(uiString(R.string.l10n_today_screen_done_e9b450d1)) } },
        dismissButton = { TextButton(onClick = { open = false }) { Text(context.getString(android.R.string.cancel)) } })
}
