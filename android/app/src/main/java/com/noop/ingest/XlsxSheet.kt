package com.noop.ingest

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element
import org.w3c.dom.Node

// A deliberately small `.xlsx` reader: enough to read a filled-in template, and nothing else.
//
// An .xlsx is a ZIP of XML. Reading one properly â€” styles, number formats, dates, formulas, streaming â€” is
// a library's worth of work. This reads each worksheet as text, in tab order, which is all a program sheet
// needs, and is honest about that: no formula evaluation (a cell's last cached value is used, which is what
// Excel wrote), no date coercion, no styling.
//
// Kotlin twin of `Packages/StrandImport/Sources/StrandImport/XlsxSheet.swift`. Android needs no XML
// dependency for this: `java.util.zip` and `javax.xml.parsers` are both in the platform and on the JVM, so
// the reader is unit-testable against the shipped template with nothing mocked.
internal object XlsxSheet {

    /**
     * One worksheet, reduced to what the caller needs to recognise and read it.
     *
     * Headers are kept SEPARATE from rows so an empty sheet is still identifiable. A downloaded template
     * that nobody has filled in has the right columns and no data, and that has to be reported as "no
     * exercises yet" rather than "wrong file" â€” the user is holding the right file.
     */
    data class Sheet(val headerKeys: Set<String>, val rows: List<Map<String, String>>)

    /**
     * Ceiling on ONE decompressed part.
     *
     * Measured on the expanded stream, not the archive, because that is the only bound that means
     * anything: an .xlsx is a ZIP, and a zip bomb is by definition tiny compressed and enormous expanded,
     * so a limit on the file's own size is trivially defeated. The backup restore path guards itself the
     * same way and for the same reason. 64 MB is orders of magnitude above any real worksheet and still
     * bounded.
     */
    const val maxPartBytes: Long = 64L * 1024 * 1024

    /** Every worksheet, in workbook (tab) order. */
    fun sheets(data: ByteArray): List<Sheet> = grids(data).map { headerKeyed(it) }

    /** One grid's header row applied to the rows beneath it. */
    private fun headerKeyed(grid: List<List<String>>): Sheet {
        val headerRow = grid.firstOrNull() ?: return Sheet(emptySet(), emptyList())
        val keys = headerRow.map { HeaderNorm.normalize(it) }
        val out = ArrayList<Map<String, String>>()
        for (cells in grid.drop(1)) {
            // Blank rows are ordinary in a spreadsheet â€” a user leaves space at the bottom.
            if (cells.all { it.trim().isEmpty() }) continue
            val dict = HashMap<String, String>(keys.size)
            keys.forEachIndexed { i, key ->
                if (key.isEmpty()) return@forEachIndexed
                val v = cells.getOrElse(i) { "" }
                val existing = dict[key]
                if (existing == null || existing.isEmpty()) dict[key] = v
            }
            out.add(dict)
        }
        return Sheet(keys.filter { it.isNotEmpty() }.toSet(), out)
    }

    /**
     * Every worksheet as a grid of strings, in workbook (tab) order.
     *
     * Every sheet rather than the first, because the caller is looking for a particular KIND of sheet and
     * only it can recognise one. A user who reorders tabs, keeps the instructions page in front, or pastes
     * the data into their own workbook still gets an import; picking by position would refuse all three.
     */
    fun grids(data: ByteArray): List<List<List<String>>> {
        val parts = readEntries(data)
        // Shared strings are optional: a sheet written with inline strings has no such part.
        val shared = parts["xl/sharedStrings.xml"]?.let { sharedStrings(it) } ?: emptyList()
        val paths = worksheetPaths(parts)
        if (paths.isEmpty()) throw LiftProgramSheetImporter.ImportError.Unreadable
        return paths.mapNotNull { path -> parts[path]?.let { sheetGrid(it, shared) } }
    }

