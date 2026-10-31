package com.noop.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.noop.R
import com.noop.ingest.HealthConnectImporter.ImportCategory

/**
 * Shared category consent surface for onboarding and Data Sources (#645). Keeping the same selector
 * in both entry points prevents onboarding from quietly requesting a broader permission set than the
 * settings flow. The last enabled category cannot be switched off because an empty import request has
 * no useful or explainable result.
 */
@Composable
internal fun HealthConnectCategorySelector(
    selected: Set<ImportCategory>,
    onSelectionChange: (Set<ImportCategory>) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            uiString(R.string.health_connect_categories_title),
            style = NoopType.subhead,
            color = Palette.textPrimary,
        )
        Text(
            uiString(R.string.health_connect_categories_detail),
            style = NoopType.footnote,
            color = Palette.textTertiary,
        )

        ImportCategory.entries.forEach { category ->
            val checked = category in selected
            val canToggle = !checked || selected.size > 1
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        uiString(category.titleRes()),
                        style = NoopType.subhead,
                        color = Palette.textPrimary,
                    )
                    Text(
                        uiString(category.detailRes()),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                }
                Switch(
                    checked = checked,
                    enabled = canToggle,
                    onCheckedChange = { enabled ->
                        val next = if (enabled) selected + category else selected - category
                        if (next.isNotEmpty()) onSelectionChange(next)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = Palette.surfaceBase,
                        checkedTrackColor = Palette.accent,
                        uncheckedThumbColor = Palette.textSecondary,
                        uncheckedTrackColor = Palette.surfaceInset,
                        uncheckedBorderColor = Palette.hairline,
                    ),
                )
            }
        }
    }
}

@StringRes
private fun ImportCategory.titleRes(): Int = when (this) {
    ImportCategory.RECOVERY -> R.string.health_connect_category_recovery
    ImportCategory.ACTIVITY -> R.string.health_connect_category_activity
    ImportCategory.NUTRITION -> R.string.feature_nutrition
    ImportCategory.BODY_COMPOSITION -> R.string.health_connect_category_body_composition
}

@StringRes
private fun ImportCategory.detailRes(): Int = when (this) {
    ImportCategory.RECOVERY -> R.string.health_connect_category_recovery_detail
    ImportCategory.ACTIVITY -> R.string.health_connect_category_activity_detail
    ImportCategory.NUTRITION -> R.string.feature_nutrition_detail
    ImportCategory.BODY_COMPOSITION -> R.string.health_connect_category_body_composition_detail
}
