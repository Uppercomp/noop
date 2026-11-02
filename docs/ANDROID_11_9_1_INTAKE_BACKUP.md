# Android 11.9.1: intake-only Health Connect and consistent backups

The 11.9.0 setup guidance included Activity alongside Nutrition. Existing dashboards give imported
activity energy priority over NOOP's own calorie estimate, so that advice could unexpectedly change
historical calorie displays. The intended integration was food intake alongside NOOP expenditure.

## Corrected source boundary

- New Health Connect selections default to Nutrition only. Existing explicit category selections
  remain available; deselect all categories except Nutrition for an intake-only integration.
- Active and total calories-burned records are excluded from every Health Connect read category,
  including when Android retains old permissions. Activity still offers steps, workouts, distance
  and VO2 max; Body composition remains a separate opt-in.
- Existing Health Connect burn values remain stored. Android repository reads suppress those burn
  fields and series, including the comparison resolver, while preserving food intake, weight and
  other unrelated fields. This is a reversible presentation/source-policy correction, not a deletion
  or a rewrite of WHOOP history.
- Energy balance remains logged intake minus total expenditure, but no longer prefers historical
  Health Connect total burn. Explicit Apple Health file imports retain their existing treatment.

In **Data Sources → Health Connect**, select **Nutrition** alone and import. Body composition can
also be enabled when weight/body measurements are wanted. Activity is not needed for food calories.

## Backup creation

Checking a live SQLite file before copying it does not verify the bytes ultimately archived.
Modern Android SQLite now creates a separate transactional snapshot using `VACUUM INTO`; that
snapshot is checked before compression and removed afterwards. Pending WAL commits are included,
and later live writes cannot alter the staged file. See SQLite's
[snapshot documentation](https://www.sqlite.org/lang_vacuum.html).

Older SQLite versions retain the existing checkpoint/locked-copy compatibility path, with an
additional check of the actual staged copy. Busy checkpoints fail explicitly. Existing restore
integrity checks remain mandatory: corrupt pages are not accepted or hidden to force a restore.
The `.noopbak` entry names, settings whitelist, Room schema and physiological formulas are unchanged.

## Verification and platform scope

141 selected Android tests passed, including old-grant scope exclusion, intake/expenditure
separation, historical burn suppression without deleting source rows, missing food logs, backup
containers and integrity rejection. A native SQLite test verifies that a snapshot includes pending
WAL commits and remains unchanged after later writes. A fresh snapshot exported on a Samsung Android
16 device passed archive CRC and full SQLite integrity checks after transfer to the PC.
A reconstructed backup was restored through the normal importer on an Android 16 emulator, then
opened through Room and checked again. The restore integrity gate was retained throughout.

The correction concerns Android Health Connect and Android backup adapters. Apple has no Health
Connect adapter; no portable analytics formula, stored value contract or schema migration changed.
Private backup copies and recovery artifacts are excluded from source control and release assets.
