# Tasks

## 1. Shared update model and rules

- [x] 1.1 In `shared/commonMain`, add the version-1 manifest model and a strict parser (design §1, `release-pipeline` design §7). It rejects unknown `schemaVersion`, missing or mistyped fields, non-HTTPS APK URLs, non-hex hashes, and notes over 4000 characters, and it ignores unknown extra fields. Verify with host tests covering a valid manifest, each rejection, and that notes stay plain text.
- [x] 1.2 Add the offer decision (package, pinned signer, version code greater than installed) and the update state machine from design §4, with expected package and pin as constructor inputs. Verify with host tests for every transition, including:
  - same or lower code → `UpToDate`
  - signer mismatch → `Unavailable` with no download
  - Cancel → `Available`
  - verification failure → `Failed` with a reason
  - install cancelled → `ReadyToInstall`

## 2. Feed client, preferences, and check scheduling

- [x] 2.1 Add the update HTTP client (design §2): its own Ktor CIO instance with no auth or cookies, User-Agent `Airchive/<versionName>`, HTTPS-only, at most 5 redirects, host rule `github.com` / `*.githubusercontent.com`, 64 KiB manifest cap, and 15 s / 30 s timeouts. Verify with MockEngine host tests for:
  - a redirect to another host and to `http:`
  - six redirects
  - an oversized body
  - 404, offline, and timeout
  - that no request carries an `Authorization` or `Cookie` header
- [x] 2.2 Store the last successful check, the dismissed version, and the pending-install record in the installation-wide preferences that hold Interface detail, so sign-out leaves them. Verify with a host test that an owner sign-out leaves all three in place.
- [x] 2.3 Add `AppUpdateBindings` (nullable) to `ProductionAirchiveApp`, plus BuildConfig fields for the feed URL and pinned certificate. Production builds them only in release builds; debug and iOS pass `null`. Start the resume check from `MainActivity.onResume` with its own `ForegroundRefreshBackoff` and job, only when the last success is at least 6 hours old, and cancel it in `onPause`. Verify:
  - a host test shows a resume within 6 hours makes no request, while a failure backs off and leaves the history and device refresh state unchanged
  - a debug build constructs no update client and makes no feed request, because the binding is null

## 3. Settings section and Home notice

- [x] 3.1 Add "App version & updates" to Settings with every state in design §4:
  - current version and last check
  - available version with plain-text notes and size
  - progress with Cancel
  - Retry, up to date, unavailable with cause, and "In-app updates apply to release builds" for a null binding

  Detailed adds version code, commit, SHA-256, and feed URL. Opening it never checks. Verify with Compose host tests for each state in Simple and Detailed, and a test that composing the section issues no feed request.
- [x] 3.2 Add the dismissible Home notice in a reserved slot outside the instrument panel (not a modal). It is deferred while a temperature draft, confirmation, or command is pending, hidden for a dismissed version, and opens the Settings section when tapped. Verify with Compose host tests for a pending command (notice appears only after it settles, command unchanged), dismissal persisting for that version, and tap navigation.
- [x] 3.3 Check large text and TalkBack semantics for both surfaces. Verify with Compose host tests at the maximum font scale (no clipping) and content descriptions on every action.

## 4. Download and pre-install verification (Android)

- [x] 4.1 Run the download in a coroutine owned by `AirchiveApplication`, so it survives rotation and leaving the screen (design §5):
  - check free space against `apk.size` plus headroom
  - stream to `cacheDir/updates/<versionCode>.apk.part`, hashing as it goes
  - abort above `apk.size` or 200 MB
  - delete on cancel or failure, and delete leftover `.part` files at startup

  Verify with host tests using a MockEngine body for an over-size abort, a cancel deleting the file, and a startup cleanup.
- [x] 4.2 Add archive inspection through `getPackageArchiveInfo`, using signing certificates on API 28+ and signatures on API 26–27: package, `longVersionCode`, exactly one signer, and not debuggable. Generate test APK fixtures at build time with a throwaway key; never commit a key. Install an API 27 system image and AVD for `scripts/test-emulator.sh` (`AIRCHIVE_TEST_AVD`). Verify with instrumented tests on the API 37 and API 27 emulators (`ANDROID_SERIAL=emulator-5554`, GPU host) covering a hash mismatch, a wrong signer, two signers, a debuggable APK, a lower code, and the valid throwaway-pin case.

## 5. Installation and outcome

- [x] 5.1 Add `REQUEST_INSTALL_PACKAGES` and the install-permission flow: explain, open `ACTION_MANAGE_UNKNOWN_APP_SOURCES` for this package, and re-check on return. Verify with a Compose host test for the explanation state, and an instrumented test that returning without the permission stays in `NeedsInstallPermission`.
- [x] 5.2 Install through a `PackageInstaller` session (`MODE_FULL_INSTALL`, `setAppPackageName`, and `setRequireUserAction(USER_ACTION_REQUIRED)` on API 31+), with a non-exported status receiver and a mutable `PendingIntent`. `STATUS_PENDING_USER_ACTION` launches the confirmation, and the other statuses map to states. Verify:
  - a unit test asserts that the session params require user action on API 31+
  - a host test maps every status
  - `grep -rnE 'uninstall|pm clear|ACTION_INSTALL_PACKAGE|clearApplicationUserData' androidApp/src/main shared/src/commonMain` returns nothing from update code
- [x] 5.3 Write the pending-install record before committing the session, and report the outcome on the next start (design §7). Verify with host tests for "Updated to `<version>`" shown once and the record cleared, and for "Update didn't finish" with Retry when the code is lower and no session is active.

## 6. Data protection and isolation

- [x] 6.1 Add Room migration testing (`room3-testing` or the Room 3 equivalent) to a test that opens every schema in `shared/schemas` and migrates it to the current version with no destructive fallback and row counts preserved. It runs in the existing CI `build` job. Verify the test passes and that a temporary schema bump with no migration fails it (then revert).
- [x] 6.2 Add a host test that fails if update code depends on ThinQ, Firebase, the credential store, or the AC command port. Verify it passes, and fails with a temporary import (then revert).
- [x] 6.3 Document the updater in `docs/app-updates.md`: states, failure reasons, permissions, the recovery route over ADB with `release.sh --install`, and the acceptance checklist. Verify each state named in design §4 appears in the doc.

## 7. Acceptance on the owner's phone (owner-approved, after `release-pipeline`)

- [x] 7.1 Before building vN, run `./gradlew :shared:testAndroidHostTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest :androidApp:lintDebug` and the updater instrumented tests on both emulators. Verify that everything is green and record the counts in the PR.
- [ ] 7.2 Publish bootstrap vN through `release-pipeline` and install it with `release.sh --install <phone serial>` (the last ADB install). Verify the app reports up to date.
- [ ] 7.3 Publish vN+1 and update from inside the app: notice → Settings → Update → permission → confirm → "Updated to vN+1". Without sending any AC command, verify that sign-in, Room rows, preferences, the stored ThinQ token, all five tabs, charts and coverage, polling, and control gating are unchanged.
- [x] 7.4 Repeat the failure cases from design Migration Plan step 4 (offline, timeout, permission denied, cancel and retry, storage pressure, process death during download, large text, TalkBack). Record the results and limitations in the [go/no-go table on issue #4](https://github.com/cnhy-nero-diskard/AirchiveApp/issues/4#issuecomment-6772267605); the overall phone acceptance remains NO-GO pending 7.2 and 7.3.

## Workflow follow-up

- Resumable downloads (HTTP Range) and the final notice and section wording stay open (design Open Questions). Neither changes behaviour.
- Open the PR referencing #4, then sync and archive after merge.
