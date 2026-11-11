## MODIFIED Requirements

### Requirement: Initial synchronization is bounded
On a fresh owner-authorized installation, the app SHALL load at most the latest 24 hours of five-minute observations plus the latest 30 available finalized daily totals before declaring initial history ready. Collection queries SHALL respect the Airchive access contract's maximum page size of 250. Older observation ranges SHALL load only for an explicitly selected period and then be cached; selection can start bounded missing-range loading automatically.

#### Scenario: First successful history load
- **WHEN** the owner completes setup and history access is available
- **THEN** the app SHALL populate the initial bounded window and show when it last synchronized

#### Scenario: Older range requested
- **WHEN** the owner selects an uncached historical range
- **THEN** the app SHALL fetch it in bounded pages and retain it locally for later viewing
