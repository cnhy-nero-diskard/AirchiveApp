## MODIFIED Requirements

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

## ADDED Requirements

### Requirement: Energy day chart shows hourly consumption
The Day view SHALL show 24 local-hour energy buckets in kWh, selectable values and quality status, and a separately labeled sampled daily subtotal. Week and month SHALL use daily aggregates. Missing, partial, anomalous and unknown-unit intervals SHALL remain distinguishable without inventing zeros; detailed coverage SHALL remain accessible.

#### Scenario: Incomplete hour
- **WHEN** an hour has usable and missing or invalid observations
- **THEN** its usable subtotal SHALL be marked partial and its quality evidence SHALL remain accessible

#### Scenario: No measured values
- **WHEN** an hour has no usable measurements
- **THEN** the chart SHALL show unavailable rather than measured zero
