## Purpose

Keeps a durable, economical on-device view of Airchive history that stays useful offline and catches later upgrades to older observations.

## ADDED Requirements

### Requirement: History comes from owner-authorized read-only access
The app SHALL read only the configured AC's allowed Firestore paths with the signed-in owner's client identity. It SHALL issue no Firestore write and SHALL NOT carry server or service-account credentials. An authorization failure SHALL leave previously cached history visible with an explicit access error.

#### Scenario: Owner authorized
- **WHEN** the enrolled owner opens history with network access
- **THEN** the app SHALL fetch allowed device data and store a normalized local copy

#### Scenario: Read denied
- **WHEN** Firestore denies a read
- **THEN** the app SHALL show the denied state, retain existing cached history for that owner, and offer a retry

### Requirement: Initial synchronization is bounded
On a fresh owner-authorized installation, the app SHALL load at most the latest 24 hours of five-minute observations plus the latest 30 available finalized daily totals before declaring initial history ready. Collection queries SHALL respect the Airchive access contract's maximum page size of 250. Older observation ranges SHALL load only after an explicit user selection and then be cached.

#### Scenario: First successful history load
- **WHEN** the owner completes setup and history access is available
- **THEN** the app SHALL populate the initial bounded window and show when it last synchronized

#### Scenario: Older range requested
- **WHEN** the owner selects an uncached historical range
- **THEN** the app SHALL fetch it in bounded pages and retain it locally for later viewing

### Requirement: Refresh catches new and revised samples
The app SHALL refresh when opened or resumed after its last successful refresh is at least ten minutes old, and whenever the owner explicitly requests refresh. It SHALL query changes to both `persistedAt` and `reconciledAt` with boundary overlap, merge by `sampleId`, and advance its durable watermark only after all required pages and local writes succeed. It SHALL NOT continuously listen to Firestore or schedule background telemetry polling in the first release.

#### Scenario: Older sample is reconciled
- **WHEN** a previously cached sample receives a later `reconciledAt` update
- **THEN** the next successful refresh SHALL replace the cached projection of that sample

#### Scenario: Partial refresh failure
- **WHEN** any required page or local commit fails during refresh
- **THEN** the app SHALL retain the earlier successful watermark so a later retry can recover the missing changes

#### Scenario: Manual refresh
- **WHEN** the owner requests refresh before the ten-minute interval has elapsed
- **THEN** the app SHALL attempt a new bounded synchronization

### Requirement: Cached history remains honest offline
The app SHALL present cached history without network access, show the time and outcome of the last successful refresh, and distinguish missing or null energy intervals from measured zero. Cache contents SHALL be scoped to the signed-in owner, Firebase project, and device.

#### Scenario: Open while offline
- **WHEN** the app opens without connectivity after a previous successful sync
- **THEN** it SHALL show the cached data with an offline or stale label rather than an empty history

#### Scenario: No cached data while offline
- **WHEN** the app opens without connectivity before any successful history sync
- **THEN** it SHALL show an explanatory empty state and retry action
