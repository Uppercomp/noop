package com.noop.ui

import com.noop.testcentre.TestModeRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins the per-mode glyphs on the Test Centre rows.
 *
 * The guard that matters is the FIRST test: `TestMode.icon` is a shared field ("SF Symbol on Apple; mapped
 * to a drawable id on Android") kept in step with the Swift twin by `TestModeRegistryParityTest`, so the id
 * set can grow without Android noticing. An id with no mapping renders NO glyph — it degrades silently, and
 * only visually, which is exactly the class of defect a compile cannot catch. iOS has the same failure mode
 * in reverse: an SF Symbol name that does not exist draws nothing.
 *
 * The second test is the copy-paste guard: nine modes with nine SF Symbols must not collapse onto fewer
 * Android glyphs.
 */
class TestModeIconTest {

    @Test
    fun everyRegistryIconIdResolvesToAGlyph() {
        val unmapped = TestModeRegistry.all.map { it.icon }.filter { testModeIcon(it) == null }
        assertEquals("these registry icon ids have no Android glyph", emptyList<String>(), unmapped)
    }

    @Test
    fun noTwoModesShareAGlyph() {
        val names = TestModeRegistry.all.map { testModeIcon(it.icon)?.name }
        assertEquals("every mode resolved", TestModeRegistry.all.size, names.filterNotNull().size)
        assertEquals("glyphs must be distinct", names.size, names.toSet().size)
    }

    @Test
    fun anUnknownIdRendersNothingRatherThanSomeoneElsesGlyph() {
        // A future mode's id must not borrow the previous branch's icon.
        assertNull(testModeIcon("ic_not_a_real_mode"))
        assertNull(testModeIcon(""))
        assertNotNull(testModeIcon("ic_bed"))
    }
}
