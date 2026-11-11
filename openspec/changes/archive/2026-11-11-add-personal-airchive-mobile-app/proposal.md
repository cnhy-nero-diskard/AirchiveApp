## Why

Airchive records useful five-minute AC telemetry, but its only UI is a local, read-only dashboard. A personal mobile app should make that history useful beside live device status and everyday controls, while keeping startup fast and Firestore reads bounded.

## What Changes

- Build a private, Android-first Kotlin Multiplatform app for the one LG air conditioner already monitored by Airchive, with shared UI/domain code and an iOS project scaffold.
- Guide the owner through Google sign-in, a separate control-capable ThinQ token, AC verification, and initial history download; keep credentials editable in Settings.
- Read this device's Airchive history directly through owner-scoped Firestore access and maintain an on-device database. Download the latest 24 hours of observations and 30 finalized daily totals initially, then refresh on app open/resume or explicit request.
- Show a complete Home, Device, Energy, History/Health, and Settings experience. Preserve data-quality and timestamp distinctions between sampled telemetry and live ThinQ state.
- Control power, target temperature, mode, and fan speed only when the actual device profile permits them; confirm outcomes with a fresh live state read.
- Produce a signed, installable private Android APK and a reproducible build and verification handoff.

## Capabilities

### New Capabilities

- `owner-onboarding`: First-run identity, ThinQ credential setup, device verification, and repeatable account management.
- `telemetry-sync`: Bounded owner-authorized Firestore reads, durable local history, incremental synchronization, and offline viewing.
- `ac-control`: Live AC state and profile-gated power, temperature, mode, and fan commands.
- `mobile-experience`: The app's navigation, energy/history presentation, quality semantics, and private Android delivery with iOS scaffolding.

### Modified Capabilities

None. This repository has no existing product specs.

## Impact

- Adds the Android/Kotlin Multiplatform project, platform Firebase adapters, ThinQ HTTP client, local database, UI, tests, and private release instructions in this repository.
- Depends on the sibling Airchive change `add-owner-mobile-telemetry-access` to register the Android client and enable exact owner/device read rules. Its collector, existing token, private poll service, and telemetry schema remain under that repository's ownership.
- Requires one-time enrollment of the owner's Firebase UID and a separate LG PAT with device-control permission. No service-account key is shipped to the phone.
