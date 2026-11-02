# Android 11.10 metric availability audit

This fork release makes the Android presentation catalog consistent across Today, Explore,
Compare and Deep Timeline. The existing theme, default selections and saved ordering remain.

## Finding metrics

- Today: edit **Key Metrics** or **Your cards**, search the available metrics, and enable/reorder them.
- Explore: search its metric selector, then choose **Add to Today** to pin to either section.
- Compare: search the same catalog; up to four metrics can be selected. The picker remains usable at the limit.
- Deep Timeline: **Browse all metrics** separates daily summaries from signals with actual sample timestamps.

All 36 normal catalog metrics have exactly one Key Metrics option and one Your cards option.
Raw research channels and diagnostic counters are not promoted into physiological metrics.

## Consistency and confidence

- Legacy `energy_kcal`, `spo2_candidate` and `steps_est` selections resolve to `total_kcal`, `spo2`
  and `steps`. They no longer create duplicate picker entries; stored data is not rewritten.
- Total burn uses the same NOOP total estimate as energy balance. Active energy is a separate
  Apple Health export quantity and never borrows total burn. Archived Health Connect burn rows
  remain excluded by the existing intake-only policy.
- Energy balance is logged intake minus estimated total burn. Negative is deficit; positive is surplus.
  Today is explicitly labelled a partial-day estimate. Missing food logs remain missing, rather than
  borrowing yesterday's intake or assuming zero.
- Weight, body composition and periodic fitness estimates show their latest available reading on or
  before the selected day, with its actual date. A future measurement never fills an earlier day.
  Nutrition CSV weigh-ins are a fallback when no imported health measurement exists for that date.
- Measured/imported SpO₂ wins per day. The existing experimental candidate can fill missing days only
  while its setting is enabled. The common oxygen entry warns that it may use an unvalidated estimate.
  Raw optical ratio is labelled as a ratio, not oxygen percent; the dense experimental candidate has
  its own warning and actual timestamps. These changes do not validate either oxygen method.
- Daily timeline summaries have no hour axis or intraday zoom. One reading draws a dot, not an invented
  continuous waveform. Raw signals retain their real timeline. Missing data remains an empty state.
- Reported/imported steps take priority, with the existing calibrated estimate as fallback. The rolling
  30-day average reuses the existing helper; absent days are not invented as zero.
- Stress index uses the existing daily StressModel with its full-history baseline. It is distinct from
  the live daytime stress signal. Mass, temperature/deviation and effort scales respect existing preferences.
- Metric-series and health-import invalidations refresh these screens even if no daily strap row changes.
  Food-only Health Connect dates now count in the source badge.

## Health Connect nutrition

The Nutrition permission also reads optional protein, total carbohydrate and total fat. Missing fields
stay absent; an explicit zero stays zero. Existing source aggregation is preserved: sum within an origin,
then take the maximum across origins per day. A successfully read 30-day nutrition window reconciles
only its own source/key rows; failed or partial reads preserve existing data. CSV remains the fallback
when that day's Health Connect quantity is absent. No calorie-burn permission is added.

Provider field reference: [Android NutritionRecord](https://developer.android.com/reference/androidx/health/connect/client/records/NutritionRecord).

## Shared catalog

| Key | Label | Group | Base unit |
| --- | --- | --- | --- |
| `energy_balance` | Energy balance | Nutrition | kcal |
| `total_kcal` | Total calories burned | Nutrition | kcal |
| `avg_hr` | Average Heart Rate | Heart | bpm |
| `max_hr` | Max Heart Rate | Heart | bpm |
| `vo2max` | VO₂ max (imported) | Heart | mL/kg/min |
| `fitness_age` | Fitness Age | Heart | yrs |
| `vo2max_est` | VO₂ Max (estimated) | Heart | mL/kg/min |
| `vitality` | Vitality | Heart | count/index |
| `body_age` | Body Age | Heart | yrs |
| `recovery` | Charge | Charge | % |
| `hrv` | Heart Rate Variability | Charge | ms |
| `rhr` | Resting Heart Rate | Charge | bpm |
| `resp_rate` | Respiratory Rate | Charge | rpm |
| `spo2` | Blood oxygen · SpO₂ | Charge | % |
| `skin_temp` | Skin Temperature | Charge | °C |
| `sleep_performance` | Rest | Rest | % |
| `sleep_total_min` | Asleep Time | Rest | min |
| `sleep_efficiency` | Sleep Efficiency | Rest | % |
| `sleep_deep_min` | Deep (SWS) Sleep | Rest | min |
| `sleep_rem_min` | REM Sleep | Rest | min |
| `sleep_light_min` | Light Sleep | Rest | min |
| `steps_average_30` | 30-day step average | Effort | steps |
| `strain` | Effort | Effort | /100 |
| `steps` | Steps | Effort | count/index |
| `active_kcal` | Active energy (Apple Health export) | Effort | kcal |
| `weight` | Weight | Health | kg |
| `body_fat` | Body Fat | Health | % |
| `lean_mass` | Lean Body Mass | Health | kg |
| `bmi` | BMI | Health | count/index |
| `calories_in` | Calories In | Nutrition | kcal |
| `protein_g` | Protein | Nutrition | g |
| `carbs_g` | Carbs | Nutrition | g |
| `fat_g` | Fat | Nutrition | g |
| `hydration` | Hydration | Health | ml |
| `stress` | Stress index | Health | /3 |
| `mood` | Mood | Mind | /5 |

## Scope and verification

These are Android UI, repository observation and Android Health Connect provider changes. Existing
physiological formulas and stored schema remain unchanged; no migration or backup whitelist changes
are required. Swift app UI has not been ported or compiled in this Windows-only pass. The marketing
version is aligned in project.yml. New presentation text is supplied in English and German; other
locales currently use the English fallback for these additions.

The signed staging APK builds with release lint, and 245 selected JVM tests pass with zero failures,
errors or skips. Coverage includes catalog/pin completeness, aliases, saved layouts, shared energy
sources, absent food, oxygen precedence/gating, optional nutrition fields, source counts, Room
invalidation, snapshots/dates, units and Coach behavior. This is a selected suite, not a claim that
the entire inherited test suite is green. The doc-comment lint still reports 12 inherited sites in
untouched files; the modified files introduce none.

An Android 16 emulator passed an in-place upgrade, preserving existing pins. UI checks covered
searching/pinning intake and balance to Key Metrics and Your cards, consistent values across views,
partial-day captions, snapshot dates, Compare oxygen/nutrition search and opening its picker at the
four-metric limit. Restart retained the added card. Deep Timeline displayed the same daily oxygen
value with a visible single-point dot, no hour axis, and separate raw/experimental entries.

Health fixture files and screenshots are private and excluded from the repository and release assets.
No live strap sync or fresh third-party Health Connect provider transfer is claimed for this UI release.
