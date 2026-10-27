## ADDED Requirements

### Requirement: Accepted native instrument identity spans the five production routes
The app SHALL preserve the accepted native Home Instrument identity across Home, Device, Energy, History, and Settings. Home SHALL present one continuous panel that pairs comfort and recorded energy with equal emphasis: a setpoint with local editing and explicit Apply when control authority permits, separate supported mode and fan controls, and an energy chart with nearby coverage and latest usable sample age. Yesterday's finalized LG total, when available, and history-sync context SHALL be labeled separately from today's sampled subtotal and the live device read. Device SHALL reuse the Home comfort instrument rather than introduce a second control language. Missing readings SHALL use an em dash and honest setup/unavailable context, never invented zero or apparent connection.

#### Scenario: Owner opens Home
- **WHEN** an onboarded owner opens Home with cached data and verified live control authority
- **THEN** one continuous panel SHALL give comfort and recorded energy equal prominence with explicit controls, nearby chart/coverage/sample age, and separately labeled finalized-total and history-sync context

#### Scenario: Owner opens Device
- **WHEN** the owner navigates to Device
- **THEN** the comfort reading SHALL reuse the Home instrument composition, including the setpoint with Apply and separate mode and fan controls subject to the same authority gates

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
Bottom navigation SHALL sit outside the scrolling content with all five destination labels visible, and content SHALL scroll independently of navigation in both themes. The app SHALL respect system insets, keep on-screen actions reachable while the keyboard is shown, and let Back dismiss the keyboard before leaving the current route when the IME is open. Phone landscape and the maximum supported text scale SHALL be supported, testable states of every route.

#### Scenario: Rotate to landscape
- **WHEN** the phone rotates to landscape on any of the five routes
- **THEN** navigation labels, current readings, and actions SHALL remain visible without overlap and the route SHALL still scroll

#### Scenario: Back with the keyboard open
- **WHEN** the owner presses Back while the keyboard is visible
- **THEN** the keyboard SHALL close first without discarding a local draft or navigating away

### Requirement: Secondary routes reuse the instrument blocks without autonomous requests
Opening a disclosure SHALL NOT trigger a device read, a telemetry fetch, or an AC command. Device details SHALL expand without an additional device read, with refresh remaining explicit, and Energy, History, and Settings disclosures SHALL behave the same. Settings SHALL present Interface detail prominently and SHALL keep recovery actions for account, credential, and local-data flows reachable.

#### Scenario: Device details disclosure
- **WHEN** the owner expands Device details
- **THEN** no new device read SHALL be issued and the existing state SHALL keep its own observation time

#### Scenario: Settings action recovery
- **WHEN** the owner expands an account or connection section in Settings
- **THEN** the section SHALL expose its recovery or management actions without issuing a request merely from being opened

### Requirement: Energy period changes are presentation-only and downloads stay explicit
Changing the Energy range between day, week, and month or navigating its dates SHALL present cached data without automatically downloading the selected range, and SHALL label the selected period and data availability. Historical intervals needed for that selected range SHALL load only after the explicit download/load action, respecting cached query coverage. Existing bounded foreground/manual synchronization remains independent; browsing a period is not a download request.

#### Scenario: Switch to an uncached period
- **WHEN** the owner selects a week or month with no local record
- **THEN** the route SHALL show its empty or cached presentation without silently issuing a download

#### Scenario: Explicit download
- **WHEN** the owner activates the download action for the selected period
- **THEN** missing data for that selected period SHALL be requested within the existing bounded-query contract, already covered ranges SHALL NOT be redundantly fetched, and progress/outcome SHALL be labeled

### Requirement: History ledger stays virtualized, selectable, and newest-first
The History ledger SHALL list observations newest first without eagerly composing the entire cache, SHALL let the owner select an observation to open its detail, and SHALL keep saved count, cache/sync context and refresh at the head of the list. Usable-value counts and quality/collector explanations SHALL remain reachable in either mode without making technical detail dominate Simple.

#### Scenario: Large local history
- **WHEN** the local cache holds many observations
- **THEN** the ledger SHALL scroll responsively with the newest entries first

#### Scenario: Select an observation
- **WHEN** the owner selects a ledger entry
- **THEN** a native detail presentation SHALL open for that observation only

### Requirement: Interface detail defaults to Simple and persists installation-wide
Interface detail SHALL offer Simple and Detailed, with Simple selected when no value is saved or the saved value is unrecognized. The choice SHALL be stored in private installation-wide preferences that survive sign-out, owner history and cache removal, and ThinQ credential removal, and SHALL NOT be treated as owner data. An in-memory fallback used where no platform adapter exists SHALL NOT promise persistence across app instances.

#### Scenario: Fresh installation
- **WHEN** the app has never recorded a choice
- **THEN** Simple SHALL be selected

#### Scenario: Sign-out or cache removal
- **WHEN** the owner signs out or clears cached history
- **THEN** the saved Interface detail choice SHALL remain in effect for the next session

### Requirement: Interface detail applies immediately and sets disclosure defaults
A new choice SHALL take effect immediately across Home, Device, Energy, History, and Settings without an app restart. Informational sections SHALL start collapsed in Simple and expanded in Detailed; task and action sections, including Account actions, SHALL start collapsed in both modes. The owner SHALL be able to override disclosures manually, and those overrides SHALL reset to the selected mode's defaults whenever the mode changes. State-required recovery sections, such as unverified history access, SHALL default to expanded in either mode and after a mode change, while retaining manual disclosure control.

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
- **THEN** the Device route's normal entry read SHALL still occur, and the selection itself SHALL have issued no request

### Requirement: Energy totals use only usable samples and keep coverage evidence
Recorded energy SHALL be presented as five-minute samples, never as instantaneous power, and energy-per-time SHALL appear only as interval-average power from a recognized conversion. Missing/non-finite, anomalous, failed-energy-read or unrecognized-unit values SHALL be excluded from usable sums while their status and evidence remain available. A separate live-state source failure SHALL NOT by itself discard otherwise usable energy. The app SHALL distinguish recorded query-cache coverage from usable measured coverage, SHALL label a sampled subtotal separately from finalized LG daily totals and local interval sums, and SHALL keep Asia/Manila day boundaries.

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
Home SHALL group actual five-minute intervals into eight three-hour bins for the current day. Measured, partial, gap, measured-zero and future states SHALL remain distinguishable through readable text, outlines and accessible descriptions, not color alone; partial bins SHALL NOT look like completely measured solid bars. A newly received unusable sample SHALL NOT advance the timestamp used for latest usable sample age. Device state age, latest usable sample age, and history sync age SHALL be displayed independently.

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
