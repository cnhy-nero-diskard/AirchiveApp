# Mobile Experience Specification

## Purpose

Defines a complete personal mobile experience for operating one AC and understanding its energy history, quality, and collector health.

## Requirements

### Requirement: Main routes cover the owner's everyday tasks
The app SHALL provide Home, Device, Energy, History/Health, and Settings destinations. Home SHALL lead with the AC and an actionable summary; Device SHALL expose live state and supported controls; Energy SHALL show usage over time; History/Health SHALL explain observations and collector quality; Settings SHALL manage credentials, refresh, local data, and the app version and updates. Each destination SHALL handle loading, empty, offline, stale, denied, and error states relevant to it.

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
The first release SHALL produce a signed Android APK that can be installed privately and updated without clearing local data.

#### Scenario: Private distribution and platform boundary
- **WHEN** the signed first-release APK is distributed or the iOS scaffold is inspected
- **THEN** "Installed privately" means installed directly, outside any app store; release APKs MAY be publicly downloadable from the repository's GitHub Releases, signed with the pinned Airchive release identity. The project SHALL contain shared Kotlin Multiplatform UI/domain code and an iOS app scaffold wired to that shared code; iOS distribution and device validation are outside this first release.

#### Scenario: Upgrade private APK
- **WHEN** the owner installs a newer release signed with the same release identity
- **THEN** the app SHALL retain compatible local history and protected settings

#### Scenario: Open iOS project
- **WHEN** a developer opens the iOS scaffold on a supported Mac
- **THEN** it SHALL reference the shared app code and identify the remaining platform setup needed for iOS execution

### Requirement: Accepted native instrument identity spans the five production routes
The app SHALL preserve the accepted native Home Instrument identity across Home, Device, Energy, History, and Settings.

#### Scenario: Instrument content preserves controls and measurement meaning
- **WHEN** the owner opens Home or Device
- **THEN** Home SHALL present one continuous panel that pairs comfort and recorded energy with equal emphasis: a setpoint with local editing that is sent after a one-second pause when control authority permits, separate supported mode and fan controls, and an energy chart with nearby coverage and latest usable sample age. Yesterday's finalized LG total, when available, and history-sync context SHALL be labeled separately from today's sampled subtotal and the live device read. Device SHALL reuse the Home comfort instrument rather than introduce a second control language. Missing readings SHALL use an em dash and honest setup/unavailable context, never invented zero or apparent connection.

#### Scenario: Owner opens Home
- **WHEN** an onboarded owner opens Home with cached data and verified live control authority
- **THEN** one continuous panel SHALL give comfort and recorded energy equal prominence with explicit controls, nearby chart/coverage/sample age, and separately labeled finalized-total and history-sync context

#### Scenario: Owner opens Device
- **WHEN** the owner navigates to Device
- **THEN** the comfort reading SHALL reuse the Home instrument composition, including the setpoint with its paused send and separate mode and fan controls subject to the same authority gates

#### Scenario: Live access is missing while history is cached
- **WHEN** history is available but there is no verified live device reading
- **THEN** cached recorded energy SHALL remain available, comfort SHALL show honest missing/unavailable context, and control authority SHALL NOT be inferred from that history

### Requirement: Readings stack instead of clipping at narrow widths and enlarged text
When the available width for the paired readings is less than 300dp or the system text scale exceeds 1.2, the app SHALL stack the comfort and recorded-energy readings rather than squeezing them side by side, retaining equal reading treatment and a horizontal separator. The app SHALL NOT clip text or shrink the type hierarchy to fit; interactive targets SHALL keep a 48dp minimum size and disclosure rows SHALL keep a 56dp minimum height.

#### Scenario: Narrow instrument panel
- **WHEN** the available paired-reading field measures less than 300dp wide
- **THEN** the two readings SHALL stack vertically at equal width with all essential text fully visible

#### Scenario: Enlarged system text
- **WHEN** the system text scale exceeds 1.2
- **THEN** the readings SHALL stack and wrap without clipping and without reducing the reading hierarchy

### Requirement: The shell keeps native navigation and window-safe behavior
In landscape windows at least 600 dp wide the app SHALL use a compact side rail for the five destinations; narrower or portrait windows SHALL use bottom navigation.

