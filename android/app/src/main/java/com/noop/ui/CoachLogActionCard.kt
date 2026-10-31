package com.noop.ui

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import com.noop.NoopApplication
import com.noop.ai.CoachLogAction
import com.noop.ai.ChatMsg
import kotlinx.coroutines.launch

@Composable
internal fun CoachLogActionCard(message: ChatMsg, sending: Boolean) {
    val action = remember(message.text) { CoachLogAction.parse(message.text) } ?: return
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("noop_confirmed_coach_actions", android.content.Context.MODE_PRIVATE) }
    var saved by remember(message.id) { mutableStateOf(prefs.getBoolean(message.id, false)) }
    var preview by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    Text(action.preview(), style = NoopType.footnote, color = Palette.textSecondary)
    TextButton(enabled = !sending && !saved && !saving, onClick = { preview = true }) {
        Text(if (saved) "Saved to NOOP" else "Review and confirm log")
    }
    error?.let { Text(it, style = NoopType.footnote, color = Palette.statusWarning) }
    if (preview) AlertDialog(
        onDismissRequest = { if (!saving) preview = false },
        title = { Text("Confirm this log") },
        text = { Column(Modifier.heightIn(max = Metrics.editorListMaxHeight).verticalScroll(rememberScrollState())) {
            Text(action.preview() + "\n\nSave these exact values? Nothing will be sent to the strap, and existing entries will not be overwritten.")
        } },
        confirmButton = { TextButton(enabled = !saving, onClick = {
            saving = true
            scope.launch {
                try {
                    val app = ctx.applicationContext as NoopApplication
                    action.save(app.repository, app.activeDeviceId)
                    if (action is CoachLogAction.Journal) {
                        val questions = loadCustomJournalQuestions(ctx)
                        if (questions.none { it.equals(action.question, ignoreCase = true) }) saveCustomJournalQuestions(ctx, questions + action.question)
                    }
                    prefs.edit().putBoolean(message.id, true).apply()
                    saved = true
                    preview = false
                } catch (e: Exception) { error = e.message ?: "Unable to save log"; preview = false }
                finally { saving = false }
            }
        }) { Text(if (saving) "Saving…" else "Confirm and save") } },
        dismissButton = { TextButton(enabled = !saving, onClick = { preview = false }) { Text("Cancel") } },
    )
}
