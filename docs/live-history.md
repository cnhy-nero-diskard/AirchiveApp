# Android real-history integration

## Scope

This milestone replaces the Android launcher's simulated history graph with
the owner's actual stored Firestore data and a durable normalized Room cache.
It does not enable LG ThinQ controls, accept a ThinQ PAT, change backend rules,
or produce a release-signed APK. Google sign-in authorizes history separately
from ThinQ. Missing client configuration must never silently show demo data.

The enrolled backend was already verified before this milestone. See the
sibling Airchive repository's `docs/mobile-access.md` for the signed-in UID,
device/project review, identity bootstrap, deployed rules, denial tests, and
deny-all rollback. No server credentials are shipped with the app.

## Read-only backend baseline

At `2026-10-02T05:57:28.531809Z`, an independent server-side read of the configured
device found:

- 288 observations in the latest 24 hours, fetched in pages of at most 250.
- 30 available finalized daily-total documents in the bounded latest-30 query.
- Recorded observation energy units: `Wh`.
- Latest observation and collector success: `2026-10-02T05:55:02.865826Z`.
- Collector consecutive failures: 0.
- Root device identity fields: `deviceId` and `updatedAt`; no alias, model,
  or verified live profile was recorded. The app must not invent those values
  or claim current connectivity from stored telemetry.

These are backend baseline counts, not evidence of the new app's synchronization
or display. A moving 24-hour window may differ when the app is tested.

## Acceptance boundaries

- Scope Room rows and sync state by project, current Firebase UID, and device.
- Render existing local history before a remote refresh finishes.
- Bootstrap only the latest 24 hours and up to 30 available finalized totals.
- Apply the existing ten-minute open/resume refresh gate and explicit manual
  refresh; do not install continuous Firestore listeners or background polling.
- Fetch older five-minute observations only on explicit range selection.
- Retain same-owner cache on offline/denied reads; never show it to another UID.
- Preserve null intervals, recorded units, quality flags, and sampled timestamps.
- Keep production ThinQ token entry and all simulated/live controls unavailable
  until their separate secure integration and deliberate validation.

## Verification

Verified on 2026-10-02 with the existing owner's Android debug installation.
The app was upgraded with `adb install -r`, preserving the signed-in session
and production history. No ThinQ token was entered and no AC command was sent.

### Passed checks

- 305 shared JVM/Room host tests: zero failures or errors. Focused additions
  cover bootstrap versus incremental refresh, the ten-minute foreground gate,
  deliberate retry, owner isolation (including a transitional Compose frame),
  stale-result suppression, confirmed account actions, and serialization of
  historical downloads with cache deletion. Account changes and deletion were
  tested with fixtures/SQLite, not by signing the real owner out on the phone.
- Android debug app and instrumentation APK builds passed. The updated APK was
  installed on the owner's phone and all five production routes were visited.
  Home displayed 288 actual cached observations and an 11 Wh sample ending
  `2026-10-02T06:10:02.865Z`; no demo device or simulated-control surface appeared.
- Final selected phone suite: **10 tests passed** (two opt-in live-history
  checks, two Google chooser checks, six Firebase-task cancellation checks).
- Live isolated first load: **288 observations in two pages, 30 daily totals**,
  four telemetry adapter calls (two observation pages, totals, health).
  These are adapter-call counts, not billing/underlying RPC counts.
- Reopening the isolated Room file preserved all 288 observations and 30 totals
  with **zero telemetry adapter calls**. Subsequent incremental sync merged one
  observation through one persisted-at page and one reconciled-at page; two
  telemetry adapter calls. Combined live cache test elapsed about four seconds.
- Production Activity launch and recreation with its Firestore SDK networking
  explicitly disabled both displayed **300 accumulated cached observations**.
  The last successful server-sync timestamp did not advance. Networking was
  restored in `finally`; the phone's Wi-Fi/mobile settings were never changed.
  This is Activity-recreation evidence, not an airplane-mode process-restart test.
- Force-stopping and reopening the real app preserved history. A subsequent
  explicit History refresh succeeded, displaying no recorded sync errors and
  a last-success time of `2026-10-02 15:22 Asia/Manila`.
- Energy showed the selected `2026-10-01` local day as fully cached; pressing
  its load button again reported that no remote reads were needed. A further
  `2026-09-30` request remained loading during a bounded observation and was
  cancelled by navigating away; do not claim its download completed.
- ThinQ token entry and all AC controls remained unavailable. The temporary
  instrumentation package was removed; the updated app remains installed.

### Important boundaries and remaining checks

- Firestore reads explicitly use `Source.SERVER`. Room alone provides offline
  rendering; SDK cache hits cannot claim current authorization or advance the
  server-sync timestamp. Offline/denied reads retain the same owner's rows.
