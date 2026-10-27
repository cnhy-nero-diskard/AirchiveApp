## Context

See `proposal.md` for motivation and scope. The starting implementation is the issue #2 handoff at `7da585c7e3eb55500de35cd3840059a764ad7fd5`, on `feat/issue-2-shared-instrument-integration`. The branch already contains the accepted native theme, instrument and four supporting routes; its later ignore-only commit does not change application behavior. `docs/ui-ux-agent-handoff.md`, `docs/shared-instrument-screens.md` and `docs/interface-detail.md` establish the accepted behavior where older product/design scope sentences disagree.

Exploration identified lifecycle seams, not a need to reconstruct the UI:

- `HomeInstrument.kt` hides retained state during entry, but treats return from `onDeviceOpened` as completion even when no new successful live read was produced. Its temperature draft is remembered against profile and observed temperature.
- `AndroidProductionThinQGraph.kt` clears cached device/profile without resetting live/command controllers. Credential setup saves inside the setup coordinator before the graph receives its result.
- `ProductionDeviceRoute` renders secondary live details directly from the controller. Settings form state is remembered by bindings rather than owner identity.
- The production shell conditionally composes routes; a Settings round-trip disposes the Home/Device panel. The preview currently bypasses the production Home shell and uses a separate fake route composition, so its existing green checks do not establish these lifecycle properties.

These are source observations to reproduce with safe fixtures before changing behavior, not newly executed device failures. The old mobile change is complete, but main spec files are absent; baseline synchronization is a prerequisite in the apply phase. Design is required because shared UI, asynchronous controls and protected Android credential commits cross lifecycle boundaries.

## Goals / Non-Goals

**Goals:**

- Give owner/credential changes a single explicit invalidation boundary shared by UI state and asynchronous work.
- Keep same-context drafts and pending outcomes independent of route/disclosure composition and interface-detail selection.
- Add production-shell acceptance coverage without creating a real Firebase, credential or household-device graph.
- Prefer small extensions of existing ports, controllers and instrument components over replacing accepted behavior.

**Non-Goals:**

- No new global state framework, dependency upgrade, database migration or polling service.
- No persistence of temperature drafts or sensitive setup forms across process death; only the existing nonsecret display preference promises installation durability.
- No exact-pixel certification, tablet navigation redesign, release distribution or new household-device acceptance. Product-level exclusions are in the proposal.

## Decisions

### D1. Preserve the native instrument and apply additive requirement deltas

Keep `AirchiveTheme`, `InstrumentReading`, `InstrumentPage/Panel/Block/Disclosure` and existing data presenters as the incumbent implementation. Use semantic Material roles and existing type/shape/spacing tokens, not new component colors or generated raster geometry. Keep the 76sp/82sp tabular reading role, equal Home readings and stack-before-shrink thresholds; fix observed overflow structurally within that hierarchy.

The three delta files add distinctly named requirements to existing capability paths. They do not replace the original requirements or scenarios. During apply, first sync the four specs from `add-personal-airchive-mobile-app`, verify preserved content and validate. Do not rewrite that completed change's tasks or reinterpret its owner-reported acceptance as evidence for this delivery. Archival of the previous change is a separate lifecycle action, not a prerequisite for writing this proposal.

**Alternative:** Recreate the screens or fold this work into the completed change. Rejected because it discards accepted implementation/evidence and mixes historical acceptance with new work. Re-declaring entire base capabilities here is also rejected: it would duplicate obligations and make sync order ambiguous.

### D2. Fence live, command and credential work by an explicit context generation

Associate runtime state and operations with an opaque context key combining the owner/project/device partition and a monotonically changing credential generation. The key contains no raw token. Increment/invalidate it on owner change, confirmed sign-out, credential removal, protected-key loss and successful credential replacement, including replacement with otherwise identical metadata. A failed replacement for the same owner leaves the working credential context intact.

The lifecycle owner must synchronously stop exposing the old context before cleanup or new requests proceed. Clear live/profile/command presentation, temperature drafts and sensitive setup state at that boundary; reset History sample selection only when its owner/project/device partition changes. A same-context transient read failure can retain explicitly old state with commands disabled; a new context starts unread and cannot use an older context as its fallback.

