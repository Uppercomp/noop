package com.noop.ingest

import com.noop.data.LiftMuscle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pins [LiftProgramSheetImporter] and the small `.xlsx` reader under it.
 *
 * The strongest test here is the one against the SHIPPED template ([docs/lift-log-program-template.xlsx]):
 * it is the exact artefact the app tells a user to download, it is built by a script in this repository, and
 * it is what the Apple side reads. A reader that cannot open it is a reader that cannot open the only file
 * the feature advertises.
 *
 * The template also settled a real question the source could not: it writes its strings INLINE
 * (`t="inlineStr"` with the text nested inside `<is>`), not through `xl/sharedStrings.xml`, so a reader that
 * only looks for a direct `<t>` child finds nothing but empty headers.
 */
class LiftProgramSheetImporterTest {

    /** The repo's `docs/` directory, from Gradle, IDE or repo-root working directories. */
    private fun shippedTemplate(): File? {
        val userDir = File(System.getProperty("user.dir") ?: ".")
        return listOf(
            File(userDir, "../../docs/lift-log-program-template.xlsx"),
            File(userDir, "docs/lift-log-program-template.xlsx"),
            File(userDir, "../docs/lift-log-program-template.xlsx"),
        ).map { it.canonicalFile }.firstOrNull { it.isFile }
    }

    /** The header row a template or CSV offers, straight out of the sheets. */
    private fun headerRow(data: ByteArray): List<String> = XlsxSheet.sheets(data).first().headerKeys.sorted()

    // MARK: - The shipped template

    @Test
    fun `the shipped template opens and offers the columns the importer looks for`() {
        val file = shippedTemplate()
        assumeTrue("template not reachable from ${System.getProperty("user.dir")}", file != null)
        val data = file!!.readBytes()

        val sheets = XlsxSheet.sheets(data)
        assertTrue("a template with no sheets at all", sheets.isNotEmpty())

        // The keys the parser matches on, after normalisation. If a column is renamed in the generator, this
        // is where the two halves of the feature stop agreeing — on BOTH platforms.
        val keys = sheets.first().headerKeys
        for (expected in listOf(
            "program", "program_note", "exercise", "primary_muscle", "secondary_muscles",
            "sets", "reps", "weight_kg", "target_max_rpe", "rest_sec", "note",
        )) {
            assertTrue("the shipped template has no \"$expected\" column (it has ${keys.sorted()})", expected in keys)
        }
    }

    /**
     * A downloaded-but-unfilled template is the RIGHT file with nothing in it, which is a different mistake
     * from a wrong file — and the parser has to say so, because the advice differs.
     */
    @Test
    fun `an unfilled template reads as empty rather than as the wrong file`() {
        val file = shippedTemplate()
        assumeTrue("template not reachable", file != null)
        val error = runCatching { LiftProgramSheetImporter.parse(file!!.readBytes()) }.exceptionOrNull()
        assertTrue("expected Empty, got $error", error is LiftProgramSheetImporter.ImportError.Empty)
    }

    // MARK: - A minimal workbook the test builds

