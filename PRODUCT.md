# Airchive

<!-- impeccable:product-schema 1 -->

## Platform

android

## Stack

Android-first Kotlin Multiplatform with shared Compose Multiplatform UI and domain code. An iOS scaffold exists; iOS delivery and device validation are outside the first release.

## Users

The owner of the one LG air conditioner already monitored by Airchive. The owner confirmed on 2026-10-24 that the identity should focus on a personal, single-AC companion for now.

## Product Purpose

Give comfort control and energy history equal emphasis. A normal visit should make the AC's state and supported controls understandable alongside recorded energy use, without making setup and diagnostics the main experience.

## Positioning

A personal companion combining LG ThinQ live state and supported controls with Airchive's recorded five-minute telemetry and finalized daily energy totals. Local history remains useful offline.

## Operating Context

- Everyday AC state checks and explicit power, temperature, mode, and fan commands supported by the configured device profile.
- Energy exploration by day, week, and month, with recorded source, units, coverage, and gaps preserved.
- Independent freshness for live device state, recorded observations, and history synchronization.
- Google owner access for history and a separate protected ThinQ credential for device access.

## Capabilities and Constraints

- Keep the first experience focused on the configured AC; multi-device discovery and multi-household product design are outside the current identity scope.
- The production shell provides Home, Device, Energy, History, and Settings destinations; History presents the History/Health capability. Navigation changes must preserve these capabilities and update the existing specification when required.
- Settings offers Simple (the fallback for absent or unrecognized values) and Detailed presentation. The choice applies immediately, persists installation-wide, and changes explanations/disclosure defaults only; readings, units, warnings, authority gates, and recovery actions remain available in both modes.
- Render cached history promptly. Refresh on app lifecycle or explicit request; do not add continuous polling as a visual effect.
- Five-minute sampled energy is not instantaneous power. Interval-average power must be labeled accordingly.
- Missing measurements are gaps, never measured zero. Finalized LG daily totals and local interval sums remain separate.
- Energy-day boundaries use the configured AC's Asia/Manila time zone, independently of the viewer's location.
- Enable controls only for supported capabilities and current, verified device state. Report sending, accepted, confirmed, failed, and unconfirmed outcomes accurately; never automatically resend an ambiguous command.
- No measured room temperature, humidity, cost, savings, automation, or additional writable mode may be invented for a design concept.
- Home Instrument and its Shared Instrument layout were selected by the owner on 2026-10-24. The native Home implementation uses the shared live-state and command controllers alongside the existing energy presentation.

## Brand Commitments

Home Instrument: porcelain surfaces, slate text, restrained cobalt accents, and paired instrument-like readouts that give comfort and energy equal prominence. Preserve familiar native controls and clear measurement labels. The owner selected an image-first refinement workflow. No new logo or custom illustration has been approved.

## Evidence on Hand

- openspec/changes/add-personal-airchive-mobile-app/ contains the original product requirements and implementation record.
- shared/src/commonMain/kotlin/cloud/airchive/shared/ProductionAirchiveApp.kt implements the production route shell and Home.
- DeviceControlsCard.kt, EnergyRoute.kt, and HistoryHealthRoute.kt contain current interaction and measurement semantics.
- docs/thinq-control.md records direct observations separately from owner-reported real-device acceptance.
- `AirchiveTheme.kt` implements light/dark Material role schemes for the five-route production shell and configuration-blocked states. The legacy `AirchiveApp` demo still uses default Material schemes. Production routes share the instrument page/panel/disclosure system; `DESIGN.md` and `.impeccable/design.json` describe current composition. Dated handoffs remain historical. Concept images are generated previews rather than emulator captures.

## Product Principles

1. Comfort control and energy understanding deserve equal priority.
2. Freshness and measurement meaning are visible where they affect a decision.
3. Everyday tasks lead; setup and technical detail appear when relevant.
4. Offline history and uncertain command outcomes remain understandable.
5. Stay personal and focused on one AC until scope is explicitly expanded.

## Accessibility & Inclusion

Status needs readable text and accessible semantics alongside color. Preserve native Android navigation and controls, at least 48 dp touch targets, scalable text, light and dark themes, and system motion preferences. Charts must expose values and missing coverage without relying on color alone.
