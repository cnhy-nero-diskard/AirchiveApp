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

## Current delivery checkpoint — 2026-10-31 (+08:00)

This section supersedes the interim runtime status above for the newer source
revision only. It does not change the attribution or result of the earlier
checkpoint.

### Source/build revision

- **Branch:** `feat/issue-2-shared-instrument-integration`
- **Source-tested commit:** `54b15a668154433aa12747735ae3c167e3435179`
- **Source-tested tree:** `b7d29cb62a5a2355e72a05efcfea189002a0cbd8`
- **Command:** `.\gradlew.bat --no-daemon --max-workers=2 :shared:testAndroidHostTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest :androidApp:lintDebug`
- **Result:** `BUILD SUCCESSFUL` on the tested source tree. An earlier daemon-backed invocation exited when the Gradle daemon disappeared during lint analysis; the bounded no-daemon rerun above completed successfully.
- **Shared host tests:** 383 passed, 0 failed, 0 errors, 0 skipped.
- **Android artifacts:** Debug APK and debug instrumentation APK assembled; instrumentation source compiled.
- **Debug lint:** 5 warnings and 1 hint, all unchanged findings: `OldTargetApi`, `GradleDependency`, `DataExtractionRules`, `MissingApplicationIcon`, `UseKtx`, and `AutoboxingStateCreation`.
- **OpenSpec:** `openspec validate "deliver-shared-instrument-ui" --type change --strict --no-interactive` passed.
- This record is a documentation-only follow-up; application/test evidence is tied to the source commit/tree above.

### Synthetic native acceptance

All native checks below used only fake owner/history/ThinQ ports and synthetic
markers. The named device was `Medium_Phone_API_35(AVD) - 15` (Android 15/API 35,
1080×2400 physical resolution, 420dpi). No production Firebase account, PAT,
network, household AC or real telemetry was used.

| Scope | Result and evidence |
| --- | --- |
| Same-context draft/profile reconciliation (task 3.2) | Passed `sameContextProfileRefreshKeepsSupportedDraftAndDisablesUnsupportedValues`: a live read changed 25→27 while the 26 draft remained; a step update kept Apply valid; narrowed bounds and a Celsius→Fahrenheit profile made the old-unit/out-of-range draft explanatory and non-applicable. Counter stayed at zero commands. Same-context route entry did not refetch capabilities; the explicit Refresh device state action did. Host tests additionally passed forced profile refresh publication without generation change and late-result suppression on owner invalidation. |
| Disclosure defaults and recovery (tasks 3.4/4.4) | Passed `productionDisclosureDefaultsRespectVisibleRouteSections`: Simple/Detailed route behavior, manual disclosure reset, Account actions remaining collapsed in Detailed, state-required History access expansion across mode changes and manual collapse, Energy daily record, History status guide, and Settings History/storage. Mode selection caused no extra operation counters in the related form/pending-command tests. |
| Display preference persistence (task 3.5) | Android adapter tests passed 2/2 for absent/unrecognized fallback, fresh adapters and private-file persistence; common fallback tests passed; Activity recreation passed. External process probe passed using manually installed debug/test APKs, separate `am instrument` seed/verify phases, and force-stopping both fixture packages between them. The verifier asserts the second Activity PID differs from the seeded PID and reads the saved `detailed` value. |
| Command and account journeys (task 5.2) | Passed `interfaceDetailSelectionDoesNotRestartOrCancelPendingCommand` (Sending→Confirmed once), `acceptedCommandRemainsUnconfirmedWithoutAutomaticRetry`, `failedCommandShowsStableFeedbackWithoutRetry`, and `unconfirmedCommandOffersRefreshAndNeverResends` (one submission; explicit live refresh only). `delayedCredentialVerificationCannotSaveAfterSyntheticOwnerSwitch`, account-switch/credential-clearing, sign-out confirmation, Home/Device draft round-trip, and form/dialog preservation tests passed. |
| Credential privacy | Encrypted credential-store instrumentation passed 11/11; synthetic PAT cancellation/privacy journeys passed. No plaintext marker was written to the captured settings/history/diagnostic projections. |
| Energy/history and route hierarchy | Explicit Energy day/week/month browsing and coverage reuse passed; summary/coverage parity, production route hierarchy and truthful no-usable/offline/denied/loading/error states passed; the 3,000-row History ledger journey passed. |

The explicit profile refresh is user-triggered only; route entry remains
cache-first for same-context profile capabilities, Device-details refresh stays
live-only, and no timer/polling path was added. A transient forced list/profile
failure conservatively clears the verified-profile presentation for that
unchanged context until a successful retry; the draft remains context-scoped,
and commands stay unavailable while profile/state is not verified.

### Curated native captures