Capture the context before starting profile/live/command/setup work, recheck it when authorizing dispatch, and check it again before publishing any result. Bind an authorized operation's credential access to that captured context so neither deferred dispatch nor a confirmation read can borrow a replacement token; credential snapshots remain private transient data, never part of the context key or diagnostics. Ignore late old-generation completions and prevent them from enabling controls or repopulating a cleared profile. A new entry is ready only after a successful device-matching live read and matching verified profile for that context, not merely because an entry callback returned. Apply this guard to Device details as well as the main instrument. Opening details remains presentation-only (`readOnOpen=false`).

Credential setup needs a fence at the protected-store commit itself: checking only after `setupCredential` returns is too late. Introduce a narrow conditional-commit/context seam in the existing setup/account integration and serialize commit versus invalidation using a shared lifecycle boundary. Retain verify-before-save and preserve the previous working token on same-owner verification failure. The boundary covers short generation/attempt checks, local state changes and the protected-store commit, never LG I/O or waiting for an in-flight command. Give each setup attempt its own revision so cancellation, removal or a newer attempt supersedes a still-running verification even when the owner is unchanged.

Explicitly restructure `onDeviceOpened`'s incumbent network-under-`profileMutex` section: snapshot the eligible context and required work, release locks before list/profile/live requests, then reacquire the lifecycle boundary followed by `profileMutex` only for conditional local publication. Lock order is lifecycle boundary -> profile mutex, never the reverse. Where existing account actions hold the history coordinator's operation lock, that lock is outermost; ThinQ/profile code must not call back into history operations while holding its locks. Invalidation must not wait for a network-held profile mutex. Add deterministic tests that suspend each network step and still allow invalidation to complete.

A current verified candidate's protected-store commit and credential-generation increment form one atomic transition. Associate the verified profile and setup-success feedback with the resulting generation, not the now-superseded originating one; its live state remains unread until the normal successful matching live read. A stale/cancelled attempt performs neither the store write nor the generation transition. Failed cleanup leaves controls conservatively invalidated until a fresh eligible read rather than resurrecting old presentation state.

Cancellation is resource management, not the correctness fence. If an old command may already have been sent, never resend or claim it was undone. Its result cannot become the new context's feedback/state. Keep the submission gate device-global across generations: do not create an independent unlocked gate for the replacement context. While a superseded request still occupies that gate, the new context disables conflicting actions with a neutral busy explanation or reports that a new command was not submitted; it never waits in an automatic command queue, reveals the old target/outcome, or retries when the gate becomes free. Only the old operation's own completion releases its slot. Cancellation is not proof that a physical command was undone.

**Alternatives:** UI-only hiding or resetting controllers without fencing completions. Rejected because secondary details and late callbacks can repopulate retained state. Comparing token masks/timestamps alone is rejected because different credentials can share that metadata. Rebuilding the entire Android production graph on every route/detail change is rejected because it would add requests and cancel useful work.

### D3. Hoist small same-context interaction state above route composition

Own the temperature draft in a shared, context-keyed state holder at the production shell/controller boundary rather than inside the disposable panel. Home and Device consume the same draft and current observation, with separate values for the pending edit and last confirmed setting. Recomposition, disclosure changes, navigation to Settings and interface-detail selection do not replace the draft.

A successful same-context read updates confirmed context without overwriting a dirty draft. If the observed value now equals the draft, recompute whether Apply is needed without claiming an unsent command was confirmed. Revalidate against the latest supported units/bounds/step; an unsupported draft disables Apply and requires an explicit valid selection rather than silently clamping or submitting a substitute. A context change discards the draft. Process recreation can reset it; the installation preference remains durable.

Own in-flight command work above individual route coroutine scopes so a presentation change does not cancel/restart a submitted request or hide its eventual matching outcome. Keep one-at-a-time submission and consume existing bounded confirmation reads; do not add an extra confirmation fetch or automatic retry.

Scope PAT/country drafts, replacement/removal confirmations and account feedback to the current owner/context. Detail changes preserve a same-context active form or confirmation; account changes, cancellation or credential removal clear the relevant sensitive state. Do not save raw PAT drafts with saved-instance state, preferences, history or evidence. Scope History sample selection to its owner/project/device partition, independently of ThinQ credential generation, so a new owner's matching sample identifier cannot inherit an open selection and replacing a PAT need not reset unrelated history browsing.

**Alternative:** `rememberSaveable` for every field or preserving drafts only within the panel. Rejected because indiscriminate persistence risks secrets, while panel-local memory cannot survive the required Settings round-trip. A broad navigation rewrite is unnecessary for this small state lifetime change.

### D4. Keep interface detail as an isolated presentation input

