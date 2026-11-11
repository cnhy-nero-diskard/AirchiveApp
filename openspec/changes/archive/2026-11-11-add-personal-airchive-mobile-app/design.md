## Context

See `proposal.md` for motivation and the four delta specs for behavior. This repository has no application code yet. The sibling Airchive repository polls one LG AC every five minutes into `devices/{deviceId}/telemetry`, caches finalized `dailyTotals`, and writes `runtime/collector`. Its local dashboard already defines quality-aware energy presentation and a dual-`persistedAt`/`reconciledAt` refresh contract. The collector's PAT has read scopes only, and its Firestore rules currently deny all mobile reads. The sibling `add-owner-mobile-telemetry-access` change owns the exact owner/device rules, maximum 250-document page, Firebase enrollment, and deployment.

## Goals / Non-Goals

**Goals:**

- Make the cached Home route fast even when Firestore or LG is slow, and keep live AC status visibly separate from sampled history.
- Put all reusable domain, sync, command, and UI behavior in Kotlin Multiplatform code while keeping platform-specific identity, Firestore, and secret storage at narrow boundaries.
- Preserve the collector's existing schema and write path and enforce the read-only client boundary.
- Ship a privately upgradable Android APK with an iOS project already connected to shared code.

**Non-Goals:**

- Turning the collector's private `POST /poll` endpoint into an app API, placing its server credentials on a phone, or editing collector write logic from this repository.
- Device pairing, a household account system, automations, push notifications, background telemetry polling, or unsupported ThinQ controls.
- Shipping or claiming real-device validation of the iOS app from a Windows development host.

## Decisions

### D1: Shared mobile app with platform adapters

Use Compose Multiplatform for the shared five-route interface and Kotlin common code for models, command state, presentation, synchronization, and tests. Use Ktor with platform engines for the narrow ThinQ HTTP protocol. Use Room KMP for the normalized local history, coverage, and sync metadata. Android `actual` adapters use Firebase Auth/Firestore and an Android Keystore-backed encrypted token store; the iOS scaffold links the shared code and declares equivalent Auth/Firestore/Keychain adapters for later Mac-side integration. Keep provider APIs behind small interfaces so mocked data can run the complete Android flow before live enrollment.

Sharing only business logic with two independent native UIs would duplicate a substantial greenfield UI. A third-party cross-platform Firebase wrapper would make this private app depend on an additional compatibility layer when the platform SDKs already exist. A Python companion service would add deployment and a second auth boundary despite the owner's direct-client preference.

### D2: Owner identity and first-run bootstrap

Register the signed Android application in the existing Firebase project and use Google sign-in. The app never grants its own Firestore access: it can show the signed-in UID and an access-pending state while the operator enrolls that exact UID in the sibling repository's rules. Firebase client configuration is packaged with the app; no service-account JSON, collector PAT, or owner token is embedded in source or APK build configuration. Recheck access after the rules deployment without forcing token re-entry.

Onboarding then links to LG's PAT portal, asks for a separate control-capable PAT and the account country (prefilled `PH`, editable), generates a stable per-install client UUID, and verifies the target AC's identity and profile. The configured collector device ID is a nonsecret app configuration value, not a user-selected arbitrary Firestore path. The app reports that a successful read check does not prove command authority; it classifies LG's no-control-authority result on the first actual command. A failed replacement leaves the last valid token intact. Credential editing lives in Settings. Confirmed sign-out/account change deletes the local history and ThinQ token before another account is shown.

Exact UID enrollment is a one-time operator action in Airchive, as its design states. Self-enrollment from the app would defeat the owner-only rule. Email-based rules and an allowlist lookup add identity drift or extra authorization reads for no gain with one owner.

### D3: Narrow, profile-driven ThinQ client

Implement only device list, profile, current state, and control endpoints needed by the four controls. Match request headers, country-to-region routing, stable client ID, payload shape, and error codes against the official Python SDK already used by the collector; use sanitized fixtures and tests, not a copied token or exception object. Do not assume every SDK AC setter is accepted by this model. Read the actual profile to derive writable values, numeric bounds, and units. The device's observed state establishes which properties exist; a safe, deliberate real-device verification gate establishes whether each of the four command paths actually works.

Serialize commands per device. Disable repeated submit while one is pending. Model outcome as sending, accepted, confirmed, failed, or unconfirmed; after acceptance, make a bounded fresh state read and confirm only a matching value. A timeout after send is ambiguous, so the app reads state and asks the owner before any resend. Never write a command result into the collector's historical telemetry. When Firebase is unavailable, the ThinQ path can still work from its local PAT; when ThinQ is unavailable, cached history still works.

### D4: Bounded Firestore-to-Room synchronization

The first successful sync fetches the latest 24 hours of telemetry and the latest 30 available finalized daily-total documents, using pages of at most 250 and only the four path shapes authorized by the Airchive change. Older five-minute history is fetched only for an explicit selected window. Whole Firestore documents may include `raw`; normalize only display and sync fields into Room and discard raw payloads after parsing. Partition records and sync state by Firebase project, owner UID, and device ID.

Render Room immediately. On app open/resume, refresh if the last successful sync is at least ten minutes old; manual refresh bypasses that threshold. Do not install a continuous snapshot listener or background worker. For incremental refresh, query both server-managed mutation fields `persistedAt` and `reconciledAt` from overlapped durable high-water marks, page each result, and upsert by `sampleId`. Advance a high-water mark from successfully observed server timestamps only after both query streams and the Room transaction complete. This avoids a fast handset clock skipping server writes and catches older rollover patches. Keep range coverage separate from mutation freshness so an uncached historical selection is fetched even if the incremental watermark is current. Failed refreshes leave prior cache and watermarks usable and show their age and error.