#### Scenario: Navigation retains independent scrolling and window safety
- **WHEN** the owner navigates or edits a field in a supported window or text scale
- **THEN** Navigation SHALL sit outside independently scrolling content and retain visible or scroll-reachable labels. The app SHALL respect system insets, keep on-screen actions reachable while the keyboard is shown, and let Back dismiss the keyboard before leaving the current route when the IME is open. Phone landscape and the maximum supported text scale SHALL be supported, testable states of every route. Where available width and text scale permit, Home SHALL place controls and the hourly chart in separate columns while preserving reading order and accessible controls.

#### Scenario: Rotate to landscape
- **WHEN** the phone rotates to landscape on any of the five routes
- **THEN** navigation labels, current readings, and actions SHALL remain visible or reachable without overlap and the route SHALL still scroll

#### Scenario: Back with the keyboard open
- **WHEN** the owner presses Back while the keyboard is visible
- **THEN** the keyboard SHALL close first without discarding a local draft or navigating away

#### Scenario: Wide landscape instrument
- **WHEN** the landscape content has enough width for both columns at the current text scale
- **THEN** controls and the energy chart SHALL use side-by-side columns with controls first in semantic order

#### Scenario: Enlarged text or narrow window
- **WHEN** the available content is too narrow or text is enlarged
- **THEN** instrument sections SHALL stack and remain scrollable without clipping

### Requirement: Secondary routes reuse the instrument blocks without autonomous requests
Opening a disclosure SHALL NOT trigger a device read, a telemetry fetch, or an AC command.

#### Scenario: Foreground refresh stays independent of disclosures
- **WHEN** a scheduled live-state read becomes due while the app is foregrounded
- **THEN** The foreground scheduler MAY refresh a verified device state independently of disclosure or route selection, with a five-minute baseline and bounded exponential backoff on failure. Scheduled reads SHALL share the command exclusion slot, use no additional profile reads, stop in the background, and be cancelled or fenced on owner or credential changes. Settings SHALL present Interface detail prominently and SHALL keep recovery actions for account, credential, and local-data flows reachable.

#### Scenario: Device details disclosure
- **WHEN** the owner expands Device details
- **THEN** the disclosure SHALL NOT initiate a device read and the existing state SHALL keep its own observation time

#### Scenario: Settings action recovery
- **WHEN** the owner expands an account or connection section in Settings
- **THEN** the section SHALL expose its recovery or management actions without issuing a request merely from being opened

#### Scenario: Scheduled read encounters a pending command
- **WHEN** the foreground device refresh is due while a command occupies the device slot
- **THEN** the read SHALL be deferred and SHALL NOT send or retry a command

#### Scenario: Device refresh fails
- **WHEN** a scheduled device read fails or is rate-limited
- **THEN** the app SHALL label the retained state stale or unavailable and delay subsequent attempts with bounded exponential backoff

### Requirement: Energy period changes are presentation-only and downloads stay explicit
The Energy route SHALL immediately present cached data for the selected day, week or month and automatically request missing elapsed ranges when owner-authorized. Requests SHALL be limited to the selected period, with no adjacent-period prefetch, reuse coverage, avoid duplicate concurrent reads, and expose progress, failure and retry. Offline cache SHALL remain visible. Future intervals SHALL NOT be fetched.

#### Scenario: Switch to an uncached period
- **WHEN** the owner selects an authorized uncached period
- **THEN** the route SHALL show cached data immediately and request only missing elapsed portions of that period

#### Scenario: Explicit download
- **WHEN** the owner retries loading the selected period
- **THEN** only missing elapsed data SHALL be requested and progress SHALL be labeled

#### Scenario: Offline or denied
- **WHEN** a selected-range request fails or access is unavailable
- **THEN** cached data SHALL remain visible with an actionable retry or access recovery state

### Requirement: History ledger stays virtualized, selectable, and newest-first
The History ledger SHALL show newest-first hourly groups in a virtualized list, retain saved count, sync context and refresh, and provide accessible drill-down to individual observations and their quality evidence.

#### Scenario: Large local history
- **WHEN** the cache holds many observations
- **THEN** hourly groups SHALL scroll responsively newest first