    /**
     * A workbook written by hand, with its sheets in the opposite order from their PART NAMES — the case
     * the workbook/rels resolution exists for. Sheet 2 is tab-first, and holds the data.
     */
    private fun workbook(): ByteArray {
        fun inline(ref: String, value: String) =
            """<c r="$ref" t="inlineStr"><is><t xml:space="preserve">$value</t></is></c>"""

        val sheet1 = """<?xml version="1.0"?><worksheet><sheetData>""" +
            """<row r="1">${inline("A1", "How to use")}</row>""" +
            """<row r="2">${inline("A2", "Fill in the other tab")}</row>""" +
            """</sheetData></worksheet>"""
        val sheet2 = """<?xml version="1.0"?><worksheet><sheetData>""" +
            """<row r="1">${inline("A1", "Program")}${inline("C1", "Exercise")}""" +
            """${inline("D1", "Primary muscle")}${inline("F1", "Sets")}${inline("G1", "Reps")}</row>""" +
            // A gap at B, and a shared string for the exercise name, so both resolution paths are exercised.
            """<row r="2"><c r="A2" t="s"><v>0</v></c><c r="C2" t="s"><v>1</v></c>""" +
            """${inline("D2", "Front delts")}<c r="F2"><v>4</v></c><c r="G2"><v>8</v></c></row>""" +
            """<row r="3"/>""" +
            """</sheetData></worksheet>"""

        fun write(out: ZipOutputStream, path: String, text: String) {
            out.putNextEntry(ZipEntry(path))
            out.write(text.toByteArray())
            out.closeEntry()
        }

        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out ->
            write(out, "xl/workbook.xml",
                """<?xml version="1.0"?><workbook><sheets>""" +
                    """<sheet name="How to use" sheetId="1" r:id="rId1"/>""" +
                    """<sheet name="Program" sheetId="2" r:id="rId2"/></sheets></workbook>""")
            write(out, "xl/_rels/workbook.xml.rels",
                """<?xml version="1.0"?><Relationships>""" +
                    """<Relationship Id="rId1" Target="worksheets/sheet1.xml"/>""" +
                    """<Relationship Id="rId2" Target="worksheets/sheet2.xml"/></Relationships>""")
            write(out, "xl/sharedStrings.xml",
                """<?xml version="1.0"?><sst><si><t>Upper A</t></si><si><r><t>Incline</t></r><r><t> press</t></r></si></sst>""")
            write(out, "xl/worksheets/sheet1.xml", sheet1)
            write(out, "xl/worksheets/sheet2.xml", sheet2)
        }
        return bytes.toByteArray()
    }

    @Test
    fun `worksheets are read in tab order, not part order`() {
        val grids = XlsxSheet.grids(workbook())
        assertEquals("the workbook's own sheet order decides", "How to use", grids[0][0][0])
        assertEquals("Program", grids[1][0][0])
    }

    @Test
    fun `a shared string, an inline string and a gap all land in the right column`() {
        val sheet = XlsxSheet.sheets(workbook())[1]
        val row = sheet.rows.single()
        assertEquals("Upper A", row["program"])
        assertEquals("Incline press", row["exercise"])
        assertEquals("a rich-text run concatenates into one value", "Front delts", row["primary_muscle"])
        assertEquals("4", row["sets"])
        assertEquals("8", row["reps"])
    }

    @Test
    fun `a blank row at the bottom of a sheet is not a row`() {
        assertEquals(1, XlsxSheet.sheets(workbook())[1].rows.size)
    }

    @Test
    fun `a filled workbook imports through the whole parser`() {
        val result = LiftProgramSheetImporter.parse(workbook())
        assertEquals(1, result.programs.size)
        val program = result.programs.single()
        assertEquals("Upper A", program.name)
        val line = program.lines.single()
        assertEquals("Incline press", line.exercise)
        assertEquals(LiftMuscle.frontDelts, line.primaryMuscle)
        assertEquals(4, line.targetSets)
        assertEquals(8, line.targetReps)
        assertTrue("a clean sheet warns about nothing", result.warnings.isEmpty())
    }

    // MARK: - CSV, the format every spreadsheet app can write

    private fun csv(vararg lines: String) = (lines.joinToString("\n")).toByteArray()

    @Test
    fun `csv lines group into programs in sheet order, with their targets`() {
        val result = LiftProgramSheetImporter.parse(
            csv(
                "Program,Program note,Exercise,Primary muscle,Secondary muscles,Sets,Reps,Weight kg,Target max RPE,Rest sec,Note",
                "Upper A,A good day,Bench press,Chest,\"Front delts, Triceps\",4,8,60,8,120,Elbows tucked",
                "Upper A,,Incline dumbbell press,chest,front delts,3,10,25,7,90,",
                "Lower A,,Squat,Quads,Glutes,5,5,100,9,180,Depth over ego",
            ),
        )
        assertEquals(listOf("Upper A", "Lower A"), result.programs.map { it.name })
        assertEquals("A good day", result.programs[0].note)

        val bench = result.programs[0].lines[0]
        assertEquals("Bench press", bench.exercise)
        assertEquals(LiftMuscle.chest, bench.primaryMuscle)
        assertEquals(listOf(LiftMuscle.frontDelts, LiftMuscle.triceps), bench.secondaryMuscles)
        assertEquals(4, bench.targetSets)
        assertEquals(8, bench.targetReps)
        assertEquals(60.0, bench.targetWeightKg!!, 0.0)
        assertEquals(8.0, bench.targetMaxRpe!!, 0.0)
        assertEquals(120, bench.restSec)
        assertEquals("Elbows tucked", bench.note)

        assertEquals("the second line of a program joins it rather than starting a new one", 2, result.programs[0].lines.size)
        assertNull(
            "an empty cell means not planned, not an empty note",
            result.programs[0].lines[1].note,
        )
        assertEquals("Depth over ego", result.programs[1].lines.single().note)
    }

    @Test
    fun `numbers are read as spreadsheets write them`() {
        assertEquals(60.0, LiftSpreadsheetNumber("60")!!, 0.0)
        assertEquals(60.5, LiftSpreadsheetNumber("60.5")!!, 0.0)
        assertEquals("a comma decimal is what most of Europe types", 60.5, LiftSpreadsheetNumber("60,5")!!, 0.0)
        assertEquals(60.0, LiftSpreadsheetNumber("60 kg")!!, 0.0)
        assertEquals(2.0, LiftSpreadsheetNumber("2 min")!!, 0.0)
        assertEquals(-5.0, LiftSpreadsheetNumber("-5")!!, 0.0)
        assertNull("a blank cell is not a planned zero", LiftSpreadsheetNumber(""))
        assertNull(LiftSpreadsheetNumber("as many as it takes"))
    }

    @Test
    fun `a row without an exercise is skipped and said so, and the rest still import`() {
        val result = LiftProgramSheetImporter.parse(
            csv(
                "Program,Exercise,Sets,Reps",
                "Upper A,Bench press,4,8",
                "Upper A,,4,8",
                "Upper A,Squat,5,5",
            ),
        )
        assertEquals(2, result.programs.single().lines.size)
        assertEquals(listOf("Row 3: no exercise name, so the row was skipped"), result.warnings)
    }

    @Test
    fun `a misspelled muscle warns about its row and leaves the line unclassified`() {
        val result = LiftProgramSheetImporter.parse(
            csv("Exercise,Primary muscle,Secondary muscles", "Bench press,shoulders,chest;wings"),
        )
        val line = result.programs.single().lines.single()
        assertNull("guessing that shoulders means front delts would put sets in a bucket nobody chose", line.primaryMuscle)
        assertEquals("and a token that is not a muscle is ignored", listOf(LiftMuscle.chest), line.secondaryMuscles)
        assertEquals(
            listOf(
                "Row 2: \"shoulders\" is not a muscle group, so \"Bench press\" was left unclassified",
                "Row 2: \"wings\" is not a muscle group and was ignored",
            ),
            result.warnings,
        )
    }

    @Test
    fun `the primary is never also a secondary`() {
        val result = LiftProgramSheetImporter.parse(
            csv("Exercise,Primary muscle,Secondary muscles", "Bench press,chest,\"chest, triceps, triceps\""),
        )
        assertEquals(listOf(LiftMuscle.triceps), result.programs.single().lines.single().secondaryMuscles)
    }

    @Test
    fun `an out-of-range max RPE is refused rather than clamped`() {
        val result = LiftProgramSheetImporter.parse(
            csv("Exercise,Target max RPE", "Bench press,12", "Squat,8"),
        )
        assertNull(result.programs.single().lines[0].targetMaxRpe)
        assertEquals(
            listOf("Row 2: max RPE 12 is not between 1 and 10, so \"Bench press\" has none"),
            result.warnings,
        )
        assertEquals(8.0, result.programs.single().lines[1].targetMaxRpe!!, 0.0)
    }

    @Test
    fun `a sheet without an exercise column is not a program sheet`() {
        val error = runCatching {
            LiftProgramSheetImporter.parse(csv("Day,Recovery,Strain", "2026-10-03,88,12.4"))
        }.exceptionOrNull()
        assertTrue("expected MissingColumns, got $error", error is LiftProgramSheetImporter.ImportError.MissingColumns)
    }

    @Test
    fun `a program sheet with no data in it reads as empty`() {
        val error = runCatching {
            LiftProgramSheetImporter.parse(csv("Program,Exercise,Sets,Reps"))
        }.exceptionOrNull()
        assertTrue("expected Empty, got $error", error is LiftProgramSheetImporter.ImportError.Empty)
    }

    @Test
    fun `headers are matched however the user spelled them`() {
        val result = LiftProgramSheetImporter.parse(
            csv("Day, Movement ,Working sets,Repetitions,Load,Rest (seconds)", "Upper A,Bench press,4,8,60,120"),
        )
        val line = result.programs.single().lines.single()
        assertEquals("Bench press", line.exercise)
        assertEquals(4, line.targetSets)
        assertEquals(8, line.targetReps)
        assertEquals(60.0, line.targetWeightKg!!, 0.0)
        assertEquals(120, line.restSec)
    }

    @Test
    fun `a note longer than the editor can hold is capped where the editor caps it`() {
        val long = "x".repeat(400)
        val result = LiftProgramSheetImporter.parse(csv("Exercise,Note", "Bench press,$long"))
        assertEquals(200, result.programs.single().lines.single().note!!.length)
    }
}

/** The importer's number reader, reached through the one public entry point that uses it. */
private fun LiftSpreadsheetNumber(raw: String): Double? =
    LiftProgramSheetImporter.doubleFrom(raw)
