# Android application identity migration

Issue #52 establishes one canonical Android identity for Minis for Android.

## Canonical identity

- Android namespace: `io.github.slackerllc.minis`
- Android applicationId: `io.github.slackerllc.minis`
- Android testNamespace: `io.github.slackerllc.minis.test`
- Instrumentation runner: `androidx.test.runner.AndroidJUnitRunner`

The reverse-DNS identity is based on the verifiable GitHub repository owner `Slacker-LLC`. The Java package segment uses `slackerllc` because hyphens are not valid Java/Kotlin package identifiers. This avoids claiming ownership of an unrelated DNS domain while giving the project a stable owner-scoped identity.

## Legacy installations

`dev.openminispet.android` is a separate legacy Android application identity. Android does not treat an APK with `io.github.slackerllc.minis` as an in-place update of that package, so both packages may coexist on a device.

This repository does not currently contain a verifiable cross-package private-data importer/exporter for the legacy app. The manifest also sets `android:allowBackup="false"`. Therefore this migration does **not** claim automatic transfer of Room databases, SharedPreferences, internal files, granted permissions, notification/accessibility/default-assistant roles, or other package-scoped state. No hidden root copy of another package's private data is performed.

Existing product-level storage names are intentionally kept stable inside each app sandbox, including Room database names such as `minis.db` and existing SharedPreferences names/keys. Keeping those names stable does not make the two Android package sandboxes interchangeable.

If a future release adds legacy-data transfer, it must use an explicit, independently testable export/import contract rather than relying on package renaming or privileged filesystem copying.

## Android integration contracts

The source, unit-test, and instrumentation-test package roots move from `com.openminis.app` to `io.github.slackerllc.minis`. JNI class symbols and active build/debug tooling move with the Kotlin/Java package so native bindings remain exact.

Manifest component names remain relative to the canonical namespace. Provider authorities remain `${applicationId}`-derived so the new app receives its own collision-free authorities. The `:pet` process suffix remains unchanged.

User-facing/product deep-link contracts are not renamed merely because the Android package changes. Existing `minis:` links and HTTPS app links such as `app.minis.love` remain product contracts. Package-scoped internal intent actions that were explicitly based on the old Kotlin namespace move to the canonical namespace together with their callers.

Changing applicationId can require external configuration updates that are not provable from this repository alone, including Digital Asset Links, OAuth/app-registration allowlists, package-name/certificate bindings, device roles, and store/distribution metadata. Repository CI verifies the APK package identity but does not claim those external systems are already updated.

## Regression policy

Active Android/native/build/CI paths must not contain these legacy identities:

- `dev.openminispet.android`
- `com.openminis.app`
- `com/openminis/app`
- `Java_com_openminis_app`

`scripts/test-android-identity.sh` enforces that narrow rule. Historical, legal, provenance, migration, release-note, or archival documentation may retain legacy names when required to accurately describe origin/history; such records are not active runtime identity and must not be rewritten solely to satisfy the guard.
