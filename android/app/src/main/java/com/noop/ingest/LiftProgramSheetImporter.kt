package com.noop.ingest

import com.noop.data.LiftLogRepository
import com.noop.data.LiftMuscle
import kotlin.math.roundToInt

// Build a Lift Log PROGRAM from a spreadsheet filled in on a computer.
//
// WHY THIS EXISTS. A program is a name plus an ordered list of exercise lines, and every line carries an
// exercise, a muscle classification, sets, reps, a weight, a max RPE, a rest period and a technique note.
// Typing all of that on a phone, for a dozen exercises, is the single most tedious thing in the feature —
// and it is exactly the kind of work a spreadsheet on a real keyboard is good at.
//
// This reads the filled-in sheet and produces programs. It never WRITES: the caller decides what to do with
// the result, which keeps this parser pure and testable with no store and no app.
//
// TWO FORMATS, ONE PARSER. `.xlsx` is what the shipped template is, because a spreadsheet can lock its own
// structure and offer the muscle vocabulary as a dropdown — the user cannot mistype a muscle name that has
// to match a closed token set. CSV is accepted too, because every spreadsheet program on every platform can
// save it, and because a user who rebuilds the sheet by hand should not be blocked. Detection is by ZIP
// magic bytes, the way the backup path tells a zipped backup from a bare SQLite file.
//
// Kotlin twin of `Packages/StrandImport/Sources/StrandImport/LiftProgramSheetImporter.swift`.

/** One exercise line read from the sheet — the TARGETS, mirroring `liftProgramItem`. */
data class ImportedProgramLine(
    val exercise: String,
    val primaryMuscle: LiftMuscle?,
    val secondaryMuscles: List<LiftMuscle>,
    val targetSets: Int?,
    val targetReps: Int?,
    val targetWeightKg: Double?,
    /** The line's max RPE, 1-10: a ceiling to stay under, stored as `liftProgramItem.targetRpe`. */
    val targetMaxRpe: Double?,
    val restSec: Int?,
    val note: String?,
)

/** One program: a name, an optional note, and its lines IN SHEET ORDER. */
data class ImportedProgram(val name: String, val note: String?, val lines: List<ImportedProgramLine>)

/**
 * What a sheet produced, plus everything that was wrong with it.
 *
 * Warnings are per-row and NON-fatal by design. A sheet with one misspelled muscle should import eleven
 * good lines and tell the user about the twelfth, not refuse the file — the alternative is a user fixing
 * one typo at a time through twelve round trips.
 */
data class LiftProgramImportResult(val programs: List<ImportedProgram>, val warnings: List<String>)

object LiftProgramSheetImporter {

    sealed class ImportError(message: String) : Exception(message) {
        /** Not a spreadsheet this can read at all. */
        object Unreadable : ImportError("That file could not be read as a spreadsheet.")

        /** Readable, but without the columns that make it a program sheet. */
        data class MissingColumns(val columns: List<String>) :
            ImportError("That sheet has no ${columns.joinToString()} column.")

        /** Read fine, but there was nothing in it. */
        object Empty : ImportError("That sheet has no exercises in it yet.")

        /** Too big to be a program sheet — see [maxFileBytes]. */
        object TooLarge : ImportError("That file is too large to be a program sheet.")
    }

    // MARK: - Bounds
    //
    // A program is a few dozen rows. Everything below is far above any real sheet and far below anything
    // that could hurt: this feature is a convenience, and it must never be the reason the app is slow, runs
    // out of memory, or writes a database nobody wants.

    /** A filled template is ~9 KB; a hand-built sheet with a year of programs is well under a megabyte. */
    const val maxFileBytes: Int = 8 * 1024 * 1024

    /** Rows considered from a sheet — a spreadsheet can carry a million empty rows, and some writers emit them. */
    internal const val maxRows = 5_000

    /** Exercise lines kept per program. A program with more lines than this is not a program. */
    internal const val maxLinesPerProgram = 200

    /** Programs created from one file. */
    internal const val maxPrograms = 50

    /** Warnings reported. A pathological sheet could otherwise produce thousands; the count is still honest. */
    internal const val maxWarnings = 50

