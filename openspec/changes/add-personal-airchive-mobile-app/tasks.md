## 1. Establish the mobile project and contracts

- [x] 1.1 Scaffold Android and iOS app targets plus a shared Kotlin Multiplatform/Compose module; verify the Android debug app builds and the iOS project references the shared module.
- [x] 1.2 Add compatible Compose, Ktor, serialization, Room KMP, coroutine, and Android Firebase dependencies with a version catalog; verify dependency resolution and an Android clean build.
- [x] 1.3 Define shared interfaces for owner identity, telemetry reads, local storage, protected ThinQ credentials, and LG requests; verify fake implementations run the app without live accounts.
- [x] 1.4 Record the configured Firebase project, one monitored device ID, `Asia/Manila` day boundary, and the sibling Airchive read contract in nonsecret configuration; verify the debug build targets the intended project and contains no service-account key or PAT.

## 2. Build the local history and sync path

- [x] 2.1 Create typed models and fixture parsers for observations, daily totals, device identity, and collector health, including legacy missing units, null intervals, quality flags, and source failures; verify representative Airchive payload tests pass.
- [x] 2.2 Create a versioned Room KMP schema for normalized observations, finalized daily totals, covered ranges, and sync metadata keyed by project/UID/device; verify reopen and migration tests preserve data without storing raw payloads.
- [x] 2.3 Implement the Android Firebase Auth/Firestore read adapter with only the four permitted path shapes and pages of at most 250; verify emulator reads use the enrolled identity and the adapter exposes no write operation.
- [x] 2.4 Implement first-load synchronization for the latest 24 hours plus 30 available finalized daily totals; verify a 288-sample fixture paginates, reports progress, and stores no older five-minute samples before explicit selection.
- [x] 2.5 Implement on-demand historical range fetch and durable range coverage; verify revisiting a covered range reads Room without repeating the full Firestore query.
- [x] 2.6 Implement incremental `persistedAt` and `reconciledAt` queries with overlap, de-duplication, and server-timestamp high-water marks; verify a new sample, completeness upgrade, and older rollover patch each update the local row.
- [x] 2.7 Make both query streams and their Room upserts commit before watermarks advance; verify injected page and local-commit failures leave watermarks recoverable and a retry converges.
- [x] 2.8 Trigger refresh on app open/resume only after ten minutes and on manual request regardless of age, with no continuous listener or background worker; verify a counting fake observes those exact triggers.
- [x] 2.9 Show cached history with last-success and error age offline or after authorization failure; verify offline restart retains the prior owner’s rows and a fresh offline install shows an actionable empty state.

## 3. Implement owner and ThinQ onboarding

- [x] 3.1 Add Android Google sign-in and session restore using the existing Firebase project; verify a fresh sign-in yields a UID while denied Firestore access produces an access-pending screen rather than a crash.
- [x] 3.2 Build first-run steps for Google history access, a portal link and separate control-capable ThinQ PAT, editable country preset to `PH`, and initial history sync; verify progress and failure states with fake providers.
- [x] 3.3 Add Android Keystore-backed encrypted PAT storage and a stable per-install LG client ID; verify plaintext is absent from local history, logs, and backups/diagnostics, and a reopened app can use the stored token.
- [x] 3.4 Implement ThinQ token verification against the configured AC identity and profile, preserving the previous token on failed replacement; verify correct, wrong-account, invalid-token, and network-failure fixtures produce distinct outcomes.
- [x] 3.5 Add Settings replacement/removal, masked credential status, protected-key recovery, and confirmed sign-out/account change that clears owner-bound Room data and PAT; verify another Google account never sees the previous owner’s cache.

## 4. Implement live AC state and the four controls