The original five Simple/light/1.0 captures remain under
`docs/verification/shared-instrument/captures/`. Task 5.3 adds 44 labeled
production-shell captures in the same directory: five routes across Simple and
Detailed, light and dark themes, and font scales 1.0 and 1.3 (40 captures), plus
the scrolled Home chart labels for both modes and themes at 1.3 (4 captures).

- **Source/test parent commit:** `54b15a668154433aa12747735ae3c167e3435179`
- **Source/test tree:** `0ff055d0293a06b90cda89c0de542a0fc2350f15` (the four
  changed source/test paths over that parent; docs and captures are excluded)
- **Matrix checkpoint:** 2026-11-01 (+08:00)
- **Device:** `Medium_Phone_API_35` AVD, Android 15/API 35, 1080x2400, 420dpi
- **Command:** `adb shell am instrument -w -e class cloud.airchive.personal.preview.SharedInstrumentUiTest#captureSyntheticProductionShellVisualMatrix cloud.airchive.personal.test/androidx.test.runner.AndroidJUnitRunner`
- **Result:** Passed at effective system font scales 1.0 and 1.3. All five route
  anchors and selected destination semantics were checked in both modes and
  themes. `productionDisclosureDefaultsRespectVisibleRouteSections` separately
  checked expanded and collapsed recovery disclosures, including offscreen
  History access. The lower Home captures show all eight energy values and bars
  at 1.3 without clipped labels.

The 44 files use names of the form
`matrix-synthetic-shell-{simple,detailed}-{route}-{light,dark}-scale-{1.0,1.3}.png`,
with `Home-energy-labels` captures for the four additional scrolled images. All
screenshots used fake owner/history/ThinQ ports and synthetic markers. The
Settings screenshot in each run was taken after the fake credential was
removed. No production account, credential, telemetry, or network was used.

An earlier capture exposed Home's `15.11` energy readout wrapping in the paired
layout; the width threshold was changed to stack before shrink at 360dp. The
default-scale Home capture shows the full value on one line. These captures do
not change, score, or waive the historical 74.69% pixel gate.

### Narrow, landscape, and maximum-text acceptance (tasks 5.4 and 6.1)

- **Source/test parent:** `c0c35af880a416571490a42f437cec042df2f69f`
- **Narrow and maximum-text test tree:**
  `290e0b9c1cdd47ddfa32b5944384ce39161b50c0` (parent plus instrumentation
  source before the later landscape-only extension)
- **Final landscape and branch-build source/test tree:**
  `c388393205f2c3a264c0f3b6aa861b9296186060` (same application source plus
  the extended landscape case and navigation-bounds helper; documentation,
  captures, and synced specifications are excluded)
- **Device:** `Medium_Phone_API_35` AVD, Android 15/API 35, physical 1080x2400
  at 420dpi. Fake owner/history/ThinQ ports and synthetic credentials were used.

**Narrow window:** `narrowPhoneWindowKeepsReadingsImeAndDialogReachable` passed
with `adb shell wm size 720x1600`, 420dpi, and effective `font_scale=1.0`, which
measured a 274dp-wide window. The test confirmed stacked complete readings,
selected-route semantics, status/navigation insets, the Settings token and
country fields, native IME visibility, Tab focus to country, Back dismissing
the IME first, a measured 56dp Account actions disclosure, and Back cancelling
sign-out. Two captures are recorded as
`narrow-synthetic-home-under-300dp-scale-1.0.png` and
`narrow-synthetic-home-controls-reachable-scale-1.0.png`.

```text
adb shell am instrument -w -e class cloud.airchive.personal.preview.SharedInstrumentUiTest#narrowPhoneWindowKeepsReadingsImeAndDialogReachable cloud.airchive.personal.test/androidx.test.runner.AndroidJUnitRunner
Result: OK (1 test)
```

**Phone landscape:** `productionShellPhoneLandscapeKeepsRoutesReachable` passed
at logical 914x411dp and effective `font_scale=1.0`. It checked the `15.11`
sampled reading, scrolled until both `°C` and `kWh` were fully above the fixed
navigation container, confirmed the Home `Refresh history` action stays above
navigation, and selected all five routes. Captures are recorded as
`landscape-synthetic-{home,home-readings,home-controls,device,energy,history,settings}-scale-1.0.png`.

```text
adb shell am instrument -w -e class cloud.airchive.personal.preview.SharedInstrumentUiTest#productionShellPhoneLandscapeKeepsRoutesReachable cloud.airchive.personal.test/androidx.test.runner.AndroidJUnitRunner
Result: OK (1 test)
```

**Maximum system text:** the Android Display size and text slider was set to its
maximum; `font_scale=2.0` was checked before the run. The five-route production
shell matrix passed in Simple/Detailed and light/dark, including the long
Settings/measurement explanations and the complete `25°C` / `15.11kWh`
readings. Twenty route captures and four lower Home chart captures are recorded
with `scale-2.0` filenames.

