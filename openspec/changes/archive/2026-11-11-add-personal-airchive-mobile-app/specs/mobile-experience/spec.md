## Purpose

Defines a complete personal mobile experience for operating one AC and understanding its energy history, quality, and collector health.

## ADDED Requirements

### Requirement: Main routes cover the owner's everyday tasks
The app SHALL provide Home, Device, Energy, History/Health, and Settings destinations. Home SHALL lead with the AC and an actionable summary; Device SHALL expose live state and supported controls; Energy SHALL show usage over time; History/Health SHALL explain observations and collector quality; Settings SHALL manage credentials, refresh, and local data. Each destination SHALL handle loading, empty, offline, stale, denied, and error states relevant to it.

#### Scenario: Ready owner opens the app
- **WHEN** an onboarded owner opens the app with cached data
- **THEN** Home SHALL render the cached summary promptly while eligible network refresh runs separately

#### Scenario: Owner needs diagnostic detail
- **WHEN** the owner opens History/Health
- **THEN** the app SHALL expose sample status, gaps, source failures, collector health, and last-sync context in readable terms

### Requirement: Energy presentation preserves measurement meaning
The app SHALL use the AC's `Asia/Manila` day boundary for daily energy, label the five-minute interval values as sampled energy, and label any energy-per-time conversion as interval-average power. It SHALL show null intervals as gaps, flag incomplete coverage, distinguish finalized LG daily totals from sums of stored intervals, and preserve the recorded unit and source. It SHALL NOT display a five-minute average as instantaneous wattage.

#### Scenario: Missing interval in a selected range
- **WHEN** a stored interval lacks a usable value
- **THEN** the chart and total SHALL mark missing coverage rather than count it as measured zero

#### Scenario: Finalized total differs from interval sum
- **WHEN** LG's finalized daily total and the sum of stored intervals differ
- **THEN** the app SHALL label the two measures separately and show coverage context

### Requirement: Status is understandable and accessible
The app SHALL explain normal, anomalous, unavailable, stale, and failed conditions with text and accessible semantics as well as visual treatment. Technical payloads and diagnostics SHALL remain available through secondary detail rather than dominate the main task surfaces.

#### Scenario: Non-normal observation
- **WHEN** an observation carries a non-normal interval status or quality flag
- **THEN** the UI SHALL expose a readable explanation that does not rely on color alone

### Requirement: Private Android release and iOS preparation
The first release SHALL produce a signed Android APK that can be installed privately and updated without clearing local data. The project SHALL contain shared Kotlin Multiplatform UI/domain code and an iOS app scaffold wired to that shared code; iOS distribution and device validation are outside this first release.

#### Scenario: Upgrade private APK
- **WHEN** the owner installs a newer release signed with the same release identity
- **THEN** the app SHALL retain compatible local history and protected settings

#### Scenario: Open iOS project
- **WHEN** a developer opens the iOS scaffold on a supported Mac
- **THEN** it SHALL reference the shared app code and identify the remaining platform setup needed for iOS execution
