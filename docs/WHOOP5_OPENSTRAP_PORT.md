# WHOOP 5.0 input mapping from OpenStrap

This change extends NOOP's existing decoder → local streams → SQLite → analytics architecture.
The Android screens, design tokens, navigation and the current UI work are preserved. The existing
analytics remain the authority for daily scores; importing a second app's formulas would change their
meaning without establishing accuracy against WHOOP.

## Reference revisions

The comparison uses these pinned, MIT-licensed sources:

- [OpenStrap Edge](https://github.com/OpenStrap/edge/tree/5b9b435f6b33f2c884f5da1657a461ded1989e07).
- [OpenStrap protocol](https://github.com/OpenStrap/protocol/blob/ecb512b710fbfa5fffce999f939a64aa7c486ee0/lib/src/gen5_records.dart),
  the protocol revision pinned by Edge's `pubspec.yaml`.
- [OpenStrap analytics](https://github.com/OpenStrap/analytics/tree/27b0ba486234900084ed791cfb95e71738645d98),
  also pinned by Edge.

The public timestamp-synthesized R22 reference fixtures are adapted under MIT; the complete notice
is in `licenses/OpenStrap-MIT.txt`. Packet geometry is independently implemented in Swift and Kotlin.
No WHOOP app code, firmware or assets are included.

## Input coverage

All offsets below are absolute within a complete WHOOP 5 frame, including its 8-byte envelope.

| Input | NOOP handling after this change | Interpretation |
|---|---|---|
| R18 HR, RR | Existing HR/RR streams; RR retains its source channel and ticks-to-ms conversion | Existing verified mapping; OpenStrap's direct RR-as-ms reading does not replace NOOP's tick conversion |
| R18 accelerometer mean and motion magnitude | Existing gravity stream with `dynAccel` | Existing mapping; the exact motion statistic remains unpinned |
| R18 skin temperature @73 and auxiliary channels @69/@71 | Existing main and auxiliary temperature storage | Existing family-specific /100 and /10 views, with sentinel/plausibility gates |
| R18 step/motion counter @57, cadence @59, activity @63 | Existing step stream and auxiliary cadence | Existing approximation; the new raw activity slot also preserves unknown/invalid codes |
| R18 band state @81 | Existing full flag byte plus interpreted envelope | Not a deep/light/REM classification; wear interpretations remain unvalidated |
| R18 oxygen/status byte @82 | Existing raw auxiliary slot and gated instrumentation candidate | Never promoted to a production SpO₂ percentage by this change |
| R18 optical @106–109, quality-like float @113 | Existing lossless auxiliary slots | No LED-colour, dB, or physiological calibration is asserted |
| R18 record flags @10 and subsecond word @19 | **New** appended auxiliary slots | Raw; proposed 25/50 Hz and Q15 interpretations remain candidates |
| R18 adjacent word @61 and byte @64 | **New** appended auxiliary slots | Raw, unnamed semantics |
| R18 ungated activity byte @63 | **New** appended auxiliary slot | Preserves the original code even when no named activity can be decoded |
| R20 deep optical buffers | Existing raw channel maps and raw-history archive | No red/IR assignment or SpO₂ calibration inferred |
| R21 / shape-matched 1244-byte IMU | Existing six-axis decoder, raw IMU storage and archive | Existing length/count gates and raw columns retained |
| R22 tagged research records | **New** six-layout research decoder; original frames remain archived | Instrumentation only, never scored physiology |
| R26 base, 24 deltas and burst word | Existing waveform stream; **fixed** Android read-side loss of base and burst fields | Stored base stays in its unsigned wire domain; an explicit signed i32 view is available |
| R26 timing, motion bits, state, flags, result and pad | **New** complete frame retention and CRC-gated metadata accessor | Raw; no sampling-rate or quality threshold is activated |
| Live HR/RR, battery, wear events, alarm, raw IMU and ECG capture | Existing family-aware protocol / collectors | No new commands or connection-state changes |

The R18 auxiliary codec remains format 2. Five slots are appended at bitmap positions 17–21;
positions 0–16 keep their original widths and meaning. Old rows return null for the added slots,
and old readers can still decode the original prefix of new rows.

R26 uses a nullable `rawFrame` BLOB appended to `ppgWaveformSample`: Room 41→42 and GRDB
`v48-ppg-raw-frame`. The original header, body, currently unmapped bytes and checksums survive a
successful offload. Existing waveform rows retain their deltas/base/burst and have null evidence;
discarded historical bytes cannot be reconstructed. First-seen deduplication and the existing
604,800-row retention limit remain in force. At that cap, 88-byte frames add about 51 MiB of raw
payload, before SQLite overhead. Full `.noopbak` SQLite backups retain this column on both platforms.

## R22 tag mapping

Only an intact 188-byte type-47/version-22 frame is accepted. Dispatch uses the emitted body tag @21,
including fallback tags; it does not depend on the band's configured research variant.

| Tag | Candidate raw fields |
|---|---|
| 1 | One i32 base plus 49 i16 delta slots; metadata block |
| 2, 4 | Same window and metadata; located but uninterpreted extension bytes |
| 3 | Two i32-base/24-delta windows; metadata shifted by two bytes |
| 5 | Embedded PIP timestamp and 24-delta window; embedded motion/status/flags only |
| 6 | Three 25-sample i16 acceleration columns and the raw tail |
| Unknown | Header, tag and complete raw body; no guessed channel fields |

Tag 5 does not read the other tags' metadata locations: those bytes can belong to the previous
packet. Float-shaped metadata is retained as unsigned 32-bit patterns, including non-finite patterns.
Optical deltas remain raw; saturation rails and stale tail slots do not become fabricated pulse data.
R22 contributes no daily stream rows, so the existing archive-before-ack path still preserves it.
The decoder does not enable research packets or send a new strap command.

## Feature equivalents already present

The reference app's main non-AI daily features already have local NOOP implementations. Keeping these
consumers avoids changing the meanings of values that existing screens and history use.

| Reference feature family | NOOP implementation |
|---|---|
| HRV, resting HR, nightly respiratory estimate | `HrvAnalyzer.kt`, `HrvFreqDomain.kt`, `AnalyticsEngine.kt`, `SleepStager.kt` |
| Recovery/readiness, strain, stress | `RecoveryScorer.kt`, `ReadinessEngine.kt`, `StrainScorer.kt`, `StressIndex.kt` |
| Sleep, naps, debt and regularity | `SleepStager.kt`, `SleepStagerV2.kt`, `NapDetector.kt`, `SleepDebt.kt`, `CircadianEngine.kt` |
| Activity/steps and training load | `StepsEstimateEngine.kt`, `StepsCounter.kt`, `TrainingLoadEngine.kt` |
| Automatic/live workouts, GPS and zones | `AutoWorkoutDetector.kt`, existing live workout and `location/GpsSession.kt` paths |
| Fitness-age / VO₂ estimate | `FitnessAgeEngine.kt` and `IntelligenceEngine.kt` |
| Breathing coherence | `ResonanceEngine.kt` and the existing Breathe flow |
| Trends, journal insights, cycle tracking and local coaching | Existing Intelligence/Insights/Journal/Cycle flows and `CyclePhaseEngine.kt` |
| Local exports, Health Connect, background sync, widgets and smart alarm | Existing backup, ingest, BLE service, widget and alarm packages |

This is feature-family coverage, not numeric equivalence with OpenStrap or WHOOP. Edge's supporting
analytics library also contains methods that are not all enabled in its app. Clinical apnea/arrhythmia
alerts, cloud/social/account services and the AI assistant are outside this port. NOOP's existing scope
rules continue to apply. A WHOOP subscription is not required to collect these inputs.

## Validation and remaining limits

The new research map uses a shared fixture covering public captures for tags 1, 2, 3, 5 and 6, a
poison-filled tag 4 and an unknown tag. Expected results are the verbatim stdout of independently
compiled, optimized Swift helpers, including all 256 auxiliary flag values and a real public R26
metadata fixture. Both platforms read the same expected bytes. Reproduce it with:

```sh
python3 Tools/gen_whoop5_research_oracle.py > /tmp/whoop5_research_oracle.json
```

Compare the output with `whoop5_research_oracle.json` under both protocol test resource directories
and the WhoopStore test resources before updating any fixture. This proves implementation parity;
it does not validate the upstream physiological interpretations.

Tests cover CRC/size rejection, tag-specific field absence, archive retention, legacy auxiliary
blobs, signed-versus-wire base views, waveform write/read round trips, migration preservation and
Room/GRDB schema parity. Tests for unrelated existing UI edits are reported separately.

Device captures so far contain R18 and R26, not R20/R21/R22. New research interpretations therefore
remain candidates until independently observed on more hardware. A stable oxygen byte or one matching
night does not validate SpO₂. Reference measurements across changing inputs are still needed before a
new physiological estimator can affect recovery or other daily outputs.

## Local verification result (2026-10-06)

- Android protocol/data suites: 1,188 tests, zero failures, one intentional skip.
- Swift WhoopProtocol on Linux: 814 tests, zero failures, one intentional skip.
- Swift WhoopStore on Linux with snapshot-enabled SQLite: 624 tests, zero failures.
- Private replay of the owner's morning export: 21,133 intact historical frames;
  all 20,453 R18 rows retain the five added fields, and all 680 R26 rows retain the exact
  original frame and decode metadata. Zero mismatches. The capture and replay output remain
  outside tracked source.
- `assembleFullRelease -PstagingRelease`: successful. Dex merging required a local
  4 GiB Gradle heap override; the repository's Gradle settings were not changed.
- The broader Android suite ran 6,877 tests and found seven unrelated failures: three source-anchor
  assertions in `GlowRingFlatArcTest` against the existing UI edits, and four Windows filesystem/log
  assertions in `StrapLogArchiveTest`. The port leaves those UI/production log files untouched.
- Repository-wide doc-comment lint reports twelve pre-existing over-budget sites in the existing
  alarm, Intelligence and UI edits; none is in the port's implementation files.
- Android database upgrades are executed under Robolectric, including preservation of a prior
  waveform row. A Windows-only test-cleanup adjustment defers removal of mounted schema ZIPs to
  JVM exit; database validation itself is unchanged.
- The phone is currently absent from ADB. The APK has not been installed and a live post-upgrade
  strap sync has not been verified. Capture replay is evidence for decoding, not for live BLE behaviour.

The repository parity-governance acceptance check ran 50 tests and failed two checks: existing
Lift Log twin references do not resolve, and the checked inventory/authority hashes do not match
the edited working tree. The authority was not regenerated over unrelated unreviewed changes.
The port's byte/field/schema parity is verified by the shared executable oracle and package tests.
A subsequent test-only Gradle run exposed a missing cached Room schema export. Regenerating it
with non-incremental KSP resolved the prerequisite; all three database-upgrade tests then passed.
The standalone Swift oracle was regenerated again and matched the committed fixture byte for byte.

The signed staging APK is `NOOP-11.8.0-whoop5-inputs-staging-20261006.apk`
(package `com.noop.whoop.staging`, version code 550). SHA-256:
`cf09cdd6c2dc0d82cc6bd2fde5d55cdcd693e3748d5ca7439e4931b0ded8a11f`.

## Fork oxygen display follow-up

Android version 11.8.1 enables the existing candidate display by default at the owner's explicit
request. Existing saved preferences still win. The new note appears on the dashboard, key-metric
and Health tiles, and below candidate values in the detail screen. Candidate values receive no
clinical threshold colour judgement and remain separate from `spo2Pct`, recovery and alerts.
The calculation, raw decode and Apple default-off policy are unchanged. See [fork notes](../FORK.md).

The oxygen/vital test selection passed 71 tests with zero failures. The repository-wide translation
gate reports existing UI/Lift Log gaps and format mismatches; both oxygen strings exist in all nine
Android resource locales. A live post-upgrade sync remains unverified.

The signed fork APK is `NOOP-11.8.1-whoop5-inputs-staging-20261006.apk`, package
`com.noop.whoop.staging`, version code 551. The build and signature verification passed. SHA-256:
`cdbdb6ba5982fd73d181869702c2a491806fc5e93407279bfd7bf40580b2c289`.
