## Why

An open dashboard needs current device state and history without a manual refresh.

## What Changes

- Refresh verified live state and authorized cached history on a bounded foreground cadence.
- Pause scheduled work in the background, fence owner and credential transitions, and back off failures.
- Keep live state, recorded telemetry, and manual refresh distinct.

## Capabilities

### Modified Capabilities
- `telemetry-sync`: permit five-minute foreground refresh of history.
- `mobile-experience`: permit scheduled live reads without making disclosures cause reads.

## Impact

Android lifecycle scheduling, ThinQ command coordination, history sync and tests.