#### Scenario: Select an observation
- **WHEN** the owner opens an hour and selects an observation
- **THEN** its detail SHALL remain available

### Requirement: Interface detail defaults to Simple and persists installation-wide
Interface detail SHALL offer Simple and Detailed, with Simple selected when no value is saved or the saved value is unrecognized. The choice SHALL be stored in private installation-wide preferences that survive sign-out, owner history and cache removal, and ThinQ credential removal, and SHALL NOT be treated as owner data. An in-memory fallback used where no platform adapter exists SHALL NOT promise persistence across app instances.

#### Scenario: Fresh installation
- **WHEN** the app has never recorded a choice
- **THEN** Simple SHALL be selected

#### Scenario: Sign-out or cache removal
- **WHEN** the owner signs out or clears cached history
- **THEN** the saved Interface detail choice SHALL remain in effect for the next session

### Requirement: Interface detail applies immediately and sets disclosure defaults
A new choice SHALL take effect immediately across Home, Device, Energy, History, and Settings without an app restart.

#### Scenario: Detail changes reset disclosures without hiding recovery
- **WHEN** the owner changes Interface detail while a route or recovery flow is open
- **THEN** Informational sections SHALL start collapsed in Simple and expanded in Detailed; task and action sections, including Account actions, SHALL start collapsed in both modes. The owner SHALL be able to override disclosures manually, and those overrides SHALL reset to the selected mode's defaults whenever the mode changes. State-required recovery sections, such as unverified history access, SHALL default to expanded in either mode and after a mode change, while retaining manual disclosure control.

#### Scenario: Change the choice
- **WHEN** the owner selects Detailed in Settings
- **THEN** all five routes SHALL present Detailed copy and Detailed disclosure defaults immediately, without a restart

#### Scenario: Manual override followed by a mode change
- **WHEN** the owner manually collapses an informational section and opens Account actions in Detailed, then switches to Simple
- **THEN** optional informational and action disclosures SHALL reset to collapsed, while state-required recovery sections SHALL stay expanded

### Requirement: Both interface modes preserve essential content
Simple and Detailed SHALL differ only in optional explanations and informational disclosure defaults. Relevant readings, measurement units, freshness, coverage, warnings, errors, recovery actions, supported-capability and current-state control gates, and command confirmations SHALL be preserved in both modes; essential status SHALL NOT be hidden merely because Simple was selected.

#### Scenario: Compare the modes on the same route
- **WHEN** the owner switches between Simple and Detailed on Energy or Device
- **THEN** the route's essential readings, units, freshness, coverage, warnings, failure feedback and recovery SHALL remain visible, with identical authority gates and consequential-action confirmations

### Requirement: Selecting interface detail changes presentation only
Selecting Simple or Detailed SHALL NOT send an AC command, request telemetry, trigger a live device read, or alter a temperature draft, form entry, confirmation, or pending command outcome. The restriction applies to the act of selection itself; eligible route-entry reads that the app already performs SHALL continue to run normally and SHALL be attributable to route entry rather than to the selection.

#### Scenario: Selection with a draft or pending outcome present
- **WHEN** the owner changes Interface detail while a temperature draft or an unconfirmed command exists
- **THEN** the draft and the command status SHALL be unchanged and no new command or read SHALL be started by the selection

#### Scenario: Route entry after selection
- **WHEN** the owner navigates to Device after changing the choice
- **THEN** the Device route SHALL reuse existing same-context live state or perform its eligible initial read, and the selection itself SHALL have issued no request

### Requirement: Energy totals use only usable samples and keep coverage evidence
Recorded energy SHALL be presented as hourly or longer aggregates of five-minute samples, never as instantaneous power, and energy-per-time SHALL appear only as interval-average power from a recognized conversion.

#### Scenario: Energy eligibility preserves source and coverage distinctions
- **WHEN** the app aggregates energy samples or displays their coverage
- **THEN** Missing/non-finite, anomalous, failed-energy-read or unrecognized-unit values SHALL be excluded from usable sums while their status and evidence remain available. A separate live-state source failure SHALL NOT by itself discard otherwise usable energy. The app SHALL distinguish recorded query-cache coverage from usable measured coverage, SHALL label a sampled subtotal separately from finalized LG daily totals and local interval sums, and SHALL keep Asia/Manila day boundaries.

