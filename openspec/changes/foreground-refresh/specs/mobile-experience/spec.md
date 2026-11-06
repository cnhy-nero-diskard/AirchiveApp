## MODIFIED Requirements

### Requirement: Secondary routes reuse the instrument blocks without autonomous requests
Opening a disclosure SHALL NOT trigger a device read, a telemetry fetch, or an AC command. The foreground scheduler MAY refresh a verified device state independently of disclosure or route selection, with a five-minute baseline and bounded exponential backoff on failure. Scheduled reads SHALL share the command exclusion slot, use no additional profile reads, stop in the background, and be cancelled or fenced on owner or credential changes. Settings SHALL present Interface detail prominently and SHALL keep recovery actions for account, credential, and local-data flows reachable.

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