Firestore's own mobile offline cache can assist network access, but it does not replace the explicit normalized Room projection, coverage tracking, and quality-aware queries. Repeated full-window fetches would cost more reads and slow navigation. No aggregate document writes are added to the collector in this first pass.

### D5: Two data clocks and honest energy views

Home shows cached collector age and summary immediately, then independently fetches live ThinQ state if credentials and connectivity permit. Device controls use only the fresh ThinQ profile/state. Energy uses `Asia/Manila` local-day boundaries, recorded Wh/kWh provenance, null interval gaps, observed/expected coverage, and finalized daily totals with their own label. An interval-value divided by its duration is labeled interval-average power; there is no live wattage sensor. History/Health shows quality statuses, source failures, collector health, and selected observation detail, with technical raw payloads omitted from routine navigation. Settings exposes sync age, credential replacement, sign-out/cache deletion, and diagnostics without secrets.

This carries the local dashboard's domain meaning into a phone layout while keeping the AC action in the foreground. The visual language can take broad layout cues from ThinQ without copying LG artwork or implying unsupported features.

### D6: Private delivery and validation boundary

Keep release signing material outside Git, document key backup and a repeatable signed-APK build, and verify an in-place APK update preserves a compatible Room database and token. Register the release signing certificate for Google sign-in. The iOS Xcode project is scaffolded and wired to the shared module; Android can be built and device-tested on this Windows host, while iOS build/sign-in/Keychain verification is a later Mac-side gate and is reported as such.

Use fake LG responses, Firestore emulator/rules fixtures, and local database tests for error states, data quality, reconciliation, and command state. Then install the private APK and verify owner sign-in, bounded reads, offline restart, denied client write, and the four permitted control commands against the actual AC. No automated test should actuate the household device without a deliberate manual step.

### D7: Deliver real stored history before enabling ThinQ controls

The owner authorized replacing the Android demo screens with the configured AC's real stored history on 2026-10-02. The sibling access rollout is complete: Google sign-in and persisted UID were verified, the required root identity was initialized with explicit approval, and exact-owner read-only rules were deployed and checked on the phone. Implement this as a history-only integration milestone: wire Firebase reads, owner/project/device-scoped Room storage, bootstrap, incremental/manual refresh, and the visible history/energy/health routes together. Missing Firebase setup or access must not silently fall back to fixture data. Display cached sampled data as history, never as live ThinQ state or evidence of current device connectivity.

Do not require a ThinQ PAT to read history. Until real protected credential and validated live-control composition is separately integrated, the production UI must not accept PATs or enable simulated commands. Keep fixture providers available only in explicitly demo/test contexts. The full ThinQ, release, and offline/control acceptance requirements remain in scope for later tasks; this milestone does not mark those tasks complete.

## Risks / Trade-offs

- [Direct authorized reads return whole telemetry documents, including `raw`] -> Restrict to one owner and one device, fetch only bounded pages, retain only normalized local fields, and record read volume during acceptance.
- [The collector token lacks control permission or a profile lists a command the model rejects] -> Use a separate PAT and a per-control real-device gate; omit unsupported controls and show an actionable token error.
- [A timed-out command may already have taken effect] -> Read fresh state, show unconfirmed status, and never auto-resend an ambiguous command.
- [An older observation changes after it was cached] -> Query both mutation timestamps with overlap, upsert by ID, and commit high-water marks only after a full successful sync.
- [Firestore access is granted before the APK is ready or to the wrong UID] -> Keep deny-all until signed-in UID and device ID are verified in the sibling change; run emulator rules tests and owner/unauthorized smoke tests before rollout.
- [Offline cache survives server-side revocation] -> Explain that revocation stops future reads and make sign-out/account change clear local data; handset loss is handled through platform lock and token revocation.
- [No Mac is available for iOS compilation] -> Deliver a linked scaffold and report iOS execution as unverified rather than claiming both platforms passed.
- [Private APK signing key is lost] -> Document off-repository key backup; verify upgrade with the same signing identity before handoff.

## Migration Plan

1. Scaffold the KMP/Compose app, Room schema, platform ports, fake-provider flows, and signed Android build. Keep Firebase access denied while the UI and sync logic are tested locally.
2. Register the Android app and Google provider in the existing Firebase project; sign in once to obtain and verify the owner UID. Apply the sibling Airchive rules change after its emulator checks and operator review.
3. Run a bounded live first sync and test offline/restart and incremental reconciliation. Verify authorized and unauthorized Firestore paths and no client write access.
4. Create a separate control-capable ThinQ PAT through LG's portal. Verify profile-gated commands on the real AC one deliberate action at a time, then verify live-state confirmation and failure handling.
5. Build a signed private APK, install and upgrade it on the target Android device, record actual checks and query volume, and hand off the iOS scaffold with its Mac validation boundary.

Rollback: redeploy Airchive's deny-all client rules to stop new phone reads; collector and dashboard continue unchanged. Install the prior Android APK signed with the same key if an app regression requires it, subject to compatible local schema; otherwise clear and rebuild the disposable local history from Firestore after the fixed app is installed. Revoke the app PAT independently at LG if control access must be removed.
