package com.noop.ui

import com.noop.R

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.noop.data.ExtendedMetrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.Locale

@Composable
internal fun EnergyBodyScreen(vm: AppViewModel, onImport: () -> Unit, onCompare: () -> Unit) {
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val units = UnitPrefs.system(LocalContext.current)
    var selected by remember { mutableStateOf(LocalDate.now()) }
    var series by remember { mutableStateOf<Map<String, Map<String, Double>>>(emptyMap()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(days, selected) {
        loading = true
        error = null
        try {
            series = withContext(Dispatchers.IO) {
                val from = selected.minusDays(30).toString()
                val to = selected.toString()
                val energy = listOf("calories_in", "total_kcal", "energy_balance").associateWith {
                    ExtendedMetrics.series(vm.repo, vm.activeStrapId, it, from, to).toMap()
                }
                energy + listOf("weight", "body_fat", "lean_mass", "bmi").associateWith {
                    vm.repo.resolvedSeries(it, "apple-health", from, to, strapDeviceId = vm.activeStrapId).values.toMap()
                }
            }
        } catch (e: Exception) { error = e.message ?: "Unable to load data" }
        finally { loading = false }
    }
    fun value(key: String, unit: String) = series[key]?.get(selected.toString())?.let {
        if (unit == "kg") UnitFormatter.massFromKilograms(it, units) else String.format(Locale.US, "%.1f %s", it, unit)
    } ?: "Not recorded"
    ScreenScaffold(title = "Energy & body", subtitle = "Food logs, total expenditure and body measurements") {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { selected = selected.minusDays(1) }) { Text(uiString(R.string.feature_energybodyscreen_11)) }
            Text(selected.toString(), Modifier.weight(1f), style = NoopType.subhead, color = Palette.textPrimary)
            TextButton(onClick = { selected = selected.plusDays(1) }, enabled = selected < LocalDate.now()) { Text(uiString(R.string.feature_energybodyscreen_12)) }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = Palette.statusWarning, style = NoopType.footnote) }
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                Text(uiString(R.string.feature_energybodyscreen_13), style = NoopType.headline, color = Palette.textPrimary)
                Text("Logged intake: ${value("calories_in", "kcal")}", style = NoopType.subhead, color = Palette.textPrimary)
                Text("Total burn: ${value("total_kcal", "kcal")}", style = NoopType.subhead, color = Palette.textPrimary)
                val balance = series["energy_balance"]?.get(selected.toString())
                Text(balance?.let { "%+.0f kcal · %s".format(Locale.US, it, if (it < 0) "Deficit" else if (it > 0) "Surplus" else "Balanced") }
                    ?: "Balance needs both intake and total burn", style = NoopType.title1, color = Palette.metricAmber)
                Text(uiString(R.string.feature_energybodyscreen_14), style = NoopType.footnote, color = Palette.textSecondary)
            }
        }
        NoopCard {
            Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
                Text(uiString(R.string.feature_energybodyscreen_15), style = NoopType.headline, color = Palette.textPrimary)
                Text("Weight: ${value("weight", "kg")}", style = NoopType.subhead, color = Palette.textPrimary)
                Text("Body fat: ${value("body_fat", "%")}", style = NoopType.subhead, color = Palette.textPrimary)
                Text("Lean mass: ${value("lean_mass", "kg")}", style = NoopType.subhead, color = Palette.textPrimary)
                Text("BMI: ${value("bmi", "")}", style = NoopType.subhead, color = Palette.textPrimary)
                Text(uiString(R.string.feature_energybodyscreen_16), style = NoopType.footnote, color = Palette.textSecondary)
            }
        }
        Row { TextButton(onClick = onImport) { Text(uiString(R.string.feature_energybodyscreen_17)) }; TextButton(onClick = onCompare) { Text(uiString(R.string.feature_energybodyscreen_18)) } }
        Text(uiString(R.string.feature_energybodyscreen_19), style = NoopType.headline, color = Palette.textPrimary)
        series["energy_balance"].orEmpty().toSortedMap(reverseOrder()).forEach { (day, balance) ->
            Row(Modifier.fillMaxWidth()) {
                Text(day, Modifier.weight(1f), style = NoopType.body, color = Palette.textSecondary)
                Text(uiString(R.string.feature_energybodyscreen_20).format(Locale.US, balance), style = NoopType.bodyNumber, color = Palette.textPrimary)
            }
        }
    }
}