- The phone intermittently could not resolve `firestore.googleapis.com` during
  screen checks. Cached content remained visible, the app reported offline,
  and a later manual refresh recovered. This is not a continuous-connectivity
  or performance guarantee.
- Collector health and the latest-30 daily-total snapshot are currently loaded
  during bootstrap. Incremental refresh updates observations and watermarks;
  it does not continuously refresh collector health or rotate the daily-total
  snapshot. Displayed health is cached and its sample age is shown separately.
- No destructive sign-out/account switch was performed on the owner's phone.
  Real-control task 4.6, broader production tasks 6.3/6.4, and signed-release
  tasks 7.1/7.2 remain unaccepted. Prior backend authorization/denial checks
  are separate evidence in the sibling handoff, not newly rerun production-
  server acceptance.
- The iOS scaffold and adapter boundaries are documented in `iosApp/README.md`.
  Its launcher shows an explicit setup-required screen, not demo device data.
  Project wiring was checked from source files; iOS compilation/signing/device
  verification were not tested on Windows.
- Android's production ThinQ path now uses the Keystore-backed runtime credential
  flow and profile-gated controls; `docs/thinq-control.md` documents its status.
  The live-control task remains unaccepted until deliberate real-AC actions are
  recorded. No PAT was read from local properties and no AC command was sent.

### Repeatable checks

```powershell
.\gradlew.bat :shared:testAndroidHostTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest
openspec validate add-personal-airchive-mobile-app --strict
git diff --check
```

The instrumented `LiveHistoryCacheVerificationTest` is skipped by default.
With explicit opt-in and the reviewed owner/device runner arguments, it uses
the phone's existing Firebase session to bootstrap real history into a uniquely
named, isolated Room database, reopen it, and perform an incremental refresh.
Its isolated-data test deletes only its own test database afterward, never the
app's history. Its second test requires a populated production cache and checks
the real Activity while Firestore networking is disabled, then restores SDK
networking. It sends no Firestore write, ThinQ operation, sign-in, or sign-out.

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb -s <phone-serial> install -r -t androidApp\build\outputs\apk\androidTest\debug\androidApp-debug-androidTest.apk
& $adb -s <phone-serial> shell am instrument -w -e class cloud.airchive.personal.data.firebase.LiveHistoryCacheVerificationTest -e airchive.liveHistoryCheck true -e airchive.expectedOwnerUid <verified-uid> -e airchive.expectedDeviceId <verified-device-id> cloud.airchive.personal.test/androidx.test.runner.AndroidJUnitRunner
& $adb -s <phone-serial> uninstall cloud.airchive.personal.test
```

Client options must be configured locally and the debug signing certificate
registered with Firebase before a build can use real Google sign-in. This is
not a release APK or acceptance of a release signing identity.

## Windows-host verification

The shared and Android build checks pass on this host:

- `:shared:testAndroidHostTest`: **309 tests passed**, with no failures/errors.
- `:androidApp:assembleDebug` and `:androidApp:assembleDebugAndroidTest`:
  passed; the latter builds the instrumentation APK but does not execute it.
- `:androidApp:lintDebug`: passed with **0 errors and 4 warnings**. The two
  API-27 navigation-bar errors were fixed by moving those attributes to
  `values-v27` / `values-night-v27`; API-26 has a dark, high-contrast fallback.
  Remaining warnings concern target/compile SDK advisories, backup-rule
  deprecation, and the missing launcher icon.
- `openspec validate add-personal-airchive-mobile-app --strict` and
  `git diff --check`: passed.
- `:androidApp:testDebugUnitTest` has no source-set tests (`NO-SOURCE`); the
  shared Room/JVM host test task is the applicable local unit suite.
- Firebase Auth/Firestore emulator instrumentation on the `Medium_Phone_API_35`
  AVD (`demo-airchive-telemetry-poller` only): **39 passed, 7 skipped, 0 failed**.
  The passing local cases exercise fake-owner reads, a distinct fake intruder's
  denial, wrong-device/unlisted-path denials, bounded-query enforcement, and
  client-write denials. The 7 skips are opt-in tests requiring the real enrolled
  owner/device; no production Firebase project was contacted. The missing-client-
  options test now supplies explicit blank options, so it is independent of the
  developer's local Firebase configuration.

These host checks are not evidence of production acceptance. Local Firebase
emulator tests use only the `demo-airchive-telemetry-poller` project and fake
accounts; they do not test enrolled-owner production rules or the
collector/dashboard. No live AC command, release signing identity, target-phone
release upgrade, new production authorization denial, or production-server
no-regression check is claimed here. See the OpenSpec task list for the exact
remaining owner/hardware gates.
