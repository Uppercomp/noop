package com.noop.ui

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable

/**
 * A piece of copy a PURE helper hands to its caller: a string resource and its arguments, or a value
 * composed at runtime (a brand name, a joined list) the caller shows verbatim.
 *
 * WHY THIS TYPE EXISTS. iOS resolves copy anywhere with `String(localized:)`; Android resolves through
 * the Application (`NoopApplication.localizedString`), which a plain JVM unit test does not have. So a
 * helper that RETURNS A RESOLVED STRING becomes untestable the moment it is localised — it throws
 * `NoopApplication is not attached`. Returning the resource and its arguments keeps the decision pure
 * and testable AND keeps the words translated, which is what lets `daySourceBadge`, `relativeAgo` and
 * `smartAlarmWeekdaySummary` be pinned by tests at all.
 *
 * It also makes a comparison locale-proof: `base.first == DisplayText.Resource(R.string.today_source_on_device)`
 * cannot drift from the words, while `base.first == "On-device"` silently stops matching in German.
 *
 * PUBLIC, unlike the rest of this package's helpers, because a published read-model carries it: `FusedRow.label`
 * is a `DisplayText`, and a public data class cannot expose an internal type.
 */
sealed interface DisplayText {
    data class Resource(@StringRes val id: Int, val args: List<Any> = emptyList()) : DisplayText
    data class Dynamic(val value: String) : DisplayText

    /**
     * A symbol glued in front of another piece of copy — iOS's `"≈\(durationText(m))"`, where the "≈"
     * is punctuation and the duration is a translated format. Neither half can be resolved early (the helper
     * that builds it is pure), so the pair resolves together when the value is shown.
     */
    data class Prefixed(val prefix: String, val inner: DisplayText) : DisplayText
}

/**
 * Resolve for display, from a composable.
 *
 * An argument that is itself a [DisplayText] resolves first, so a composed sentence can nest one — iOS's
 * `String(localized: "\(n) days · \(range.name)")` puts an already-localised phrase in a `%@` slot, and
 * the Android twin has to do the same or the resource id ends up printed inside the sentence.
 */
@Composable
internal fun DisplayText.text(): String = when (this) {
    is DisplayText.Resource -> {
        // A loop, not `args.map { it.text() }`: the transform is @Composable and a `map` lambda cannot be.
        val resolved = mutableListOf<Any>()
        for (arg in args) resolved += if (arg is DisplayText) arg.text() else arg
        uiString(id, *resolved.toTypedArray())
    }
    is DisplayText.Dynamic -> value
    is DisplayText.Prefixed -> prefix + inner.text()
}

/**
 * Resolve OUTSIDE a composition — a notification, a stored log line, a string built by a plain
 * function. Same resolver as [text], so the two paths cannot word the same thing differently.
 */
internal fun DisplayText.plain(): String = when (this) {
    is DisplayText.Resource -> {
        val resolved = mutableListOf<Any>()
        for (arg in args) resolved += if (arg is DisplayText) arg.plain() else arg
        uiString(id, *resolved.toTypedArray())
    }
    is DisplayText.Dynamic -> value
    is DisplayText.Prefixed -> prefix + inner.plain()
}

/**
 * Several parts as one line — "Mon, Wed", "WHOOP, Apple Watch". A loop rather than `joinToString { … }`
 * because the transform must resolve a resource, and the compiler cannot mark a `joinToString` lambda
 * `@Composable`.
 */
@Composable
internal fun List<DisplayText>.joined(separator: String = ", "): String {
    val parts = mutableListOf<String>()
    for (part in this) parts += part.text()
    return parts.joinToString(separator)
}
