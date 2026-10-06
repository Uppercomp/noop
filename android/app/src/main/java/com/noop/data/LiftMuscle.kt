package com.noop.data

/**
 * The muscle vocabulary a logged set is classified under.
 *
 * Kotlin mirror of the macOS/iOS source of truth
 *   Packages/WhoopStore/Sources/WhoopStore/LiftMuscle.swift
 *
 * THE TOKENS ARE A STORED-DATA CONTRACT. `name` is what lands in the `primaryMuscle` column and in
 * the comma-joined `secondaryMuscles` column, and the same tokens cross the `.noopbak` boundary to
 * an Apple install. Never rename or remove one; adding is safe because [decodeList] skips what it
 * does not recognise.
 */
enum class LiftMuscle {
    // Push
    chest,
    frontDelts,
    sideDelts,
    rearDelts,
    triceps,

    // Pull
    lats,
    upperBack,
    traps,
    biceps,
    forearms,

    // Legs
    quads,
    hamstrings,
    glutes,
    adductors,
    abductors,
    calves,

    // Trunk
    abs,
    obliques,
    lowerBack,
    neck,
    ;

    /**
     * Coarse section, used only to group the picker. Not stored, not counted — purely presentation
     * scaffolding, so changing it is free.
     */
    enum class Region { push, pull, legs, trunk }

    val region: Region
        get() = when (this) {
            chest, frontDelts, sideDelts, rearDelts, triceps -> Region.push
            lats, upperBack, traps, biceps, forearms -> Region.pull
            quads, hamstrings, glutes, adductors, abductors, calves -> Region.legs
            abs, obliques, lowerBack, neck -> Region.trunk
        }

    companion object {
        /**
         * A set credits its PRIMARY muscle in full and each SECONDARY at a half.
         *
         * The 0.5 is not a house convention: the dose-response meta-regression the reference doses
         * come from compared 1.0, 0.5 and 0.0 for a secondary mover and found the evidence
         * strongest for fractional, which its primary models use. Change this and the reference
         * doses stop meaning what they claim.
         */
        const val directSetCredit: Double = 1.0
        const val indirectSetCredit: Double = 0.5

        /**
         * Cases in picker order: by region, in the order declared above.
         *
         * Swift's `LiftMuscle.ordered` is `allCases` and this is `entries`, so the two agree by
         * CONSTRUCTION: both are declaration order, and the declarations are kept in step (the token
         * order itself is pinned by `LiftMuscleParityOracleTest`).
         */
        val ordered: List<LiftMuscle> get() = entries

        /** Cases in one region, in declaration order. */
        fun inRegion(region: Region): List<LiftMuscle> = entries.filter { it.region == region }

        /**
         * Raw token to case, or null when the token is not one this build knows.
         *
         * Kotlin-only by design: Swift gets this free from `RawRepresentable.init(rawValue:)`, so
         * there is no Swift declaration to pair with rather than a missing one.
         */
        fun fromRaw(raw: String?): LiftMuscle? =
            raw?.let { token -> entries.firstOrNull { it.name == token } }

        /**
         * Resolve a muscle written by a PERSON in a spreadsheet — the twin of Swift's
         * `LiftMuscle.init?(sheetName:)`.
         *
         * Accepts the stored token (`frontDelts`) and the English display name ("Front delts"), and is
         * insensitive to case, spaces, hyphens and underscores — so "front delts", "Front-Delts" and
         * "FRONTDELTS" all land on the same case. Deliberately NOT fuzzy beyond that: the vocabulary is a
         * stored-data contract, and guessing that "shoulders" means front delts would silently put sets in
         * a bucket the user did not choose.
         */
        fun fromSheetName(raw: String?): LiftMuscle? {
            val token = raw ?: return null
            // Fold diacritics first (the same step `HeaderNorm.normalize` takes), then keep only letters
            // and digits: "Front delts" and "front-delts" both become "frontdelts".
            val key = java.text.Normalizer.normalize(token.lowercase(), java.text.Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .filter { it.isLetterOrDigit() }
            if (key.isEmpty()) return null
            entries.firstOrNull { it.name.lowercase() == key }?.let { return it }
            return sheetNames[key]
        }

        /**
         * The English display names, which is what the shipped template's dropdown offers. Written out
         * rather than derived from the app-layer `displayName` extension, because `data` must not depend on
         * `ui` — and because this list is a FILE-FORMAT contract, not a label.
         */
        private val sheetNames: Map<String, LiftMuscle> = buildMap {
            put("chest", LiftMuscle.chest)
            put("frontdelts", LiftMuscle.frontDelts)
            put("sidedelts", LiftMuscle.sideDelts)
            put("reardelts", LiftMuscle.rearDelts)
            put("triceps", LiftMuscle.triceps)
            put("lats", LiftMuscle.lats)
            put("upperback", LiftMuscle.upperBack)
            put("traps", LiftMuscle.traps)
            put("biceps", LiftMuscle.biceps)
            put("forearms", LiftMuscle.forearms)
            put("quads", LiftMuscle.quads)
            put("hamstrings", LiftMuscle.hamstrings)
            put("glutes", LiftMuscle.glutes)
            put("adductors", LiftMuscle.adductors)
            put("abductors", LiftMuscle.abductors)
            put("calves", LiftMuscle.calves)
            put("abs", LiftMuscle.abs)
            put("obliques", LiftMuscle.obliques)
            put("lowerback", LiftMuscle.lowerBack)
            put("neck", LiftMuscle.neck)
        }

        /**
         * Encode a secondary list for storage. Returns null for an empty list so the column stays
         * NULL rather than holding an empty string: two spellings of "none" is a bug waiting.
         * Duplicates and the primary are stripped so one set can never be counted twice for one
         * muscle, and the user's ordering is preserved.
         *
         * The Swift twin is `LiftMuscle.encodeList`.
         */
        fun encodeList(muscles: List<LiftMuscle>, primary: LiftMuscle? = null): String? {
            val seen = LinkedHashSet<LiftMuscle>()
            if (primary != null) seen.add(primary)
            val kept = ArrayList<LiftMuscle>()
            for (m in muscles) if (seen.add(m)) kept.add(m)
            return if (kept.isEmpty()) null else kept.joinToString(",") { it.name }
        }

        /**
         * Decode a stored secondary list. Unknown tokens are skipped rather than failing the read:
         * a database written by a newer build must stay readable by an older one.
         *
         * The Swift twin is `LiftMuscle.decodeList`.
         */
        fun decodeList(stored: String?): List<LiftMuscle> {
            if (stored.isNullOrEmpty()) return emptyList()
            return stored.split(",").mapNotNull { fromRaw(it) }
        }
    }
}