```text
adb shell am instrument -w -e class cloud.airchive.personal.preview.SharedInstrumentUiTest#captureSyntheticProductionShellVisualMatrix cloud.airchive.personal.test/androidx.test.runner.AndroidJUnitRunner
Result: OK (1 test)
```

The narrow and maximum-text instrumentation runs used tree
`290e0b9c1cdd47ddfa32b5944384ce39161b50c0`; the later test-only landscape
extension does not change application source. The final landscape test and
branch build used tree `c388393205f2c3a264c0f3b6aa861b9296186060`. A repeat of
the maximum-text matrix on that final test tree was interrupted during the
switch to physical-device testing, so no result is claimed for that repeat.

Home step buttons use explicit 48dp size and action buttons use 48dp minimums;
the native test exercises these controls, while only the Settings disclosure
has an automated measured 56dp bounds assertion. Full TalkBack labels/order/
announcements were not manually checked because TalkBack was unavailable on
this AVD. Source semantics and selected/expanded-state assertions are not a
TalkBack certification. After the tests, the AVD was restored to 1080x2400,
420dpi, both text scales 1.0, light mode, automatic rotation enabled, and
portrait orientation.

The connected phone was identified as Xiaomi `2406APNFAG` (Android 16/API 36,
1220x2712 at 520dpi). It already contained a non-debug
`cloud.airchive.personal` 0.1.0 installation, which is the instrumentation APK's
target package. No install, replacement, or uninstall was attempted;
physical-phone acceptance remains unverified pending a side-by-side test
application ID or explicit authorization to replace that installation.

### Final branch validation (tasks 5.1 and 7.2)

- **Tested source/test parent:** `c0c35af880a416571490a42f437cec042df2f69f`
- **Tested source/test tree:** `c388393205f2c3a264c0f3b6aa861b9296186060`
- **Command:** `.gradlew.bat --no-daemon --max-workers=2 :shared:testAndroidHostTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest :androidApp:lintDebug`
- **Result:** `BUILD SUCCESSFUL`; 383 shared host tests passed with 0 failures,
  errors, or skips. Debug APK and instrumentation APK assembled. Debug lint
  reported the same six findings: `OldTargetApi`, `GradleDependency`,
  `DataExtractionRules`, `MissingApplicationIcon`, `UseKtx`, and
  `AutoboxingStateCreation`.
- **OpenSpec:** strict validation of `deliver-shared-instrument-ui` and
  `openspec validate --specs` both passed after the additive sync. The change
  remains active and unarchived.
- **Main recheck:** fetched `origin/main` at `1c87a60` (`add impeccable to
  gitignore`). Its only path is `.gitignore`; it does not affect the app,
  instrumentation, specs, or native evidence. No source verification was
  invalidated by that commit. The working branch remains based on its existing
  history and the PR will target the fetched `main`.
- The source/test tree above is the final native-test/build tree. OpenSpec sync,
  task tracking, and evidence edits after it are documentation/specification
  only; no application source changed afterward.

### Remaining unverified acceptance

- **Task 5.3:** Passed for all five production routes, both interface modes,
  both system themes, and font scales 1.0/1.3; see the source/test tree and 44
  new native captures above.
- **Task 5.4:** Narrow, landscape, maximum-text, IME/Back/focus, inset and
  disclosure checks passed within the AVD scope above. Full TalkBack
  announcements/order and physical-phone acceptance remain unverified.
- **Task 5.5:** Source/manifest boundary inspection found the preview Activity
  only in the non-exported `src/debug` manifest and the production `MainActivity`
  builds `ProductionAirchiveApp` with Firebase/Android ThinQ adapters, not the
  fake fixture. The legacy demo and generic fake utilities remain source code;
  no production entry point references them. A normally signed release artifact
  was not built or inspected because the release signing preflight/private
  signing material is not part of this authorization. Packaged release
  class/resource acceptance is therefore **unverified**.
- No production account, real ThinQ credential, household device command,
  Firebase-emulator suite, private signing material, tablet, or iOS acceptance
  was used or claimed.
- The connected physical phone was not modified; its existing non-debug app
  prevented a safe instrumentation install under the current package ID.
- The historical Home pixel gate remains failed/open at exactly 74.69%; none of
  these captures changes, scores, or waives that historical gate.

The implementation review to date found and resolved the Home wrap issue,
clarified synthetic capture attribution, added a PID-verifying process probe
and host fence coverage for forced same-context refresh, renamed the
accepted-status journey to match its assertions, and added recovery-disclosure
manual-collapse assertions. This is not the bounded independent review required
by task 6.2; that review and its confirmation pass remain open. Accessibility,
release-artifact and physical-device limits above also remain open.
