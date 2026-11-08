# Design

## Context

See proposal.md for motivation.

**Current state of `main` at `f07764e`:**
- The app shows its version nowhere.
- The manifest declares only `INTERNET`.
- `minSdk` is 26 and `targetSdk` is 36.
- The owner's phone runs release code 2, which was installed over ADB, so the phone records no installing app ("installer of record").
- Settings UI is shared Compose (`SettingsRoute.kt`, `OwnerSettingsCard.kt`).
- Platform features are injected into `ProductionAirchiveApp` as nullable bindings (for example `thinQBindings: ProductionThinQBindings?`).
- `MainActivity.onResume` (`MainActivity.kt:111`) starts the foreground history and device refresh, each with its own `ForegroundRefreshBackoff`.
- Ktor CIO and `ktor-client-mock` are already dependencies.
- Room exports schemas to `shared/schemas`, and only `2.json` exists.

**The feed and manifest format come from `release-pipeline`** (design §7). Owner decisions from that exploration:

| ID | Decision |
|---|---|
| D1 | Public GitHub Releases. |
| D3 | There is no separate manifest-signing key. |
| D5 | There are no forced updates. |

## Goals / Non-Goals

**Goals:**
- No update path can install anything the owner did not confirm, or anything not signed by the pinned key.
- A broken feed, missing network, or failed install never affects Home, history, or AC controls.
- The updater in the first build that carries it is correct the first time. It is the one component that cannot repair itself, so a bug in it can only be fixed over ADB.

**Non-Goals:**
- Forced updates and blocking prompts (D5).
- Google Play In-App Updates.
- Beta channels.
- Background downloads that survive the app process being killed (no foreground service or WorkManager in v1).
- Resumable downloads (see Open Questions).
- iOS. It reports "unavailable".

## Decisions

### 1. Shared logic, Android implementation, optional binding

- **`commonMain`** holds:
  - the manifest model and a strict parser for `schemaVersion: 1`
  - version comparison
  - the update state machine
  - the Settings section and Home notice
- **`ProductionAirchiveApp`** receives a nullable `AppUpdateBindings`. It is `null` on iOS and in debug builds, where the UI shows "In-app updates apply to release builds".
- **Android** implements the fetch, download, archive inspection, and PackageInstaller steps behind the binding.

This keeps the rules for deciding what is acceptable in host tests, which is the existing pattern behind the 383 host tests.

### 2. Its own HTTP client, with HTTPS and a host rule

The updater uses a dedicated Ktor CIO client:
- no auth plugins and no cookies
- User-Agent `Airchive/<versionName>`
- never the ThinQ client, which carries the token, and never Firebase

It enforces:
- HTTPS only
- at most 5 redirects
- hosts `github.com` and `*.githubusercontent.com` only
- a 64 KiB cap on the manifest
- 15 s connect and 30 s request timeouts for the manifest

The host rule is deliberately a suffix match rather than one exact asset host. Integrity comes from the checks in §5, so the host rule is defence in depth. Pinning one exact redirect host would let GitHub break the updater, and fixing that would need an ADB install.

### 3. When to check

- **On resume:** `onResume` starts an update check when the last successful check is at least 6 hours old. It uses its own `ForegroundRefreshBackoff` instance and job, cancelled in `onPause` like the others. A failure never touches the history or device refresh state.
- **Explicitly:** Settings "Check for updates" always checks.
- **Never:** opening the Settings section never checks, which matches the existing rule that opening a disclosure issues no request.

The check time, a version the owner dismissed, and any pending install are stored in the installation-wide private preferences that already hold Interface detail. Updates belong to the installation, not the owner, so sign-out leaves them in place.

### 4. Update states

```
 Idle --> Checking --+--> UpToDate
                     +--> Unavailable(offline | feed error | unknown schema | debug/iOS)
                     +--> Available(manifest)
 Available --tap Update--> Downloading(progress) --cancel--> Available
 Downloading --> Verifying --fail--> Failed(reason)   [file deleted]
 Verifying --ok--> ReadyToInstall
 ReadyToInstall --no permission--> NeedsInstallPermission --return--> ReadyToInstall
 ReadyToInstall --> Installing --+--> [process replaced by new version]
                                 +--> Failed(reason) | Cancelled --> ReadyToInstall
 next launch with pending record --> Updated(version) | NotCompleted --> Available
```

- **The Home notice** appears for `Available` when the version was not dismissed. It sits in a reserved slot outside the instrument panel, waits while a temperature draft, confirmation, or pending command is open, and opens the Settings section when tapped.
- **Simple vs Detailed:** Detailed adds the version code, commit, SHA-256, and feed URL. Both modes show the same states, errors, and actions.

### 5. Download and checks before install