Retain the existing Android `airchive_interface` preference adapter and the shared in-memory fallback. Neither sign-out, history clearing nor PAT removal deletes the installation-wide preference. Keep it out of network effect keys, graph/context construction and operation lifecycle state.

Preserve the current disclosure rule: mode changes reset to that mode's defaults; informational sections default open only in Detailed, action sections remain closed, and state-required recovery expansion takes precedence in those defaults. Manual disclosure control remains available afterward. Do not fix the intentional reset as though it were lost form state. Preserve the incumbent independent placement of the PAT editor outside Connection details and confirmation dialogs outside Account actions: their active UI/state survives while a separate informational/action header resets. Do not render descendants beneath a header that announces Collapsed, move an active editor into a default-resetting disclosure, or auto-expand Account actions merely to keep a dialog alive. Header semantics must match actual content visibility.

Request-count tests separate preference selection from legitimate route-entry reads. Switching the selector while remaining in Settings must add zero network/command calls; returning to Device can still perform its established entry read. The whole journey must retain the draft while preserving all safety gates.

**Alternative:** Key a whole route/provider by detail or reuse detail as a graph setting. Rejected because remounting would discard drafts and could trigger extra reads. Maintaining different action paths for Simple and Detailed is rejected because authority and essential truth must be identical.

### D5. Reuse presenters and validate measurement semantics, not illustrative numbers

Keep energy normalization, Manila day boundaries and existing usable-value rules in domain presentation code. Home derives eight three-hour groups from actual five-minute slots; Energy keeps day/week/month and source-specific totals. Test invalid/unknown-unit/future/zero/partial cases rather than copying the generated composition's numbers. In particular, failed energy reads invalidate usable energy, while a separate live-state source failure must not by itself erase an otherwise usable energy value. Query-cache coverage comes from recorded query coverage, not merely the presence of observations; keep it distinct from usable measured coverage, as well as the device-read, latest usable sample and history-sync ages.

Retain explicit historical download actions and the virtualized History ledger. Test a large cached ledger through real scrolling; do not replace it with an eager nested list. Profile widths, enlarged text and long values using synthetic fixtures. Change sorting/presentation execution only if a measured issue requires it, not as speculative performance work.

**Alternative:** Separate chart/total logic for Simple or a hardcoded compact Home chart. Rejected because either risks changing measurement meaning or converting missing evidence to apparent measurements.

### D6. Exercise the production shell with isolated ports and bounded native review

Extend the debug/test fixture to compose `ProductionAirchiveApp` with fake owner, history, protected-credential and ThinQ ports. Supply controllable deferred responses, per-operation counters, a deterministic clock and two owner/credential generations. Preserve production Firebase/Keystore/HTTP graph exclusion and an isolated preference file. Keep the preview Activity debug-only and non-exported. Inspect release source-set/dependency/manifest boundaries without requiring private signing material or installing on the owner's phone; inspect packaged classes/resources only when an authorized normal release build is available. If the signing preflight prevents artifact-level verification, report that check as unverified rather than bypassing the preflight, supplying dummy signing material or calling source inspection a packaged-release pass.

Acceptance matrix:

| Dimension | Required delivery evidence |
| --- | --- |
| Routes/modes/themes | All five production destinations in Simple/Detailed and light/dark, with normal and enlarged text; preserve navigation and essential labels |
| State journeys | Ready, meaningful empty/no usable values, offline, denied, stale, unavailable, loading, errors, sending/accepted/confirmed/failed/unconfirmed; expanded/offscreen recovery and confirmations |
| Lifecycle | Selection-only zero side effects; Home/Device draft through Settings; manual disclosure/reset rules; same-owner form/dialog preservation; account/credential transitions and late responses; preference reopen/recreation and isolated process restart |
| Measurements | Day/week/month, explicit download counts, gaps/partial/zero/future/unknown units, finalized/local/sample labels, cache versus usable coverage, independent ages, large ledger |
| Phone adaptivity | Default width and a narrow width exercising the <300dp panel threshold; phone landscape; text scales 1.0/1.3 and the emulator's maximum supported setting; long copy/readings and IME |
| Native access | Screen-reader labels/order/selected/expanded states and announcements, keyboard/focus reachability, native Back/dialog cancellation, touch targets and system bars |
| Release isolation | No preview Activity or fake production-success path in release configuration/artifacts |

Set and verify the emulator's effective font scale/window size, not just screenshot filenames or runner labels. Restore original settings in cleanup. A source-semantics check is not a full TalkBack certification; record exactly what was exercised and mark any unavailable manual/hardware check as unverified.

