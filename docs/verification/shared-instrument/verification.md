# Shared Instrument verification

This revision-linked record is an interim verification checkpoint for OpenSpec
task 5.1. It does not claim completion of the native runtime, visual, process
restart, release-artifact, physical-device, or accessibility acceptance tasks.

## Android build, host tests, and lint

- **Branch:** `feat/issue-2-shared-instrument-integration`
- **Tested commit:** `5178a5a432faee4e7b364fb951d603e4de0dfcdb`
- **Tested tree:** `8b901cf92a6749fdd3e03f0c5c8141be6aa3f7e9`
- **Command:** `.\gradlew.bat :shared:testAndroidHostTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest :androidApp:lintDebug`
- **Options:** normal online Gradle execution; no `--offline` option.
- **Result:** `BUILD SUCCESSFUL`.
- **Shared host tests:** 381 passed, 0 failed, 0 errors, 0 skipped.
- **Android build:** debug APK and debug instrumentation APK assembled successfully; instrumentation source compilation passed.
- **Lint:** task succeeded with 5 warnings and 1 hint:
  - `OldTargetApi` — app target SDK compatibility warning (`androidApp/build.gradle.kts:117`).
  - `GradleDependency` — a newer compile SDK is available (`androidApp/build.gradle.kts:92`).
  - `DataExtractionRules` — `android:allowBackup` deprecation (`androidApp/src/main/AndroidManifest.xml:4`).
  - `MissingApplicationIcon` — no explicit app icon (`androidApp/src/main/AndroidManifest.xml:3`).
  - `UseKtx` — SharedPreferences edit extension suggestion (`AndroidInterfaceDetailPreferences.kt:19`).
  - `AutoboxingStateCreation` hint in the debug preview Activity (`SharedInstrumentPreviewActivity.kt:69`).

## Runtime acceptance status

- No ADB-ready emulator was available. Android instrumentation tests—including
  the encrypted credential-store adapter test and the new synthetic shell
  journeys—were **not executed**. Their successful compilation is not a runtime
  pass.
- No production Firebase account, real ThinQ credential, household device,
  production network, private signing material, or owner data was used. Tests
  use synthetic markers and in-memory fake ports only.
- The historical Home pixel gate remains failed/open at 74.69%; this build does
  not change or reclassify that result.

This checkpoint is not the final acceptance matrix. Add later tested revisions,
emulator configuration, captures, and bounded-review results here without
reusing these results for a different source tree.
