package com.noop.ui

import com.noop.R
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

class SleepDebtFormattingTest {

    /**
     * The English value of a resource, read from the shipped default resources. The captions live in a
     * resource now (they are catalog copy — de "Im Ziel" / "Unter Bedarf"), and a pure helper a JVM test
     * calls cannot resolve one itself, so the wording that must stay byte-identical to iOS SleepView is
     * asserted against `values/strings.xml`, the idiom the other copy tests in this package use.
     */
    private fun english(name: String): String {
        var root = File(System.getProperty("user.dir") ?: ".").canonicalFile
        while (!File(root, "app/src/main/res/values/strings.xml").isFile) {
            root = root.parentFile ?: error("strings.xml not found from ${System.getProperty("user.dir")}")
        }
        val xml = File(root, "app/src/main/res/values/strings.xml").readText()
        val match = Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL).find(xml)
            ?: error("no English value for $name")
        return match.groupValues[1].replace("\\'", "'")
    }

    @Test
    fun nightDetailUsesTenMinuteDebtBoundary() {
        assertEquals(DisplayText.Resource(R.string.l10n_sleep_formatting_on_target_412a8343), debtCaption(9.9))
        assertEquals(Palette.statusPositive, debtColor(9.9))

        assertEquals(DisplayText.Resource(R.string.l10n_sleep_formatting_below_need_85eb30c0), debtCaption(10.0))
        assertEquals(Palette.statusWarning, debtColor(10.0))
    }

    @Test
    fun importedDebtAboveBoundaryRemainsDebt() {
        assertEquals(DisplayText.Resource(R.string.l10n_sleep_formatting_below_need_85eb30c0), debtCaption(12.5))
        assertEquals(Palette.statusWarning, debtColor(12.5))
    }

    /** No value at all reads "vs need", and the three phrases keep iOS's exact wording. */
    @Test
    fun missingValueReadsVsNeedAndThePhrasesKeepIosWording() {
        assertEquals(DisplayText.Resource(R.string.l10n_sleep_formatting_vs_need_6d942bfe), debtCaption(null))
        assertEquals("vs need", english("l10n_sleep_formatting_vs_need_6d942bfe"))
        assertEquals("On target", english("l10n_sleep_formatting_on_target_412a8343"))
        assertEquals("Below need", english("l10n_sleep_formatting_below_need_85eb30c0"))
    }
}