Run shared host tests, debug and instrumentation builds, and debug lint on the delivery branch, followed by explicitly selected synthetic native tests on a named emulator. Rerun affected Firebase-emulator instrumentation if account/auth adapter boundaries change; never substitute opt-in live tests. Run release-automation smoke checks only if those scripts change (they are not planned scope). Use prepared offline caches when available; report dependency/tool failures without weakening checks.

Capture one batched native evidence set, perform bounded independent review, fix material findings in one batch, then perform at most one confirmation pass. Do not reopen concept selection or iterate cosmetically without a defect. Store curated new evidence under `docs/verification/shared-instrument/`, outside ignored scratch output, with a linked summary, commands, results, tested commit/tree, fixture attribution and remaining limitations. No raw tokens, private telemetry, owner cache, exception payloads, APKs or noisy emulator logs belong there.

**Alternative:** Accept existing screenshots/test totals as delivery evidence or certify production behavior solely from the direct-panel fixture. Rejected because the old captures predate the new lifecycle changes and the fixture misses the production entry/navigation boundaries.

### D7. Preserve honest history and separate planning, apply and delivery

During apply, refresh `PRODUCT.md`, `DESIGN.md` and the design sidecar together to describe the five delivered routes, current gutters/grouping and detail preference; keep dated handoffs attributable to their historical revisions. Preserve the failed/open 74.69% pixel diagnostic unchanged. New native acceptance can be green at its stated scope without declaring that historical gate passed, forced or waived.

After implementation, tests and bounded review, sync this change's additive deltas through the repository workflow, verify that original scenarios survive, and satisfy the repository's spec lifecycle gate before merge. Create a PR to `main` referencing #2 only in the authorized delivery phase. Record current test evidence and owner-only follow-ups instead of calling missing hardware, production sign-in, full TalkBack or iOS checks complete. Recheck `main` at that point rather than assuming the exploration comparison is still current.

**Alternative:** Mutate old screenshots/gate state or mark unavailable acceptance tasks complete. Rejected because it destroys the evidence boundary and makes the handoff less trustworthy.

## Risks / Trade-offs

- [Late network completion after owner invalidation] -> Fence protected-store commits and all state publications by captured generation; test deliberately delayed success/failure/cancellation, including a new owner arriving before the old result.
- [Over-broad reset loses a legitimate draft or dialog] -> Separate presentation, interaction and authority lifetimes; use same-context round-trip tests and context-change discard tests.
- [Route-local cancellation changes ambiguous command outcomes] -> Move operation ownership above route composition, preserve honest unconfirmed treatment and never resend.
- [New lifecycle lock deadlocks existing history/profile operations] -> Define mutation ordering, avoid network work under locks and exercise concurrent cleanup/setup/read scenarios with deterministic fakes.
- [Maximum text or landscape exposes more content than a first viewport] -> Inspect expanded/offscreen content and fix layout rather than reducing text scaling or hiding recovery actions.
- [Emulator or manual accessibility limits] -> Report actual coverage and owner follow-ups; do not turn an unavailable check into a pass or expand to tablet/iOS delivery.
- [Ignored scratch output hides review evidence] -> Curate new evidence under the tracked docs location; preserve handoff reference files and keep unrelated scratch untracked.
- [Baseline specs still unsynced] -> Complete and validate baseline sync before applying this change; do not change prior task attribution or archive it as a side effect.

## Migration Plan

1. Planning stage: create only this change's metadata, proposal, delta specs, design and tasks; validate and stop for review.
2. After explicit apply authorization, recheck branch/working state and sync the completed original capability baseline without archival or application behavior changes.
3. Add reproducing tests, then implement the narrow context-generation, conditional-commit and hoisted-state changes using existing production boundaries.
4. Exercise the acceptance matrix, inspect release isolation, collect current evidence and finish the bounded independent review/fix/confirmation cycle.
5. Refresh current docs/sidecar, sync and validate the delivery deltas, and open the issue-linked PR through the repository's authorized delivery workflow. Keep hardware/sign-in acceptance separately gated.

No Room schema or preference-key migration is planned. Existing saved Simple/Detailed values retain their meaning; missing/unrecognized values still select Simple. Runtime drafts are deliberately reset on a new process/context, not migrated into storage. Roll back application regressions by reverting this change's implementation commits while preserving historical evidence and the installed owner's data; do not uninstall, clear production cache/credentials, or alter backend rules as an automatic rollback.
