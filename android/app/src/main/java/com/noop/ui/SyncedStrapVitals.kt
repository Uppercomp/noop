package com.noop.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.platform.LocalContext
import java.time.LocalDate
import java.util.Locale

@Composable
internal fun SyncedStrapVitals(vm: AppViewModel) {
    val oxygen by vm.spo2CandidateByDay.collectAsStateWithLifecycle()
    val days by vm.recentDays.collectAsStateWithLifecycle()
    val cutoff = LocalDate.now().minusDays(14).toString()
    val unit = UnitPrefs.temperature(LocalContext.current)
    val candidate = oxygen.filterKeys { it >= cutoff }.maxByOrNull { it.key }
    val temp = days.lastOrNull { it.day >= cutoff && it.skinTempC != null }
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space8)) {
            Text("Latest synced vitals", style = NoopType.headline, color = Palette.textPrimary)
            Text(candidate?.let { "Oxygen estimate %.0f%% · %s".format(Locale.US, it.value, it.key) } ?: "Oxygen estimate: no recent candidate",
                style = NoopType.subhead, color = Palette.metricCyan)
            Text("Not validated. This is a synced nightly candidate, not a continuous live oxygen reading.", style = NoopType.footnote, color = Palette.textSecondary)
            Text(temp?.let { "Skin temperature ${UnitFormatter.temperatureFromCelsius(it.skinTempC!!, unit, 2)} · ${it.day}" } ?: "Skin temperature: no recent night",
                style = NoopType.subhead, color = Palette.textPrimary)
        }
    }
}