    /**
     * Every entry we might need, by path. One pass over the archive, since a ZIP stream is forward-only.
     *
     * Anything not a part this reader can use is skipped WITHOUT being decompressed, so a workbook full of
     * images costs nothing to ignore.
     */
    private fun readEntries(data: ByteArray): Map<String, ByteArray> {
        val wanted = { path: String ->
            path == "xl/sharedStrings.xml" ||
                path == "xl/workbook.xml" ||
                path == "xl/_rels/workbook.xml.rels" ||
                (path.startsWith("xl/worksheets/") && path.endsWith(".xml"))
        }
        val out = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(data)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || !wanted(entry.name)) continue
                out[entry.name] = readBounded(zip)
            }
        }
        return out
    }

    /** Read one entry, refusing anything that expands past [maxPartBytes]. */
    private fun readBounded(zip: ZipInputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val n = zip.read(buffer)
            if (n < 0) break
            if (out.size() + n > maxPartBytes) throw LiftProgramSheetImporter.ImportError.TooLarge
            out.write(buffer, 0, n)
        }
        return out.toByteArray()
    }

    /**
     * Worksheet part paths in TAB order, resolved through the workbook.
     *
     * `xl/worksheets/sheet1.xml` is a stable id, not a position: a user who drags the instructions tab in
     * front of the data tab, or a writer that numbers parts differently, leaves `sheet1.xml` sitting behind
     * another sheet. So take `<sheet>` order from `xl/workbook.xml` â€” that IS tab order â€” and map each
     * `r:id` through `xl/_rels/workbook.xml.rels`.
     *
     * Falls back to every worksheet part sorted by name, for a file whose workbook part is missing or
     * unreadable. Order matters less there than not losing the data entirely.
     */
    private fun worksheetPaths(parts: Map<String, ByteArray>): List<String> {
        val workbook = parts["xl/workbook.xml"]
        val rels = parts["xl/_rels/workbook.xml.rels"]
        if (workbook != null && rels != null) {
            val ids = elements(workbook, "sheet").mapNotNull { attr(it, "r:id") ?: attr(it, "id") }
            val targets = elements(rels, "Relationship").associateNotNull { el ->
                val id = attr(el, "Id") ?: return@associateNotNull null
                val target = attr(el, "Target") ?: return@associateNotNull null
                id to target
            }
            val paths = ids.mapNotNull { targets[it] }.map { target ->
                // Targets are written relative to the workbook part, which lives in `xl/`.
                if (target.startsWith("/")) target.drop(1) else "xl/$target"
            }
            if (paths.isNotEmpty()) return paths
        }
        return parts.keys.filter { it.startsWith("xl/worksheets/") && it.endsWith(".xml") }.sorted()
    }

    /** `xl/sharedStrings.xml` â€” the string pool most cells point into. */
    private fun sharedStrings(data: ByteArray): List<String> =
        elements(data, "si").map { si ->
            // A rich-text run splits one logical string across several <t> elements; they concatenate.
            val runs = si.getElementsByTagName("t")
            buildString { for (i in 0 until runs.length) append(runs.item(i).textContent ?: "") }
        }

    /**
     * A worksheet part: rows of cells, placed by their `r` reference so gaps stay gaps.
     *
     * "C7" -> 2, zero-based. A skipped column stays an empty cell rather than shifting every value after it
     * one place left â€” which would silently move reps into the weight column.
     */
    private fun sheetGrid(data: ByteArray, shared: List<String>): List<List<String>> {
        val out = ArrayList<List<String>>()
        for (row in elements(data, "row")) {
            val cells = ArrayList<String>()
            for (c in elements(row, "c")) {
                // t="s" means the value is an index into the shared-string pool; anything else is the
                // literal text (inline strings arrive already resolved through <t>).
                //
                // BOTH are searched at ANY DEPTH, and every run concatenated: an inline string nests its
                // text one level down (`<c t="inlineStr"><is><t>Exercise</t></is></c>`), and rich text
                // splits one logical string across several <t> elements. Reading only a direct child —
                // which is what the shipped template needs to work at all — returns "" for every one of
                // its headers.
                val raw = descendantText(c, "v") ?: descendantText(c, "t") ?: ""
                val value = if (attr(c, "t") == "s") {
                    raw.toIntOrNull()?.takeIf { it in shared.indices }?.let { shared[it] } ?: ""
                } else {
                    raw
                }
                val column = columnIndex(attr(c, "r") ?: "")
                if (column >= 0) {
                    while (cells.size <= column) cells.add("")
                    cells[column] = value
                } else {
                    cells.add(value)
                }
            }
            out.add(cells)
        }
        return out
    }

    /** "C7" -> 2. Zero-based; -1 when the reference has no letters (so the cell appends). */
    internal fun columnIndex(ref: String): Int {
        var n = 0
        var any = false
        for (ch in ref.uppercase()) {
            if (ch in 'A'..'Z') {
                n = n * 26 + (ch - 'A' + 1)
                any = true
            } else if (!any) {
                continue
            } else {
                break
            }
        }
        return if (any) n - 1 else -1
    }

    // MARK: - Minimal DOM helpers
    //
    // DOM rather than a streaming parser: a program sheet is a few dozen rows, the whole part is read
    // anyway (it has to be, to bound the expansion), and `javax.xml.parsers` is on both Android and the JVM
    // â€” which is what lets the reader be exercised against the shipped template in a plain unit test.

    private fun elements(data: ByteArray, tag: String): List<Element> {
        val doc = parse(data)
        val list = doc.getElementsByTagName(tag)
        return (0 until list.length).mapNotNull { list.item(it) as? Element }
    }

    private fun elements(root: Element, tag: String): List<Element> {
        val list = root.getElementsByTagName(tag)
        return (0 until list.length).mapNotNull { list.item(it) as? Element }
    }

    private fun attr(el: Element, name: String): String? = el.getAttribute(name)?.takeIf { it.isNotEmpty() }

    /** The concatenated text of every [tag] element under [el], at any depth, or null when there is none. */
    private fun descendantText(el: Element, tag: String): String? {
        val nodes = el.getElementsByTagName(tag)
        if (nodes.length == 0) return null
        return buildString { for (i in 0 until nodes.length) append(nodes.item(i).textContent ?: "") }
    }

    private fun parse(data: ByteArray): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance()
        // Attributes are read literally (`r:id`, not the resolved namespace URI), matching the Swift twin's
        // `attributes["r:id"] ?? attributes["id"]`.
        factory.isNamespaceAware = false
        // A workbook is untrusted input from the user's file system: no external entities, no DTD fetch.
        for (feature in listOf(
            "http://apache.org/xml/features/nonvalidating/load-external-dtd",
            "http://xml.org/sax/features/external-general-entities",
            "http://xml.org/sax/features/external-parameter-entities",
        )) {
            runCatching { factory.setFeature(feature, false) }
        }
        return runCatching { factory.newDocumentBuilder().parse(ByteArrayInputStream(data)) }
            .getOrElse { throw LiftProgramSheetImporter.ImportError.Unreadable }
    }

    private inline fun <T, R> Iterable<T>.associateNotNull(transform: (T) -> Pair<String, R>?): Map<String, R> {
        val out = HashMap<String, R>()
        for (item in this) transform(item)?.let { out[it.first] = it.second }
        return out
    }
}