    /**
     * Column keys, after [HeaderNorm.normalize]. Several spellings map to the same field so a user who
     * retypes the header — or translates it — is not punished for it.
     */
    private val exerciseKeys = listOf("exercise", "movement", "lift")
    private val programKeys = listOf("program", "programme", "workout", "routine", "day")
    private val programNoteKeys = listOf("program_note", "programme_note", "workout_note", "routine_note")
    private val primaryKeys = listOf("primary_muscle", "primary", "muscle")
    private val secondaryKeys = listOf("secondary_muscles", "secondary_muscle", "secondary")
    private val setsKeys = listOf("sets", "working_sets", "target_sets")
    private val repsKeys = listOf("reps", "rep", "target_reps", "repetitions")
    private val weightKeys = listOf("weight_kg", "weight", "kg", "load", "load_kg")
    private val restKeys = listOf("rest_sec", "rest_seconds", "rest", "rest_s")
    private val noteKeys = listOf("note", "technique_note", "notes", "cue")

    /** Parse a filled-in template. Detects `.xlsx` by its ZIP magic bytes, else treats it as CSV. */
    @Throws(ImportError::class)
    fun parse(data: ByteArray): LiftProgramImportResult {
        // Refuse before doing any work. An .xlsx is a ZIP, so a hostile one can be small on disk and
        // enormous expanded; [XlsxSheet] bounds the expansion separately. This bound is on what the caller
        // handed us.
        if (data.size > maxFileBytes) throw ImportError.TooLarge

        val candidates: List<XlsxSheet.Sheet> = if (isZip(data)) {
            // Every sheet, in tab order — the workbook may carry instructions, notes or the user's own
            // scratch alongside the program, and only this parser can tell which is which.
            XlsxSheet.sheets(data)
        } else {
            val table = CsvTable.fromData(data)
            if (table.headers.isEmpty()) throw ImportError.Unreadable
            // CsvTable has already keyed every row by its normalized header, so the CSV path needs no
            // separate header pass — the two formats converge here.
            listOf(
                XlsxSheet.Sheet(
                    headerKeys = table.normalizedHeaders.filter { it.isNotEmpty() }.toSet(),
                    rows = table.rows,
                ),
            )
        }

        // An exercise column is the one thing a program sheet cannot do without, so it is also how a
        // program sheet is recognised among several. First match in tab order wins.
        //
        // Matched on HEADERS, not on rows, so the three failures stay distinguishable: a file that is not a
        // program sheet at all, and a correct template nobody has filled in yet, are different mistakes and
        // deserve different advice.
        val sheet = candidates.firstOrNull { it.headerKeys.any { key -> key in exerciseKeys } }
            ?: throw ImportError.MissingColumns(listOf("exercise"))
        val rows = sheet.rows
        if (rows.isEmpty()) throw ImportError.Empty

        val programs = ArrayList<ImportedProgram>()
        val indexByName = HashMap<String, Int>()
        val warnings = ArrayList<String>()
        var truncated = false

        for ((i, row) in rows.take(maxRows).withIndex()) {
            // Spreadsheets are full of trailing blank rows; they are not an error.
            val exercise = value(row, exerciseKeys)?.trim() ?: ""
            if (exercise.isEmpty()) {
                if (row.values.any { it.trim().isNotEmpty() } && warnings.size < maxWarnings) {
                    warnings += rowMessage(i, "no exercise name, so the row was skipped")
                }
                continue
            }

            val programName = value(row, programKeys)?.trim()?.ifEmpty { null } ?: "Imported program"

            var primary: LiftMuscle? = null
            value(row, primaryKeys)?.trim()?.ifEmpty { null }?.let { raw ->
                primary = LiftMuscle.fromSheetName(raw)
                if (primary == null && warnings.size < maxWarnings) {
                    warnings += rowMessage(i, "\"$raw\" is not a muscle group, so \"$exercise\" was left unclassified")
                }
            }

            val secondary = ArrayList<LiftMuscle>()
            value(row, secondaryKeys)?.trim()?.ifEmpty { null }?.let { raw ->
                for (part in raw.split(',', ';', '/', '|')) {
                    val token = part.trim()
                    if (token.isEmpty()) continue
                    val m = LiftMuscle.fromSheetName(token)
                    if (m == null) {
                        if (warnings.size < maxWarnings) {
                            warnings += rowMessage(i, "\"$token\" is not a muscle group and was ignored")
                        }
                    } else if (m != primary && m !in secondary) {
                        // The store excludes the primary from the secondary list, so do it here too rather
                        // than leaving a row that says "chest, chest".
                        secondary += m
                    }
                }
            }

            // A max RPE outside the scale is refused with a warning rather than clamped: 12 is a typo, and
            // guessing whether it meant 10 or 1.2 would put a ceiling in the plan nobody chose.
            val parsedRpe = doubleValue(row, listOf("target_max_rpe", "max_rpe", "rpe_max", "target_rpe", "rpe"))
            var maxRpe = parsedRpe
            if (parsedRpe != null && parsedRpe !in 1.0..10.0) {
                maxRpe = null
                if (warnings.size < maxWarnings) {
                    val shown = if (parsedRpe == parsedRpe.roundToInt().toDouble()) "${parsedRpe.roundToInt()}" else "$parsedRpe"
                    warnings += rowMessage(i, "max RPE $shown is not between 1 and 10, so \"$exercise\" has none")
                }
            }

            // Notes are capped to the same lengths the in-app editors enforce. A spreadsheet cell holds far
            // more than a phone can show, and a note that arrives longer than the editor would ever let you
            // type is a note you can never fully see or edit afterwards.
            val line = ImportedProgramLine(
                exercise = exercise,
                primaryMuscle = primary,
                secondaryMuscles = secondary,
                targetSets = intValue(row, setsKeys),
                targetReps = intValue(row, repsKeys),
                targetWeightKg = doubleValue(row, weightKeys),
                targetMaxRpe = maxRpe,
                restSec = intValue(row, restKeys),
                note = value(row, noteKeys)?.trim()?.ifEmpty { null }
                    ?.take(LiftLogRepository.maxExerciseNoteLength),
            )

            val at = indexByName[programName.lowercase()]
            if (at != null) {
                if (programs[at].lines.size >= maxLinesPerProgram) {
                    truncated = true
                    continue
                }
                programs[at] = programs[at].copy(
                    lines = programs[at].lines + line,
                    note = programs[at].note ?: programNote(row),
                )
            } else {
                if (programs.size >= maxPrograms) {
                    truncated = true
                    continue
                }
                indexByName[programName.lowercase()] = programs.size
                programs += ImportedProgram(name = programName, note = programNote(row), lines = listOf(line))
            }
        }

        // Say so rather than silently importing a subset: a user whose sheet was cut off must be able to see
        // that it was, and the preview is where they would notice.
        if (rows.size > maxRows || truncated) {
            warnings += "The sheet is larger than a program can be, so only the first $maxPrograms " +
                "programs and $maxLinesPerProgram exercises each were read."
        }

        if (programs.isEmpty()) throw ImportError.Empty
        return LiftProgramImportResult(programs, warnings)
    }

