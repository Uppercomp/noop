# Uppercomp's NOOP fork

This fork preserves the local Android UI, typography, theme and feature work, with additional
WHOOP 5.0 input mapping based on pinned OpenStrap protocol references. It is independent of
the upstream NOOP release. Upstream attribution and the PolyForm Noncommercial license remain.

## Android 11.8.1 staging build

The APK installs as `com.noop.whoop.staging`, matching the existing local staging app. Back up local
data before upgrading. Signing credentials and personal captures are not published.

- WHOOP 5 summaries retain five additional raw timing/activity/metadata fields.
- All six R22 research layouts are decoded; original records remain archived.
- Compact optical waveforms retain complete frames, base values and burst metadata.
- Room 41→42 and the corresponding Swift migration preserve existing waveform rows.
- Existing Android UI work, including the local Lift Log, is included as a working-tree snapshot.

## Blood oxygen display

This Android fork enables the existing experimental oxygen candidate display by default at the
owner's request. A previously saved on/off choice still wins. The switch remains in Settings →
Experimental → Blood Oxygen: strap estimate. Apple targets keep the upstream default-off policy;
the candidate calculation and stored data contracts are unchanged across platforms.

When no imported blood-oxygen percentage exists, WHOOP 5.0's in-band summary byte can supply a
nightly candidate. It is shown with **“Not validated”**, plus an explanation below
the detail value. It is not a validated SpO₂ measurement, may be inaccurate, and must not be used for
medical decisions. It does not feed recovery scores, illness detection or alerts. Missing candidate
data stays missing. WHOOP 4.0's raw optical channels are not converted into a fabricated percentage.

## Verification and limitations

The protocol/store port passed targeted Android and Swift suites and a private capture replay;
see [the input mapping report](docs/WHOOP5_OPENSTRAP_PORT.md). That report also lists known failures
in the wider working tree. Research layouts not seen on the owner's hardware remain candidate
interpretations. The new build has not been verified through a live post-upgrade strap sync.

NOOP's recovery, strain, sleep, respiration, calories and fitness outputs use its local algorithms;
this fork does not claim exact equivalence with WHOOP's proprietary outputs.

The oxygen/vital test selection passed 71 tests with no failures, including fresh-install defaults,
saved choices, all 31 in-band candidate values, imported-value precedence and absent data.
The whole-tree translation gate also finds existing untranslated UI/Lift Log strings and format
mismatches. The new oxygen notes have translations in all nine shipped resource locales.
