package com.noop.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import com.noop.R
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.noop.data.LiftLogRepository
import com.noop.data.LiftProgramItemRow
import com.noop.data.LiftProgramRow
import com.noop.ingest.ImportedProgram
import com.noop.ingest.ImportedProgramLine
import com.noop.ingest.LiftProgramImportResult
import com.noop.ingest.LiftProgramSheetImporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

// Build a Lift Log program from a spreadsheet filled in on a computer.
//
// Typing a dozen exercises and all their targets on a phone is the most tedious thing in the feature, and it
// is exactly the work a spreadsheet on a real keyboard is good at. This reads the filled-in template and
// shows what it found BEFORE writing anything, because a program is a plan the user then trains from.
//
// Kotlin twin of `Strand/Screens/LiftProgramImportSheet.swift`.

@Composable
fun LiftProgramImportSheet(
    vm: AppViewModel,
    deviceId: String,
    onImported: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val repo = vm.liftLog
    val scope = rememberCoroutineScope()

    var parsed by remember { mutableStateOf<LiftProgramImportResult?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var importing by remember { mutableStateOf(false) }

    // Any file: a provider is free to report a .csv as octet-stream, and the parser's own detection (ZIP
    // magic bytes, else CSV) is what actually decides — so narrowing the picker would only refuse files
    // that would have worked.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Reading is off the main thread (a picked file can be anywhere), so the callback hands off.
        scope.launch {
            val read = readPickedFile(context, uri)
            when (read) {
                is PickedFile.Bytes -> {
                    val result = runCatching { LiftProgramSheetImporter.parse(read.data) }
                    parsed = result.getOrNull()
                    failure = result.exceptionOrNull()?.let { messageFor(it) }
                }
                PickedFile.TooLarge -> {
                    parsed = null
                    failure = messageFor(LiftProgramSheetImporter.ImportError.TooLarge)
                }
                PickedFile.Unreadable -> {
                    parsed = null
                    // The URI itself would not open, which is not the parser's "this is not a
                    // spreadsheet" — that one is `ImportError.Unreadable`, below.
                    failure = uiString(R.string.l10n_lift_that_file_could_not_be_read_708f19db)
                }
            }
        }
    }

    val canImport = parsed != null && !importing

    ScreenScaffold(
        title = uiString(R.string.l10n_lift_import_a_program_1449dc00),
        // No subtitle: iOS's sheet is a plain navigation title over the intro card, whose own copy
        // already says what to do with the template.
        leading = { SheetTextLink(uiString(R.string.l10n_components_close_bbfa773e), onClick = onClose) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.sectionGap)) {
            NoopCard {
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    Text(uiString(R.string.l10n_lift_fill_it_in_on_a_computer_2b1f4499), style = NoopType.headline, color = Palette.textPrimary)
                    Text(
                        uiString(R.string.l10n_lift_download_the_template_from_the_noop_repo_a5b7a23c),
                        style = NoopType.footnote,
                        color = Palette.textSecondary,
                    )
                    Text(
                        uiString(R.string.l10n_lift_only_the_exercise_name_is_required_anyth_1513782c),
                        style = NoopType.footnote,
                        color = Palette.textTertiary,
                    )
                    NoopButton(
                        text = uiString(R.string.l10n_lift_choose_a_file_74b1d89d),
                        leadingIcon = Icons.Filled.UploadFile,
                        kind = NoopButtonKind.Primary,
                        fullWidth = true,
                        onClick = { picker.launch(arrayOf("*/*")) },
                    )
                }
            }

            failure?.let { message ->
                NoopCard {
                    Text(message, style = NoopType.body, color = Palette.statusWarning)
                }
            }

            parsed?.let { result ->
                // The cards, directly — iOS's preview has no section header above them.
                Column(verticalArrangement = Arrangement.spacedBy(Metrics.gap)) {
                    result.programs.forEach { program -> ImportPreviewCard(program) }
                }

                if (result.warnings.isNotEmpty()) {
                    NoopCard {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Overline(uiString(R.string.l10n_lift_worth_checking_59cd52ee), color = Palette.statusWarning)
                            result.warnings.forEach { warning ->
                                Text(warning, style = NoopType.footnote, color = Palette.textSecondary)
                            }
                            Text(
                                uiString(R.string.l10n_lift_these_lines_still_import_anything_unclas_7ff89622),
                                style = NoopType.footnote,
                                color = Palette.textTertiary,
                            )
                        }
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                SheetTextLink(uiString(R.string.ground_truth_cancel), onClick = onClose)
                Spacer(Modifier.weight(1f))
                NoopButton(
                    text = uiString(R.string.l10n_lift_import_d6fbc9d2),
                    kind = NoopButtonKind.Primary,
                    enabled = canImport,
                    modifier = Modifier.width(160.dp),
                    onClick = {
                        val result = parsed ?: return@NoopButton
                        importing = true
                        scope.launch {
                            performImport(repo, deviceId, result)
                            importing = false
                            onImported()
                            onClose()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ImportPreviewCard(program: ImportedProgram) {
    NoopCard {
        Column(verticalArrangement = Arrangement.spacedBy(Metrics.space10)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(program.name, style = NoopType.headline, color = Palette.textPrimary, modifier = Modifier.weight(1f))
                Text(
                    // A count, so it goes through the plural rules of the LOCALE rather than an English
                    // `if (n == 1)`: Polish and Russian have categories English does not (`uiPlural`).
                    uiPlural(R.plurals.l10n_lift_lld_exercises_a5a14ec2, program.lines.size, program.lines.size),
                    style = NoopType.caption,
                    color = Palette.textSecondary,
                )
            }
            // EVERY line, as iOS lists them: the preview is what you check the file against before
            // importing, and a cap would hide exactly the rows worth catching.
            program.lines.forEach { line ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text(line.exercise, style = NoopType.body, color = Palette.textPrimary, modifier = Modifier.weight(1f))
                    Text(lineSummary(line), style = NoopType.captionNumber, color = Palette.textTertiary)
                }
            }
        }
    }
}

/**
 * "4 x 8 · 60 kg · RPE ≤8 · 120s" — the same shape the Swift `summary(_:)` builds.
 *
 * Twin of `LiftProgramImportSheet.swift`: it is deliberately terser than the program editor's summary
 * (raw seconds, "RPE ≤", the multiplication sign a spreadsheet uses), and NONE of it is copy on the Apple
 * side — every part is plain interpolation — so none of it is translated here either.
 */
internal fun lineSummary(line: ImportedProgramLine): String {
    val parts = mutableListOf<String>()
    val sets = line.targetSets
    val reps = line.targetReps
    if (sets != null && reps != null) parts += "$sets x $reps" else if (sets != null) parts += "$sets x"
    line.targetWeightKg?.let { parts += "${LiftFormat.trim(it)} kg" }
    line.targetMaxRpe?.let { parts += "RPE ≤${LiftFormat.trim(it)}" }
    line.restSec?.let { parts += "${it}s" }
    return parts.joinToString(" · ")
}

// MARK: - Reading the picked file

private sealed interface PickedFile {
    data class Bytes(val data: ByteArray) : PickedFile
    object TooLarge : PickedFile
    object Unreadable : PickedFile
}

/**
 * Read a document the user picked, refusing one that is too big BEFORE pulling it into memory.
 *
 * The size is asked for first because that is the whole point of the limit: reading the file and then
 * discovering it is 200 MB is exactly what the bound exists to prevent, and a phone is where that matters.
 */
private suspend fun readPickedFile(context: android.content.Context, uri: Uri): PickedFile =
    withContext(Dispatchers.IO) {
        val max = LiftProgramSheetImporter.maxFileBytes.toLong()
        val announced = runCatching {
            context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length }
        }.getOrNull()
        if (announced != null && announced > max) return@withContext PickedFile.TooLarge

        try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    // A provider that will not tell us the size still cannot make us read past the bound.
                    if (out.size() + n > max) return@withContext PickedFile.TooLarge
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            } ?: return@withContext PickedFile.Unreadable
            PickedFile.Bytes(bytes)
        } catch (_: Exception) {
            PickedFile.Unreadable
        }
    }

/** What to tell the user when a file will not do. Each failure is a different mistake with different advice. */
private fun messageFor(error: Throwable): String = when (error) {
    is LiftProgramSheetImporter.ImportError.Unreadable ->
        uiString(R.string.l10n_lift_that_does_not_look_like_a_spreadsheet_us_93d0b2a9)
    is LiftProgramSheetImporter.ImportError.MissingColumns ->
        uiString(R.string.l10n_lift_that_sheet_has_no_exercise_column_use_th_12be13b9)
    is LiftProgramSheetImporter.ImportError.Empty ->
        uiString(R.string.l10n_lift_that_sheet_has_no_exercises_in_it_yet_77dcf016)
    LiftProgramSheetImporter.ImportError.TooLarge ->
        uiString(R.string.l10n_lift_that_file_is_too_big_to_be_a_program_she_4b84d8f3)
    else -> uiString(R.string.l10n_lift_that_file_could_not_be_read_708f19db)
}

/**
 * Write the programs, their lines, and the exercises the sheet classified.
 *
 * The exercises are remembered so the picker offers them next time and the per-muscle rollup resolves the
 * same name the same way. Best-effort: the vocabulary is capped, and hitting the cap must not fail an
 * import of the programs themselves.
 */
private suspend fun performImport(repo: LiftLogRepository, deviceId: String, result: LiftProgramImportResult) {
    val now = System.currentTimeMillis() / 1000L
    for (program in result.programs) {
        val programId = UUID.randomUUID().toString()
        repo.upsertPrograms(
            listOf(
                LiftProgramRow(
                    id = programId, deviceId = deviceId, name = program.name, note = program.note,
                    createdAt = now, updatedAt = now, archived = false,
                ),
            ),
        )
        repo.replaceProgramItems(
            programId,
            program.lines.mapIndexed { index, line ->
                LiftProgramItemRow(
                    id = UUID.randomUUID().toString(), deviceId = deviceId, programId = programId,
                    ord = index, exercise = line.exercise,
                    targetSets = line.targetSets,
                    // The sheet plans ONE rep count, which is what the editor plans too; the range's high end
                    // stays null rather than inventing a spread nobody typed.
                    targetRepsLow = line.targetReps, targetRepsHigh = null, targetRpe = line.targetMaxRpe,
                    targetWeightKg = line.targetWeightKg, restSec = line.restSec, note = line.note,
                )
            },
        )
        runCatching {
            repo.rememberExercises(
                program.lines.map { line ->
                    com.noop.data.liftExerciseEntity(
                        id = UUID.randomUUID().toString(), deviceId = deviceId, name = line.exercise,
                        primaryMuscle = line.primaryMuscle, secondaryMuscles = line.secondaryMuscles,
                        createdAt = now, lastUsedTs = null,
                    )
                },
            )
        }
    }
}
