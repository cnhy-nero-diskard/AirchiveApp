## Design

The resumed Android activity owns independent live-state and history loops. Owner and ThinQ context changes cancel the corresponding loop; pausing cancels both. History uses its existing authorization check, partition mutex and durable five-minute freshness gate. The live loop reuses only a matching verified profile and acquires the non-queuing device command slot before a state read. A busy command defers the read. Failed scheduled attempts back off exponentially from five minutes to a bounded maximum; no command is retried. Manual refresh remains explicit and can bypass history freshness.

The live controller's existing loading, stale and failure states communicate refresh without treating telemetry as current device state. Context fences discard results belonging to previous owners or credential generations. No background worker or continuous Firestore listener is introduced.