    // MARK: - Helpers

    private fun isZip(data: ByteArray): Boolean =
        data.size >= 4 && data[0] == 0x50.toByte() && data[1] == 0x4B.toByte()

    /**
     * Warnings name the SHEET's own row number so the user can go straight to it: the header is row 1, so
     * the first data row is row 2.
     */
    private fun rowMessage(i: Int, text: String): String = "Row ${i + 2}: $text"

    private fun programNote(row: Map<String, String>): String? =
        value(row, programNoteKeys)?.trim()?.ifEmpty { null }?.take(LiftLogRepository.maxProgramNoteLength)

    private fun value(row: Map<String, String>, keys: List<String>): String? {
        for (k in keys) {
            val v = row[k]
            if (v != null && v.trim().isNotEmpty()) return v
        }
        return null
    }

    private fun intValue(row: Map<String, String>, keys: List<String>): Int? =
        value(row, keys)?.let { doubleFrom(it) }?.roundToInt()

    private fun doubleValue(row: Map<String, String>, keys: List<String>): Double? =
        value(row, keys)?.let { doubleFrom(it) }

    /**
     * Numbers as spreadsheets actually write them: "60", "60.5", "60,5" (comma decimal in most of Europe),
     * "60 kg", "2 min". Anything with no digits at all is null rather than 0 — a blank cell means "not
     * planned", and 0 would be a planned zero.
     */
    internal fun doubleFrom(raw: String): Double? {
        val cleaned = StringBuilder()
        var seenSeparator = false
        for (ch in raw) {
            if (ch.isDigit()) {
                cleaned.append(ch)
            } else if ((ch == '.' || ch == ',') && !seenSeparator && cleaned.isNotEmpty()) {
                cleaned.append('.')
                seenSeparator = true
            } else if (ch == '-' && cleaned.isEmpty()) {
                cleaned.append(ch)
            }
        }
        if (cleaned.none { it.isDigit() }) return null
        return cleaned.toString().toDoubleOrNull()
    }
}
