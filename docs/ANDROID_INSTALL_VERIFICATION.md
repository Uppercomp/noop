# Android install verification — 2026-10-07

The published 11.8.1 staging APK was downloaded again directly from the GitHub prerelease. Its
SHA-256 matches the signed local build:

`cdbdb6ba5982fd73d181869702c2a491806fc5e93407279bfd7bf40580b2c289`

## Checks performed

- Android 16 / API 36, existing emulator installation: `adb install -r` succeeded. The original
  installation date remained unchanged. Today, More, Health and Sleep opened without a captured
  AndroidRuntime exception.
- Android 16 / API 36, separate read-only emulator with NOOP absent: fresh installation succeeded.
  Cold launch reached the first-run terms screen without a captured AndroidRuntime exception.
- Samsung SM-S926B / Android 16: the installed 11.8.0 staging APK and the new APK have the same
  signing certificate. `adb install -r --user 0` upgraded it to 11.8.1-staging, version code 551.
  Launch succeeded and resumed onboarding. No uninstall, data clear or downgrade was performed.

The emulator tests establish package installation and app startup, not Bluetooth interoperability.
No new live WHOOP sync or physiological validation was performed in this check.

## Reported “App not installed” failure

The copy saved in the phone's Downloads had the correct filename and byte length (23,561,743), but
its SHA-256 was `6e52e3625c2370bb29ae666d6642c2f9451bb642995db5a37c7a532dc82b6399`.
It differed from the verified APK at 2,113,785 byte positions. APK signature verification failed
with a v2 content-digest mismatch, and ZIP decompression failed on the damaged contents.

Installing that damaged copy on the emulator reproduced the rejection:

```text
INSTALL_PARSE_FAILED_NO_CERTIFICATES
APK Signature Scheme v2: SHA-256 digest of contents did not verify
```

The phone's damaged Downloads file was replaced with the verified APK. Its on-device SHA-256 then
matched the published asset. The cause of the file corruption itself was not established. No source
or signing change was needed, and the prerelease APK remains unchanged.

## Update compatibility

This artifact updates the customized staging app (`com.noop.whoop.staging`) signed with the same
local fork key. It is not an update for another package name or for a staging APK signed by an
unrelated fork. A package name and compatible signing identity, rather than an upstream version
label alone, determine whether Android accepts an update. Keep local backups; do not uninstall
NOOP merely to work around a damaged APK.
