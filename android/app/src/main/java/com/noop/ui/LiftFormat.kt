package com.noop.ui

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

// Number and unit formatting for the Lift Log. Kotlin twin of `Strand/Data/LiftFormat.swift`.
//
// WEIGHT IS ALWAYS STORED IN KILOGRAMS. Only display and typed input are converted, using the unit
// system the user already picked for the whole app — the Lift Log deliberately does not add a second
// weight-unit setting of its own, so a pounds user gets pounds here for free and never has two settings
// that can disagree.
//
// Conversion goes through ONE constant in BOTH directions ([UnitFormatter.POUNDS_PER_KILOGRAM]), so a
// typed value round-trips: enter 225 lb, store 102.05… kg, read it back and it renders 225 lb again.
// Using a more exact factor for input while displaying with the rounded one would drift the number on
// the way back and make the log look like it had edited itself.
//
// LIVES IN `ui` ON ANDROID, not beside the engine in `data`, because its two dependencies
// ([UnitSystem], [UnitFormatter]) are here. The engine itself is unit-agnostic — storage is kilograms
// and it never formats — so nothing in `data` needs this, and putting it there would invert the
// dependency for the sake of matching iOS's directory layout.
object LiftFormat {

    // MARK: - Weight

    /** Convert a typed weight in the user's display unit to the kilograms that get stored. */
    fun kilograms(fromDisplay: Double, system: UnitSystem): Double =
        if (system == UnitSystem.IMPERIAL) fromDisplay / UnitFormatter.POUNDS_PER_KILOGRAM else fromDisplay

    /**
     * Convert stored kilograms to the user's display unit, as a NUMBER (not a string) so a caller can
     * put it straight into an editable text field.
     */
    fun display(fromKilograms: Double, system: UnitSystem): Double =
        if (system == UnitSystem.IMPERIAL) fromKilograms * UnitFormatter.POUNDS_PER_KILOGRAM else fromKilograms

    /** A stored weight rendered for display with its unit: "60 kg", "132.5 lb". */
    fun weight(kg: Double?, system: UnitSystem): String =
        if (kg == null) "—" else "${trim(display(fromKilograms = kg, system = system))} ${weightUnit(system)}"

    /** The bare unit label for a field suffix. */
    fun weightUnit(system: UnitSystem): String = UnitFormatter.massUnit(system)

    // MARK: - Numbers

    /**
     * A number with up to TWO decimals and no trailing noise: 8.0 → "8", 7.5 → "7.5", 12.25 → "12.25",
     * 45.50 → "45.5".
     *
     * Weights and RPE are entered as decimals but are usually whole, and "8.0 x 10" in a summary line
     * reads like a precision the user did not type — so a whole number loses its ".0".
     *
     * TWO decimals, not one. Gym plates come in quarter-kilos and microplates in smaller steps, so
     * 12.25 kg is a real weight a user types; formatting to one decimal turned it into 12.3 and, because
     * the entry field reads its text back through this function, that rounded value then replaced what
     * they typed. One decimal was silently lossy, not merely terse.
     *
     * ALWAYS renders "." regardless of locale. `String.format` with an implicit locale would emit "7,5"
     * across most of NOOP's shipped locales, and since an entry field reads its text back through here,
     * that comma would then be re-parsed by [number] — which happens to accept it, so the bug would not
     * even surface as a parse failure. `Locale.US` pins the separator the rest of the Lift Log displays.
     */
    fun trim(value: Double): String {
        val rounded = (value * 100).roundToLong() / 100.0
        if (rounded == rounded.roundToLong().toDouble() && abs(rounded) < 1e9) {
            return rounded.roundToLong().toString()
        }
        var text = String.format(Locale.US, "%.2f", rounded)
        while (text.endsWith("0")) text = text.dropLast(1)
        if (text.endsWith(".")) text = text.dropLast(1)
        return text
    }

    /**
     * Parse a typed number, accepting both "7.5" and the comma decimal separator "7,5" that most of
     * NOOP's shipped locales use on their keyboards. Returns null for anything else.
     */
    fun number(text: String): Double? {
        val cleaned = text.trim().replace(',', '.')
        if (cleaned.isEmpty()) return null
        return cleaned.toDoubleOrNull()
    }

    // MARK: - Durations

    /**
     * A rest period as "2:00" / "45s" — minutes and seconds, which is how rest is spoken about: a
     * program line's rest, a finished set's measured rest. A clock that is RUNNING (the bar, the
     * session sheet) is written by the live-clock helper instead.
     */
    fun duration(seconds: Int): String =
        if (seconds < 60) "${seconds}s" else String.format(Locale.US, "%d:%02d", seconds / 60, seconds % 60)
}