- The download starts only when the owner taps Update.
- It runs in an app-scoped coroutine, so it survives rotation and leaving the screen, but not the process being killed.
- Before starting, the app compares free space with `apk.size` plus headroom.
- The download streams to `cacheDir/updates/<versionCode>.apk.part` and computes SHA-256 as it goes. It aborts the moment it exceeds `apk.size` or a ceiling of 200 MB.
- On completion it is renamed and checked:
  - the size and SHA-256 match the manifest
  - `getPackageArchiveInfo` (signing certificates on API 28+, signatures on API 26–27) reports package `cloud.airchive.personal`
  - `longVersionCode` is higher than the installed version and equal to the manifest's
  - there is exactly one signer, whose SHA-256 equals the compiled-in pin
  - the APK is not debuggable
- A manifest whose `signingCertSha256` differs from the pin is rejected before downloading.
- Any failure deletes the file and shows a specific reason.
- Leftover `.part` files are deleted on startup.
- `DownloadManager` is not used: it writes outside app-private storage and gives up control of the checks.

### 6. Installation with the owner's confirmation

- `REQUEST_INSTALL_PACKAGES` is added.
- If `canRequestPackageInstalls()` is false, the app explains why and opens `ACTION_MANAGE_UNKNOWN_APP_SOURCES` for its own package, then checks again on return.
- Installation uses a `PackageInstaller` session (`MODE_FULL_INSTALL`, `setAppPackageName`). On API 31+ the session always sets `setRequireUserAction(USER_ACTION_REQUIRED)`.
  - After the first in-app update, Airchive becomes the phone's recorded installer, which on Android 12+ would allow updates without asking.
  - A unit test pins the requirement.
- The commit status goes to a non-exported receiver through a mutable `PendingIntent`. `STATUS_PENDING_USER_ACTION` launches the system confirmation dialog, and the other statuses map to states (aborted, conflict, incompatible, storage, blocked).
- `ACTION_INSTALL_PACKAGE` is not used; Android 10 (API 29) deprecated it.
- No code path uninstalls the app, clears data, uses ADB or root, or touches Room or credential stores.

### 7. Outcome after restart

Installing replaces the process, so the success callback may never arrive. Before committing the session, the app stores a pending record `{targetVersionCode, startedAt}`. On the next launch:
- If the installed code is at least the target, it shows "Updated to `<versionName>`" once and clears the record.
- If the code is lower and no session is active, it shows "Update didn't finish" with Retry.

### 8. Protecting data across updates

A Room migration test runs from every exported schema (starting at `2.json`, the current version) to the current version. It runs in CI, so a release that would discard data cannot pass. The migration test cannot cover other preserved state:
- Firebase Auth sessions
- preferences
- the Keystore-protected ThinQ token

The real-phone acceptance run covers that, comparing before and after an in-app update.

### 9. Testing without the real key

- **Host tests** use a fake feed on Ktor MockEngine to cover:
  - parsing, unknown schema, oversized or malformed input
  - versions that are lower or equal, and a wrong package
  - certificate mismatch in the manifest
  - offline, timeout, and 404
  - backoff, dismissal, and state transitions
- **Instrumented tests on the emulator** use APKs signed with a throwaway test key and a pin that only test builds can inject. They cover hash and certificate mismatch, cancel, and the pending-record outcome.
- **The real-key path** is exercised only in the acceptance run.

## Risks / Trade-offs

- **A bug in vN's updater can only be fixed over ADB.** → The host and instrumented coverage in §9 must pass before vN is built. The `release.sh --install` path stays as the recovery route.
- **Reading signer certificates from an archive behaves differently across API levels.** → Instrumented tests on API 26–27 and 28+ images, using the test-key APKs.
- **The owner revokes "Install unknown apps", or an OEM restricts it.** → The `NeedsInstallPermission` state, with an explanation and a link to the setting. The app never falls back to anything else.
- **Storage pressure, or the process being killed during download.** → The pre-check, IO errors mapped to Failed, and `.part` cleanup at startup.
- **Release notes as untrusted text.** → Shown as plain text, never HTML or links, and length-capped by the parser.
- **A frozen feed or withheld updates.** → Accepted (D3). The owner publishes their own releases and would notice.
- **Data use.** → Checks fetch at most 64 KiB, at most every 6 hours. Downloads only happen on request and show their size first.

## Migration Plan

1. Land the change. Debug builds keep the updater disabled.
2. With `release-pipeline`, build and publish vN, the first release that carries the updater. Install it on the phone through `release.sh --install`; this is the last ADB install. Confirm it reports up to date.
3. Publish vN+1. On the phone:
   - resume the app and see the notice, then go to Settings → Update
   - complete the permission and install flow
   - after the restart, confirm "Updated to vN+1"
   - check sign-in, Room rows, preferences, the masked or present ThinQ token, all five tabs, charts and coverage, polling, and control gating

   Send no AC command.
4. Repeat the failure cases on the phone or emulator:
   - offline and timeout
   - permission denied
   - install cancelled, then retried
   - storage pressure
   - process death during download
   - large text and TalkBack across the new UI
5. Record the results in the go/no-go table on issue #4.

**Rollback:**
- A bad vN+1 is fixed by publishing vN+2.
- A broken updater in vN is fixed by an ADB install from the Mac.

## Open Questions

- Resumable downloads using HTTP Range, if APK size or connection quality makes restarting painful.
- The exact text of the notice and the Settings section. This is settled in UI review and does not change behaviour.
