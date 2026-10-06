package com.noop.ui

import com.noop.R
import com.noop.data.LiftMuscle

// Display names for the stored `LiftMuscle` vocabulary. Kotlin twin of `Strand/Data/LiftMuscleNames.swift`.
//
// `com.noop.data` deliberately holds no UI strings: `LiftMuscle`'s tokens are a stored-data contract
// (they land in the `primaryMuscle` / `secondaryMuscles` columns and cross the `.noopbak` boundary to an
// Apple install), so the human-readable name has to live at the app layer, where it can be reworded
// without touching what is written to the database. Renaming a label here is free; renaming a token is
// not — see the header of `LiftMuscle`.
//
// Names are the ones lifters use, not the anatomical Latin: "Lats", not "latissimus dorsi". The
// vocabulary is meant to be picked from in a gym in a few seconds, and the granularity is already
// justified in the enum — the label's only job is to be recognised instantly.

/** The name a user recognises, e.g. "Front delts". */
val LiftMuscle.displayName: String
    get() = when (this) {
        // Push
        LiftMuscle.chest -> uiString(R.string.l10n_lift_chest_d8bfbca8)
        LiftMuscle.frontDelts -> uiString(R.string.l10n_lift_front_delts_d0220a4f)
        LiftMuscle.sideDelts -> uiString(R.string.l10n_lift_side_delts_bf074856)
        LiftMuscle.rearDelts -> uiString(R.string.l10n_lift_rear_delts_eefa4814)
        LiftMuscle.triceps -> uiString(R.string.l10n_lift_triceps_e150a8e4)
        // Pull
        LiftMuscle.lats -> uiString(R.string.l10n_lift_lats_50e05648)
        LiftMuscle.upperBack -> uiString(R.string.l10n_lift_upper_back_8bb5e7bb)
        LiftMuscle.traps -> uiString(R.string.l10n_lift_traps_d711a748)
        LiftMuscle.biceps -> uiString(R.string.l10n_lift_biceps_f8203e2a)
        LiftMuscle.forearms -> uiString(R.string.l10n_lift_forearms_78ca0727)
        // Legs
        LiftMuscle.quads -> uiString(R.string.l10n_lift_quads_0d66d7d6)
        LiftMuscle.hamstrings -> uiString(R.string.l10n_lift_hamstrings_9067970b)
        LiftMuscle.glutes -> uiString(R.string.l10n_lift_glutes_9e1fa8dc)
        LiftMuscle.adductors -> uiString(R.string.l10n_lift_adductors_eb8167ad)
        LiftMuscle.abductors -> uiString(R.string.l10n_lift_abductors_dcc2a765)
        LiftMuscle.calves -> uiString(R.string.l10n_lift_calves_7ffa7d33)
        // Trunk
        LiftMuscle.abs -> uiString(R.string.l10n_lift_abs_fafb344d)
        LiftMuscle.obliques -> uiString(R.string.l10n_lift_obliques_5d68b519)
        LiftMuscle.lowerBack -> uiString(R.string.l10n_lift_lower_back_3bca70ef)
        LiftMuscle.neck -> uiString(R.string.l10n_lift_neck_35a237da)
    }

/**
 * Section title for the muscle picker. Presentation-only, like the region itself.
 *
 * "Push muscles", not "Push" — and for the same reason the rest timer says "Rest period" rather than
 * "Rest". A bare "Push" is the TODAY screen's readiness nudge ("push yourself"), and a generic English
 * word is never safe to reuse as a label for a different feature: the two would translate as one word
 * in some languages and read as nonsense in this picker.
 */
val LiftMuscle.Region.displayName: String
    get() = when (this) {
        LiftMuscle.Region.push -> uiString(R.string.l10n_lift_push_muscles_481b3413)
        LiftMuscle.Region.pull -> uiString(R.string.l10n_lift_pull_muscles_e5d7a5ec)
        LiftMuscle.Region.legs -> uiString(R.string.l10n_lift_leg_muscles_6683570a)
        LiftMuscle.Region.trunk -> uiString(R.string.l10n_lift_trunk_muscles_6cee9507)
    }

// MARK: - Summarising a classification

object LiftMuscleSummary {

    /**
     * One line describing how an exercise is classified, for a program row or a picker subtitle:
     * "Chest · Front delts, Triceps", or "Not classified" when it has no primary.
     *
     * The primary is listed first and separated from the secondaries, because the direct/indirect split
     * is what makes the per-muscle counts computable at all — collapsing them into one list would hide
     * the distinction the whole rollup rests on.
     */
    fun line(primary: LiftMuscle?, secondaries: List<LiftMuscle>): String {
        if (primary == null) return uiString(R.string.l10n_lift_not_classified_ae925ff0)
        val secondary = secondaries.filter { it != primary }
        if (secondary.isEmpty()) return primary.displayName
        return "${primary.displayName} · ${secondary.joinToString(", ") { it.displayName }}"
    }
}
