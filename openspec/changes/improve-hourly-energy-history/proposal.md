## Why

The current energy and history views make five-minute coverage more prominent than useful consumption trends and require manual loading of selected periods.

## What Changes

- Present hourly sampled energy on Home and Energy, with daily aggregation for longer periods and accessible quality evidence.
- Automatically load missing selected-period history within a bounded policy, retaining cached offline data and retry.
- Group the history ledger by hour with accessible observation drill-down.

## Capabilities

### New Capabilities

### Modified Capabilities

- `mobile-experience`: Hourly energy and history presentation and bounded automatic selected-period loading.
- `telemetry-sync`: Permit bounded automatic retrieval after period selection while reusing cached coverage.

## Impact

Shared presentation, history loading and tests; no changes to telemetry storage granularity or live AC polling.