- [x] 4.1 Build the narrow Ktor ThinQ client for list, profile, live state, and command requests using the official SDK’s routing/header/payload contract; verify sanitized request fixtures and error-code classification, including no-control-authority and rate-limit responses.
- [x] 4.2 Parse this AC’s profile into writable power, target-temperature, mode, and fan capabilities with allowed values and temperature bounds; verify unsupported or absent properties never produce enabled controls.
- [x] 4.3 Implement live state retrieval on Device entry and explicit refresh, keeping its timestamp separate from Firestore sample age; verify stale or failed LG reads never appear as current state.
- [x] 4.4 Implement one-at-a-time command submission and sending/accepted/confirmed/failed/unconfirmed states; verify duplicate taps and ambiguous timeouts do not resend commands automatically.
- [ ] 4.5 After an accepted command, perform a bounded fresh state check and confirm only matching state; verify mismatches, offline reads, and no-control-authority responses show actionable outcomes without changing telemetry records.
- [ ] 4.6 Validate each of the four permitted command types on the real AC through deliberate manual actions after a control-capable PAT is configured; record profile support, requested value, API result, and observed state, and remove or disable any unsupported option.

## 5. Build the full owner experience

- [ ] 5.1 Implement Home with cached AC/energy/collector summary, separate live and sampled timestamps, and a route to supported quick controls; verify cached content renders before network responses.
- [ ] 5.2 Implement Device with profile-derived power, temperature, mode, and fan controls plus pending/confirmed/error treatment; verify loading, unsupported, stale, and offline states on a narrow phone layout.
- [ ] 5.3 Implement Energy day/week/month views with `Asia/Manila` boundaries, interval gaps, coverage, recorded units, finalized daily totals, and interval-average power labels; verify null intervals never become zeros and finalized totals remain distinguishable from sampled sums.
- [ ] 5.4 Implement History/Health with observation status, quality explanations, source failures, collector health, and selected detail; verify anomalous and unavailable samples remain understandable without color alone.
- [ ] 5.5 Implement Settings with account, credentials, sync status, manual refresh, local-cache deletion, and secret-free diagnostics; verify destructive local actions require confirmation and do not write Firestore.
- [ ] 5.6 Review all five routes in representative ready, empty, offline, stale, denied, loading, and failure states with accessibility semantics and keyboard/screen-reader labels; verify the visible flows on an Android device or emulator.

## 6. Coordinate Airchive authorization and live data

- [ ] 6.1 Confirm the sibling `add-owner-mobile-telemetry-access` change matches this app’s queries, 250-document limit, allowed paths, and one-owner bootstrap; verify its rules tests and documented handoff before live integration.
- [ ] 6.2 Sign in with the private Android build, verify the exact owner UID and AC device ID, and coordinate their deliberate enrollment in Airchive rules; verify reads are denied before enrollment and succeed for the owner afterward.
- [ ] 6.3 Run the first live 24-hour/30-day sync and a later incremental sync; verify document counts, cache speed, authorization denial for another identity, client-write denial, and no regression to the collector/dashboard server access.
- [ ] 6.4 Test app restart offline, an old-sample reconciliation or emulator equivalent, account sign-out cleanup, and direct ThinQ controls while Firestore is unavailable; record actual device versus emulator evidence separately.

## 7. Deliver and verify the private release

- [ ] 7.1 Add a signed private APK build with signing material kept outside Git and register its certificate for Google sign-in; verify an installable release APK and a secret scan of tracked files.
- [ ] 7.2 Install the release APK on the target Android device and upgrade it with the same signing identity; verify sign-in, preserved compatible Room data and token, navigation, and all four validated controls.
- [ ] 7.3 Complete the iOS project scaffold and platform adapter interfaces, documenting Mac-side Firebase, Keychain, build, and device-verification steps; verify project wiring from files and state plainly that iOS execution was not tested on Windows.
- [ ] 7.4 Run shared/Android tests, lint, Android build, `openspec validate add-personal-airchive-mobile-app --strict`, and `git diff --check`; record passed checks, live-device evidence, read volume, and any remaining environment-limited checks in the handoff.
