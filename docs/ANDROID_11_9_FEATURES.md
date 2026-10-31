# Android 11.9: coaching, energy context and daily reports

This fork update preserves the existing Android theme, typography, navigation shell and WHOOP
mapping. It extends Android integrations and presentation; it does not replace the local recovery,
strain or sleep algorithms with model-generated scores.

## Widgets

All five widget surfaces use the application's dark/light palette tokens instead of the old
navy/gold copies. The main score widget follows Charge / Effort / Rest order with score rings;
its numerals use the bundled rounded font. Rings adapt to narrow widget widths. The compact
widget uses the same order, with explicit labels. Missing scores remain blank. The HR, Stress and
Coach Brief widgets inherit the matching card surfaces and text colours.

## Coach sharing and full access

Coach settings now separates daily physiology, workouts, nutrition/energy, body measurements,
journal/lab data and derived insight summaries. The existing master data-sharing consent still
gates every request. Defaults preserve daily/workout summaries; other categories require selection.
The controls also apply to enabled AI morning briefs. Manual questions and the conversation itself
continue to be sent to the chosen provider.

Full access has its own confirmation explaining local-model / verified-ZDR use, sensitive health
data, locations and retention. NOOP cannot verify an endpoint's hosting or retention policy.
Changing provider or custom-server URL revokes full access. Turning off master sharing prevents
further retrieval; it cannot revoke information already sent to a provider.

Full mode exposes all local SQLite health tables and dates through a separate OS-enforced read-only
database handle. The model can aggregate, join and page records instead of being limited to the old
14-day summary. Audited preference datasets expose profile, moments, active workouts/lifts, sleep
plans, nap overrides and biofeedback logs. API keys, authentication settings and arbitrary filesystem
access are excluded. Preferences, long cells and raw blobs have explicit paging paths.

Each answer has a maximum of 24 retrieval requests, 200 rows per SQL page, a six-second query timeout
and a 240,000-character conversation budget. These are request limits, not a hidden restriction to
recent dates or selected tables. The app reports an exhausted budget rather than claiming a complete
analysis. A model needs sufficient context capacity and must follow the documented query protocol;
access alone cannot ensure correct insights, establish causation, or recover unrecorded data.

## Confirmed logging and voice

The model may propose one structured journal entry or completed workout when the user asks to log
something. Parsing never writes. The reply shows the proposed fields, opens a review dialog and saves
only through **Confirm and save**. Existing entries are not overwritten. Unsupported actions, missing
dates/answers, invalid times and future workouts are rejected. Workout logging does not invent HR,
calories, GPS or strain. Journal questions created this way are registered in the local journal UI.

The existing on-device Android recognizer supplies dictation. Optional conversation mode sends the
recognized text, reads the reply and resumes listening. Read-aloud selects an installed voice marked
offline; there is no cloud speech fallback or bundled speech-model download. Android must provide an
offline language model/voice. Missing support is explained, with links to speech/TTS settings. Moving
the app into the background stops the microphone and playback. Proposed logs still require an
on-screen confirmation; a spoken “yes” is not a write authorization.

## Health Connect and energy balance

In **Data Sources → Health Connect**, select **Nutrition**, **Activity** and **Body composition** as
needed and grant the corresponding Android permissions, then import. Weight, body fat, lean mass
and profile-derived BMI already had importer support; the new Energy & body view makes them easier
to inspect alongside energy data. Existing optional profile-weight sync remains independent.

Nutrition imports logged `NutritionRecord.energy`, never inferred food intake. The food window is
the most recent 30 calendar days, retaining previously imported history. A completely successful
read reconciles only that source/key/window transactionally, including source deletions. Failed or
partial reads preserve previous rows. Values are summed within one origin and the maximum origin
total is selected to reduce double counting from relayed logs. This conservative rule may omit
separate meals logged in different apps; use one consistent food-log origin.

Daily energy balance is **logged intake minus total expenditure**: negative is a deficit, positive
is a surplus. Health Connect total expenditure takes precedence, followed by external active plus
resting energy, then NOOP's HR-based whole-day estimate. Active-only energy is not treated as total
burn. Missing intake does not become zero. Incomplete food logs, partial-day wear and estimation
error affect the result; the current day continues accumulating. Sources must actually write the
requested data to Health Connect—permission alone does not create records.

