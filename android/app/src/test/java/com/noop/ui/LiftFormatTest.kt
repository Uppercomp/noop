package com.noop.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Locale

/**
 * Pins [LiftFormat], the Kotlin twin of `Strand/Data/LiftFormat.swift`.
 *
 * Weight is stored in kilograms and only converted for display and typed input, so the load-bearing
 * property is the ROUND TRIP: what a user types must render back as the same number, in both unit
 * systems. That is what keeps a pounds user's log from looking as though it edited itself.
 *
 * The locale case is not hypothetical. `String.format` without an explicit locale emits the platform
 * separator, and most of NOOP's shipped locales write "7,5" — so a weight field that read its text back
 * through the formatter would show a comma. [LiftFormat.number] happens to ACCEPT a comma, so that bug
 * would not even surface as a parse failure; it would surface as the app quietly reformatting the user's
 * typing. Hence a test that runs under a comma-decimal default locale.
 */
class LiftFormatTest {

    // MARK: - trim

    @Test
    fun `a whole number loses its point zero`() {
        assertEquals("8", LiftFormat.trim(8.0))
        assertEquals("60", LiftFormat.trim(60.0))
    }

    @Test
    fun `up to two decimals survive, with no trailing noise`() {
        assertEquals("7.5", LiftFormat.trim(7.5))
        assertEquals("12.25", LiftFormat.trim(12.25))
        // 45.50 must not render as "45.5" with the zero kept, nor as "45.50"
        assertEquals("45.5", LiftFormat.trim(45.5))
    }

    /**
     * Two decimals rather than one, because gym plates come in quarter-kilos: rounding 12.25 to 12.3
     * would replace a weight the user really typed, and the entry field reads its text back through
     * this function.
     */
    @Test
    fun `a quarter-kilogram weight is not rounded away`() {
        assertEquals("12.25", LiftFormat.trim(12.25))
    }

    @Test
    fun `trim renders a dot even under a comma-decimal default locale`() {
        val original = Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            assertEquals("7.5", LiftFormat.trim(7.5))
            assertEquals("12.25", LiftFormat.trim(12.25))
            assertEquals("2:05", LiftFormat.duration(125))
        } finally {
            Locale.setDefault(original)
        }
    }

    // MARK: - number

    @Test
    fun `a typed number accepts both decimal separators, and surrounding space`() {
        assertEquals(7.5, LiftFormat.number("7.5")!!, 0.0)
        assertEquals(7.5, LiftFormat.number("7,5")!!, 0.0)
        assertEquals(7.5, LiftFormat.number("  7,5 ")!!, 0.0)
        assertEquals(225.0, LiftFormat.number("225")!!, 0.0)
    }

    @Test
    fun `number rejects what is not a number`() {
        assertNull(LiftFormat.number(""))
        assertNull(LiftFormat.number("   "))
        assertNull(LiftFormat.number("abc"))
        assertNull(LiftFormat.number("7.5.2"))
    }

    // MARK: - Weight round trip

    /**
     * The property the whole conversion exists to protect: a typed value survives storage and comes
     * back as itself, in either unit system, because both directions use the same constant.
     */
    @Test
    fun `a typed weight round-trips through storage in both unit systems`() {
        for (system in listOf(UnitSystem.METRIC, UnitSystem.IMPERIAL)) {
            for (typed in listOf(60.0, 100.0, 225.0, 12.25, 0.5)) {
                val stored = LiftFormat.kilograms(typed, system)
                assertEquals(
                    "typed $typed in $system must come back as itself",
                    typed,
                    LiftFormat.display(stored, system),
                    1e-9,
                )
            }
        }
    }

    @Test
    fun `a typed 225 pounds is stored as 102_06 kg and renders back as 225 lb`() {
        val stored = LiftFormat.kilograms(225.0, UnitSystem.IMPERIAL)
        // 225 / 2.20462 = 102.0584… — the KILOGRAM the log holds.
        assertEquals("102.06", LiftFormat.trim(stored))
        assertEquals("102.06 kg", LiftFormat.weight(stored, UnitSystem.METRIC))
        // …and the same row read back by the pounds user who typed it.
        assertEquals("225 lb", LiftFormat.weight(stored, UnitSystem.IMPERIAL))
    }

    @Test
    fun `weight renders the stored kilograms in metric and an em dash when unknown`() {
        assertEquals("60 kg", LiftFormat.weight(60.0, UnitSystem.METRIC))
        assertEquals("—", LiftFormat.weight(null, UnitSystem.METRIC))
        assertEquals("kg", LiftFormat.weightUnit(UnitSystem.METRIC))
        assertEquals("lb", LiftFormat.weightUnit(UnitSystem.IMPERIAL))
    }

    // MARK: - duration

    @Test
    fun `duration stays in seconds below a minute and switches to m,ss at it`() {
        assertEquals("45s", LiftFormat.duration(45))
        assertEquals("59s", LiftFormat.duration(59))
        assertEquals("1:00", LiftFormat.duration(60))
        assertEquals("2:00", LiftFormat.duration(120))
        assertEquals("2:05", LiftFormat.duration(125))
    }

    @Test
    fun `a zero-length rest reads as zero seconds, not as a blank clock`() {
        assertEquals("0s", LiftFormat.duration(0))
    }
}
