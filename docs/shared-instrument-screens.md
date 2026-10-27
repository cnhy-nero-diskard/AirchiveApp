# Shared Instrument: remaining screens

2026-10-27. Device, Energy, History and production Settings now extend the selected Home Instrument composition. This is a dated implementation handoff for the [extension contract](../.impeccable/shared-instrument-remaining-contract.md); the existing [DESIGN.md](../DESIGN.md) tokens and [.impeccable/design.json](../.impeccable/design.json) remain the incumbent system. No new palette, font, visual identity or shipping raster asset was introduced.

## Current route layouts

| Route | Reading field and available detail |
| --- | --- |
| Device | Reuses Home's comfort instrument with one large set-temperature reading, power, supported mode/fan choices, local temperature draft and explicit Apply. Device identity, profile and last confirmed state expand under **Device details**. Opening that disclosure does not trigger another device read (`readOnOpen=false`); refresh stays explicit. |
| Energy | Day/week/month and period navigation precede sampled kWh, readable coverage and the existing semantic chart. **Download this period** follows the record. Coverage, daily records and conversions remain available in disclosures; week/month daily records start expanded. Period changes use cached data without automatically downloading it. |
| History | Cache count, usable-value count and refresh lead a virtualized newest-first ledger. Sync/collector health and the sample-status guide expand inline. Selecting an observation retains the native detail dialog. |
| Settings | Account and protected AC connection share one panel. Account actions and connection details expand inline; history access and history/storage use a second panel. Unverified history access starts expanded. Credential replacement and destructive account/cache/removal confirmations retain their native flows. |

The shared shell keeps native bottom navigation outside the scrolling content and preserves system insets. Configuration-blocked and signed-out framing also use the shared page structure. The legacy demo route is outside this production-screen recomposition.

## Reuse and implementation

The extension matches the incumbent porcelain/slate/cobalt roles in both themes: flat surface panels, large Material corners, quiet rules, native controls and scalable text. It uses the established 16dp screen gutters, 12dp page rhythm and 20dp panel inset. Device and Energy reuse `InstrumentReading`; contextual copy retains Material typography. Incomplete coverage uses amber with readable words. History uses a `LazyColumn` with the same horizontal gutter rather than nesting its ledger in a scrolling page.

`InstrumentPage`, `InstrumentPanel`, `InstrumentBlock` and `InstrumentDisclosure` implement this grouping in [InstrumentLayout.kt](../shared/src/commonMain/kotlin/cloud/airchive/shared/InstrumentLayout.kt). Disclosure rows expose expanded/collapsed semantics, allow wrapping, have a minimum 56dp height and use immediate reveal with native ripple. Inset-zero disclosures inside already padded Settings sections use `RectangleShape` so their leading glyphs do not intersect a curved clip; other disclosure rows retain the small Material shape.

Route composition is in [ProductionAirchiveApp.kt](../shared/src/commonMain/kotlin/cloud/airchive/shared/ProductionAirchiveApp.kt), with [HomeInstrument.kt](../shared/src/commonMain/kotlin/cloud/airchive/shared/HomeInstrument.kt), [DeviceLiveStateCard.kt](../shared/src/commonMain/kotlin/cloud/airchive/shared/DeviceLiveStateCard.kt), [EnergyRoute.kt](../shared/src/commonMain/kotlin/cloud/airchive/shared/EnergyRoute.kt), [HistoryHealthRoute.kt](../shared/src/commonMain/kotlin/cloud/airchive/shared/HistoryHealthRoute.kt) and shared Settings sections in [SettingsRoute.kt](../shared/src/commonMain/kotlin/cloud/airchive/shared/SettingsRoute.kt). The latter also retains its separate demo/settings shell; production composition is `ProductionSettingsRoute`.

Measurement and command meaning remain explicit: gaps are not zero; sampled five-minute sums, interval-average power and finalized LG daily totals are distinct; energy-day boundaries remain Asia/Manila. Live state, sample age and history sync are independent. Supported/current-profile command gates, local temperature drafts, explicit Apply and uncertain-command feedback remain intact. Disclosures add no autonomous command or telemetry request.

## Native evidence

The [gallery](../.impeccable/review/remaining/index.html) contains actual native first-viewport captures, also served locally at [localhost:3316](http://localhost:3316/). These are evidence, not a web implementation or shipping UI. All captures visibly identify synthetic preview data and no device connection.

| Route | Light 1.0 | Dark 1.0 | Light 1.3 | Dark 1.3 |
| --- | --- | --- | --- | --- |
| Device | [PNG](../.impeccable/review/remaining/device-light-1.0.png) | [PNG](../.impeccable/review/remaining/device-dark-1.0.png) | [PNG](../.impeccable/review/remaining/device-light-1.3.png) | [PNG](../.impeccable/review/remaining/device-dark-1.3.png) |
| Energy | [PNG](../.impeccable/review/remaining/energy-light-1.0.png) | [PNG](../.impeccable/review/remaining/energy-dark-1.0.png) | [PNG](../.impeccable/review/remaining/energy-light-1.3.png) | [PNG](../.impeccable/review/remaining/energy-dark-1.3.png) |
| History | [PNG](../.impeccable/review/remaining/history-light-1.0.png) | [PNG](../.impeccable/review/remaining/history-dark-1.0.png) | [PNG](../.impeccable/review/remaining/history-light-1.3.png) | [PNG](../.impeccable/review/remaining/history-dark-1.3.png) |
| Settings | [PNG](../.impeccable/review/remaining/settings-light-1.0.png) | [PNG](../.impeccable/review/remaining/settings-dark-1.0.png) | [PNG](../.impeccable/review/remaining/settings-light-1.3.png) | [PNG](../.impeccable/review/remaining/settings-dark-1.3.png) |

The [verification record](../.impeccable/review/remaining/verification.md) reports 313 shared tests with zero failures/errors, Android debug/instrumentation APK builds and debug lint passing. Four native tests cover Apply/stale gating, period selection, observation details and cache-deletion cancellation; one additional capture test passes at 1.3 font scale. Execution logs: [build/tests/lint](../.impeccable/review/routes-build.log), [native interactions](../.impeccable/review/routes-instrumentation.log), [large text](../.impeccable/review/routes-large-text.log). These existing results were inspected for this handoff; no tests were rerun by the documenter.

The [initial finish review](../.impeccable/review/remaining/finish-review.md) identified one P2: clipped Settings disclosure text. The [bounded follow-up verdict](../.impeccable/review/remaining/finish-verdict.md) records that fix as resolved across four replacement Settings captures and gives a **ship** disposition within the reviewed scope.

Acceptance is limited to an Android 15 phone emulator, 1080×2400 at density 420, light/dark at font scales 1.0/1.3, using a debug-only in-memory fixture. It establishes no production sign-in, physical hardware or real AC command acceptance. Captures cover the first viewport; offscreen/expanded layouts have only the stated source and interaction evidence. Tablet, landscape and maximum-font acceptance remain outstanding. The older Home comp pixel gate remains failed/open and untouched; this extension makes no pixel-perfect claim.

## Pre-existing documentation drift

[PRODUCT.md](../PRODUCT.md)'s **Evidence on Hand** still describes default Material schemes and the absence of DESIGN.md/current fixtures at the start of the earlier identity review. [DESIGN.md](../DESIGN.md)'s historical scope/layout paragraphs still say secondary routes retain their former layouts and 24dp gutters; the sidecar repeats the former composition scope. Those statements predate this extension and do not describe these four production routes as of 2026-10-27. They were preserved, not refreshed, in this ordinary extension; this handoff records the current route composition without changing the normative tokens or system documents.