Energy & body includes a date selector and recent balance history. Intake, total burn and balance
are selectable in Compare and Explore; balance is also available in the day timeline. Body mass
and skin-temperature displays follow the app's unit preferences.

## WHOOP 5 oxygen throughout the app

The existing unvalidated candidate remains separate from calibrated/imported percentages. It is
available as a labelled estimate in Compare, Explore and the day timeline, and in Live's **Latest
synced vitals** card beside skin temperature. Live explicitly identifies it as a synced nightly
candidate rather than a continuous live oxygen reading. The display switch remains respected.
Raw optical ratios retain their separate identity. No new candidate affects recovery, illness
detection, Health Connect writeback or clinical thresholds.

## Daily reports and connection notice

Notifications settings provides morning and evening times (defaults 08:00 / 21:00), with separate
enable controls. Fresh defaults enable the requested reports; saved morning choices are respected.
The morning report uses the banked Rest/Charge values; late sync can deliver it later. The evening
report uses current-day Effort and the existing next-day Charge forecaster, including its uncertainty
range and assumed sleep. Insufficient history produces an unavailable forecast, not a guessed score.

WorkManager schedules the reports independently of the foreground screen. Clock/time-zone changes
and reboot reschedule them. Android can delay background work; these are not exact alarms. Missing
or stale data is not substituted for current-day scores.

The Bluetooth service keeps Android's mandatory foreground-service notice, but it is static,
silent, dismissible and no longer reposted for live state/battery/score changes. **Hide connection
notification** opens that channel's Android settings so it can be disabled without disabling daily
reports. Android may still list the service under active apps. No connection handshake or strap
command was changed.

## Navigation and platform scope

More uses the Insights hub for overlapping intelligence/insight entry points, Health for its vitals
flow and Data Sources for overlapping integration pages. Routes remain available to their parent
flows and deep links. Energy & body has one entry in the Body group.

This request targets the customized Android app. Android UI, Health Connect, widget, voice and
notification adapters therefore remain Android-specific. The energy-balance arithmetic has a Swift
twin, standalone Swift oracle and tests. Existing portable database schemas and backup-setting
whitelists are unchanged; imported values use existing generic metric-series keys/tables. New Coach
sharing and report-time preferences are local Android preferences, not a new cross-platform backup
contract. There are no Apple app-target source changes.

## Verification

- Signed staging release compiled and installed over the emulator's existing staging app.
- All 345 selected Android tests passed with zero failures/errors. They cover Coach read-only access,
  credential exclusion, action validation, energy source
  precedence/deletions/missing values, report scheduling/DST, Health Connect scopes, oxygen and widgets.
- Swift energy helper ran against the same deficit/surplus/null cases; its stdout is pinned verbatim
  in the Kotlin test. The actual helper and XCTest file also passed in an isolated Swift package.
- A local loopback test endpoint exercised a full schema → query → result → reply cycle. Its journal
  proposal was cancelled, then explicitly confirmed and saved through the UI. No cloud API/key was used.
- Widgets were rendered in an Android widget host; Energy & body and Compare were opened. The installed
  Android TTS engine accepted an offline voice. Actual spoken recognition/conversation remains a
  physical-device check; no live strap sync or fresh Health Connect dataset was available for this pass.
- A broader run executed 6,889 tests: the seven previously documented GlowRing/source-anchor and
  Windows log/filesystem failures remain. It also found a new German resource gap, which was repaired
  and retested. The fork still has pre-existing detached-comment and translation-audit failures;
  new advanced controls retain some English copy and other locales fall back to English.

KSP incremental processing is disabled for reproducible Room schema exports: UI-only rounds otherwise
clear the declared export directory without regenerating it, causing test configuration to fail before
any test can run. No migration or test oracle was changed to bypass that failure.

## Published build

Android `11.9.0-staging`, build `552`, package `com.noop.whoop.staging`, signed with the same staging
certificate as the previous preview. The final APK was installed as an update on the Android 16
emulator and launched without an Android runtime crash. The Samsung phone was not connected for
this verification pass.

APK: `NOOP-11.9.0-staging-20261031.apk`.
SHA-256: `ffa7157e4774a12e000a85c0a7f2442878ca9c82bfc4b0e8b806b1f27a02ba5d`.
