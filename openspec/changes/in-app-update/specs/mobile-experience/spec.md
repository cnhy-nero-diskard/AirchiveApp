# Spec Delta

## MODIFIED Requirements

### Requirement: Main routes cover the owner's everyday tasks
The app SHALL provide Home, Device, Energy, History/Health, and Settings destinations. Home SHALL lead with the AC and an actionable summary; Device SHALL expose live state and supported controls; Energy SHALL show usage over time; History/Health SHALL explain observations and collector quality; Settings SHALL manage credentials, refresh, local data, and the app version and updates. Each destination SHALL handle loading, empty, offline, stale, denied, and error states relevant to it.

#### Scenario: Ready owner opens the app
- **WHEN** an onboarded owner opens the app with cached data
- **THEN** Home SHALL render the cached summary promptly while eligible network refresh runs separately

#### Scenario: Owner needs diagnostic detail
- **WHEN** the owner opens History/Health
- **THEN** the app SHALL expose sample status, gaps, source failures, collector health, and last-sync context in readable terms
