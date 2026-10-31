package com.noop.ui

import com.noop.R

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.noop.ai.CoachSharing
import com.noop.ai.AiKeyStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
internal fun CoachSharingCard(vm: CoachViewModel) {
    val ctx = LocalContext.current
    var revision by remember { mutableIntStateOf(0) }
    var warning by remember { mutableStateOf(false) }
    val provider by vm.provider.collectAsStateWithLifecycle()
    val endpoint by vm.customBaseUrl.collectAsStateWithLifecycle()
    val full = remember(provider, endpoint, revision) { CoachSharing.full(ctx) }
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space12)) {
            Text(uiString(R.string.feature_coachsharingcard_0), style = NoopType.headline, color = Palette.textPrimary)
            Text(uiString(R.string.feature_coachsharingcard_1), style = NoopType.footnote, color = Palette.textSecondary)
            CoachSharing.Category.entries.forEach { category ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(category.title, Modifier.weight(1f), style = NoopType.subhead, color = Palette.textPrimary)
                    Switch(checked = CoachSharing.enabled(ctx, category), onCheckedChange = { CoachSharing.set(ctx, category, it); revision++ }, enabled = !full)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(uiString(R.string.feature_coachsharingcard_2), Modifier.weight(1f), style = NoopType.subhead, color = Palette.textPrimary)
                Switch(checked = full, onCheckedChange = {
                    if (it) warning = true else { CoachSharing.setFull(ctx, false); revision++ }
                })
            }
            Text(uiString(R.string.feature_coachsharingcard_3), style = NoopType.footnote, color = Palette.textSecondary)
        }
    }
    if (warning) AlertDialog(
        onDismissRequest = { warning = false },
        title = { Text(uiString(R.string.feature_coachsharingcard_4)) },
        text = { Text(uiString(R.string.feature_coachsharingcard_5)) },
        confirmButton = { TextButton(onClick = { CoachSharing.setFull(ctx, true); vm.setConsent(ctx, true); warning = false; revision++ }) { Text(uiString(R.string.feature_coachsharingcard_6)) } },
        dismissButton = { TextButton(onClick = { warning = false }) { Text(uiString(R.string.feature_coachsharingcard_7)) } },
    )
}
