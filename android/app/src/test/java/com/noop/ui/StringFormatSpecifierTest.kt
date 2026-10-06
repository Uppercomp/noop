package com.noop.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Every locale's `strings.xml` must spell format specifiers the way **Java** does, not the way Apple's
 * String Catalog does.
 *
 * WHY THIS EXISTS. `Resources.getString(id, args)` formats through `String.format`, where `%@` and
 * `%lld` are not conversions at all — they throw `UnknownFormatConversionException`. The catalog's
 * English side often has no explicit `en` entry (English is its source language), so a conversion that
 * fell back to the catalog KEY wrote `%@`/`%lld` straight into `values/strings.xml`: every English call
 * site that passed an argument would have crashed, while all eight translations were correct and the
 * whole suite stayed green. Android's own `%1$s`/`%1$d` are the only valid spellings here.
 *
 * `%HRmax` is NOT a specifier and must survive: the check names the Apple forms explicitly rather than
 * looking for a bare per-cent sign.
 */
class StringFormatSpecifierTest {

    private val appleSpecifier = Regex("""%(?:\d+\$)?(?:lld|ld|@)""")

    private val localeFiles = listOf(
        "values", "values-de", "values-es", "values-fr", "values-it",
        "values-pl", "values-pt-rPT", "values-ru", "values-zh",
    )

    private fun resRoot(): File {
        var root = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (!File(root, "app/src/main/res/values/strings.xml").isFile) {
            root = root.parentFile ?: error("strings.xml not found from ${System.getProperty("user.dir")}")
        }
        return File(root, "app/src/main/res")
    }

    private fun offenders(text: String): List<String> =
        Regex("""<string name="([a-z0-9_]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .filter { appleSpecifier.containsMatchIn(it.groupValues[2]) }
            .map { "${it.groupValues[1]} = ${it.groupValues[2]}" }
            .toList()

    @Test
    fun noLocaleUsesAppleFormatSpecifiers() {
        val res = resRoot()
        val problems = mutableListOf<String>()
        for (locale in localeFiles) {
            val file = File(res, "$locale/strings.xml")
            if (!file.isFile) continue
            offenders(file.readText()).forEach { problems += "$locale: $it" }
        }
        assertTrue(
            "a `%@`/`%lld` here throws UnknownFormatConversionException the moment it is read WITH " +
                "arguments — write `%1\$s` / `%1\$d` instead:\n" + problems.joinToString("\n"),
            problems.isEmpty(),
        )
    }
}