#### Scenario: Invalid sample in a selected day
- **WHEN** recorded query coverage includes an interval whose energy sample is invalid or has unknown units
- **THEN** that value SHALL be excluded from the usable sum, queried coverage SHALL NOT be presented as usable measured coverage, and the sample's status evidence SHALL remain available

#### Scenario: Finalized total differs from stored sums
- **WHEN** LG's finalized daily total differs from the local sum of usable samples
- **THEN** both measures SHALL be labeled distinctly with coverage context on the same Asia/Manila day

#### Scenario: Live-state source fails but energy remains usable
- **WHEN** an observation records a live-state source failure alongside valid recognized-unit energy without an energy failure or anomaly
- **THEN** usable energy SHALL remain in the sum while the independent state-source failure remains available as evidence

### Requirement: Home energy bins and freshness ages are honest and readable
Home SHALL group actual five-minute intervals into 24 hourly bins for the current day.

#### Scenario: Home bins retain real coverage and independent freshness
- **WHEN** the app renders Home energy groups and their age labels
- **THEN** Measured, partial, gap, measured-zero and future states SHALL remain distinguishable through readable text, outlines and accessible descriptions, not color alone; partial bins SHALL NOT look like completely measured solid bars. A newly received unusable sample SHALL NOT advance the timestamp used for latest usable sample age. Device state age, latest usable sample age, and history sync age SHALL be displayed independently.

#### Scenario: Invalid newest sample
- **WHEN** the newest incoming sample is invalid
- **THEN** the latest usable timestamp SHALL remain the previous usable sample's timestamp, its age SHALL continue increasing with elapsed time, and neither device nor sync freshness SHALL be substituted for it

#### Scenario: Empty and future bins
- **WHEN** a bin has no usable samples or lies later than the current time
- **THEN** a gap and a future interval SHALL remain distinct from one another and from measured zero through readable or accessible descriptions, without presenting future intervals as failed measurements

### Requirement: Preview fixtures are isolated from the release shell
Dedicated UI preview entry points and their fixture data SHALL be debug/test-only and non-exported, SHALL NOT be reachable through the release configuration, and SHALL NOT simulate a successful production connection. Production behavior SHALL require appropriate real owner, credential, and device sources rather than falling back to demo data when configuration or access fails.

#### Scenario: Release build
- **WHEN** the release configuration is built and run
- **THEN** no dedicated debug preview entry point or UI-test fixture data SHALL be packaged, and no synthetic success path SHALL be reachable from the production shell

#### Scenario: Previewed screen
- **WHEN** a screen renders from preview fixtures
- **THEN** it SHALL identify its synthetic source, and any simulated command outcome SHALL NOT be presented as a real household-device confirmation

### Requirement: Acceptance evidence cannot report an unmet gate as passed
Dated acceptance evidence SHALL identify the exact tested revision, source and scope, and historical captures SHALL NOT be presented as new acceptance. The historical Home pixel gate and its evaluated snapshot SHALL remain unchanged as failed/open at 74.69%; independent native review or a new diagnostic on another revision SHALL NOT relabel that historical result as passed, forced or waived.

#### Scenario: New native acceptance is recorded
- **WHEN** a current native acceptance pass succeeds within its stated scope
- **THEN** it SHALL be reported separately without changing or concealing the old failed/open pixel gate

#### Scenario: Evidence is cited
- **WHEN** delivery evidence is referenced for acceptance
- **THEN** it SHALL state the tested revision and its limits and SHALL label older captures as historical

### Requirement: Energy day chart shows hourly consumption
The Day view SHALL show 24 local-hour energy buckets in kWh, selectable values and quality status, and a separately labeled sampled daily subtotal. Week and month SHALL use daily aggregates. Missing, partial, anomalous and unknown-unit intervals SHALL remain distinguishable without inventing zeros; detailed coverage SHALL remain accessible.

#### Scenario: Incomplete hour
- **WHEN** an hour has usable and missing or invalid observations
- **THEN** its usable subtotal SHALL be marked partial and its quality evidence SHALL remain accessible

#### Scenario: No measured values
- **WHEN** an hour has no usable measurements
- **THEN** the chart SHALL show unavailable rather than measured zero
