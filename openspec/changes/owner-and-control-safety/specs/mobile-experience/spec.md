# Spec Delta

## MODIFIED Requirements

### Requirement: Accepted native instrument identity spans the five production routes
The app SHALL preserve the accepted native Home Instrument identity across Home, Device, Energy, History, and Settings. Home SHALL present one continuous panel that pairs comfort and recorded energy with equal emphasis: a setpoint with local editing that is sent after a one-second pause when control authority permits, separate supported mode and fan controls, and an energy chart with nearby coverage and latest usable sample age. Yesterday's finalized LG total, when available, and history-sync context SHALL be labeled separately from today's sampled subtotal and the live device read. Device SHALL reuse the Home comfort instrument rather than introduce a second control language. Missing readings SHALL use an em dash and honest setup/unavailable context, never invented zero or apparent connection.

#### Scenario: Owner opens Home
- **WHEN** an onboarded owner opens Home with cached data and verified live control authority
- **THEN** one continuous panel SHALL give comfort and recorded energy equal prominence with explicit controls, nearby chart/coverage/sample age, and separately labeled finalized-total and history-sync context

#### Scenario: Owner opens Device
- **WHEN** the owner navigates to Device
- **THEN** the comfort reading SHALL reuse the Home instrument composition, including the setpoint with its paused send and separate mode and fan controls subject to the same authority gates

#### Scenario: Live access is missing while history is cached
- **WHEN** history is available but there is no verified live device reading
- **THEN** cached recorded energy SHALL remain available, comfort SHALL show honest missing/unavailable context, and control authority SHALL NOT be inferred from that history
