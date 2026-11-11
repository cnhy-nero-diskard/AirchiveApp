## 1. Foreground refresh

- [x] 1.1 Define the foreground cadence and lifecycle requirements.
- [x] 1.2 Implement lifecycle-scoped history and live-state scheduling with backoff and command serialization.
- [x] 1.3 Verify cadence, cancellation, context fencing, backoff, and command exclusion, including Android lifecycle/device acceptance. **Completed 2026-11-11:** 495 host tests passed, including freshness/backoff/context/slot tests; three new Android lifecycle tests passed on GPU-backed `emulator-5554`. They exercise pause/resume, owner change/sign-out, in-flight cancellation, retained failure backoff and delayed live entry using the production refresh loop with synthetic callbacks. No live AC or production account access. See `docs/verification/shared-instrument/acceptance-2026-11-11.md`.
