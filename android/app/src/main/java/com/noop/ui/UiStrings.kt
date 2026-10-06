package com.noop.ui

import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import com.noop.NoopApplication
import com.noop.R

/**
 * Resource lookup for presentation code that is not itself composable.
 *
 * Most call sites can use Compose's `stringResource`; several NOOP screens deliberately keep pure
 * formatting helpers and data-driven catalogs outside composition. Resolving through the process's
 * Application resources keeps those helpers locale-aware without threading an Activity through the
 * model or storing translated text as an identity/database key.
 */
internal fun uiString(@StringRes id: Int, vararg formatArgs: Any): String =
    NoopApplication.localizedString(id, *formatArgs)

/**
 * Quantity-aware twin of [uiString]. Android picks the right `<item quantity=...>` for [count] using the
 * LOCALE's own plural rules, which is the whole point: hand-rolling `if (n == 1) singular else plural` at
 * the call site bakes in English's TWO categories. That silently under-serves any language with more —
 * Polish has one/few/many/other ("1 noc / 2 noce / 5 nocy"), so no amount of special-casing 1 can spell
 * it correctly. Routed through the Application resources for the same reason as [uiString].
 */
internal fun uiPlural(@PluralsRes id: Int, count: Int, vararg formatArgs: Any): String =
    NoopApplication.localizedPlural(id, count, *formatArgs)

/**
 * The iOS twin's `Platform.deviceNounPhrase` — the noun a sentence uses for the machine it is running on.
 *
 * iOS composes it as `String(localized: "this \(deviceNoun)")`, which resolves through the catalog's
 * `"this %@"` entry ("this iPhone" on iOS, "this Mac" on macOS). Android is always a phone, so it uses the
 * catalog's PLATFORM-NEUTRAL entry instead — the same "this device" the project already ships — rather than
 * pasting an English noun into the sentence, which is what made the sentence's own English a non-key and
 * left the translation unreachable.
 */
internal fun deviceNounPhrase(): String = uiString(R.string.l10n_fused_record_screen_this_device_eef9f92e)

/**
 * The iOS twin's `Platform.deviceNoun` — the BARE noun, "iPhone" on iOS and "Mac" on macOS.
 *
 * The Trends-report blurb takes this one, not [deviceNounPhrase]: the Swift call site is
 * `"... Saved on your \(Platform.deviceNoun)."`, and the catalog's German for that sentence is
 * "Gespeichert auf deinem %1$s." — the sentence supplies its own "deinem", so the slot must be the bare
 * noun. Passing the phrase produced "Gespeichert auf deinem dieses Gerät."
 *
 * "Phone" rather than "Device" because that is what the rest of the Lab Book copy on this platform calls
 * it (de "Telefon", zh "手机"), and the catalog already carries the translations.
 */
internal fun deviceNoun(): String = uiString(R.string.l10n_steps_calibration_screen_phone_77064d52)
