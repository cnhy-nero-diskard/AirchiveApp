## MODIFIED Requirements

### Requirement: Refresh catches new and revised samples
The app SHALL refresh history while foregrounded and authorized when its last successful refresh is at least five minutes old, and whenever the owner explicitly requests refresh. It SHALL query changes to both `persistedAt` and `reconciledAt` with boundary overlap, merge by `sampleId`, and advance its durable watermark only after all required pages and local writes succeed. Scheduled failures SHALL back off and the app SHALL NOT continuously listen to Firestore or schedule background telemetry polling.

#### Scenario: Older sample is reconciled
- **WHEN** an existing sample is revised by reconciliation
- **THEN** the next successful refresh SHALL replace the cached projection of that sample

#### Scenario: Partial refresh failure
- **WHEN** any required page or local commit fails during refresh
- **THEN** the durable watermark SHALL NOT advance and cached history SHALL remain available

#### Scenario: Manual refresh
- **WHEN** the owner requests refresh before five minutes have elapsed
- **THEN** the app SHALL attempt refresh without waiting for the foreground age gate

#### Scenario: App enters background
- **WHEN** the app leaves the foreground
- **THEN** scheduled history refresh SHALL stop
