## Why

[Issue #2](https://github.com/cnhy-nero-diskard/AirchiveApp/issues/2) hands off an accepted native Shared Instrument prototype whose production lifecycle behavior, specifications and delivery evidence still need integration and acceptance work. Reusing that implementation while closing owner/credential-state and draft-preservation gaps will make the selected experience reviewable without reopening its identity or overstating historical fixture results.

## What Changes

- Preserve Home Instrument, the Shared Instrument Home composition, equal comfort/recorded-energy emphasis, the personal one-AC scope, and Home, Device, Energy, History and Settings.
- Specify and finish the existing Android Simple/Detailed preference: Simple fallback, immediate presentation changes, informational versus action disclosure defaults, durable installation-wide storage, essential-content parity, and no extra requests, commands or lost drafts caused by selection.
- Isolate live observations, command feedback, pending work and sensitive setup state by owner/credential context. Prevent retained or late results from a previous context from appearing after replacement, removal or account changes.
- Preserve local temperature edits through same-context navigation to Settings and detail changes; reconcile fresh observations without silently submitting or overwriting a pending draft. Keep explicit Apply, capability gates and uncertain-command recovery.
- Preserve measured coverage, units, source distinctions, independent freshness, explicit historical downloads and the virtualized history ledger; verify them in both modes.
- Add targeted production-shell tests over isolated fake ports and a bounded phone acceptance matrix, including lifecycle/error states, light/dark, enlarged/max text, landscape, accessibility, IME and Back. Verify preview isolation in release configuration.
- Reconcile current product/design documentation and retain dated evidence, including the failed/open historical pixel gate. Deliver a focused PR to `main` referencing #2 with exact tested revision, evidence and unverified owner/device limits after implementation.

## Capabilities

### New Capabilities

None. This change refines existing mobile, control and onboarding capabilities rather than introducing another product area.

### Modified Capabilities

- `mobile-experience`: Accepted five-destination composition, truthful compact energy/freshness presentation, responsive native behavior, and the persistent presentation-only interface-detail preference.
- `ac-control`: Owner/credential-scoped live and command presentation, late-result suppression, explicit draft/Apply behavior and preservation through same-context navigation/detail changes.
- `owner-onboarding`: Account-scoped sensitive form/confirmation state and safe handling of in-flight credential setup across owner transitions, independent of the installation display preference.

The completed `add-personal-airchive-mobile-app` change defines these paths, but their main `openspec/specs/<capability>/spec.md` files are not yet populated. Before this change is applied, sync that completed change's four capability specs using the repository workflow, preserve its scenarios and historical acceptance attribution, and validate the baseline. This proposal only adds distinct requirements within the existing capabilities; it does not rewrite the previous change, sync it now, or authorize its archival.

## Impact

- Shared Compose shell, instrument/disclosure components, Home/Device/Energy/History/Settings presentation, ThinQ live/command lifecycle boundaries, Android graph/credential integration and debug-only fixtures/tests.
- Existing private Android interface preferences remain separate from owner history and protected ThinQ credentials; no database schema, backend query/rule, external API, dependency upgrade, release version or signing change is intended.
- Product/design authorities and a new revision-linked delivery evidence record will be refreshed during apply. Existing handoff captures and diagnostic failures remain historical, not new acceptance.

## Non-goals

- No identity reselection, replacement dashboard, decorative graphics, screenshot-based UI, polling, automatic resend or invented measurements/capabilities.
- No multi-device/household expansion, iOS adapter delivery or tablet acceptance; this delivery targets Android phones, including phone landscape.
- No production Firebase/account mutation, household AC command, private signing/install operation or destructive owner-data action without separate explicit authorization. Fixtures must use isolated synthetic data and never construct the real credential/network graph.
- Planning does not implement, commit, push, open the PR, sync/archive specs or certify a release.
