# Airchive UI/UX agent handoff

Captured 2026-10-27. This branch is a publication snapshot of approved design work and its current native prototype; it is not a release or a merge to main.

## Start here

1. Read the accompanying GitHub issue for the complete user-approved direction, constraints, remaining tasks and acceptance checklist.
2. Use this branch's current source and native evidence. The work was originally local and uncommitted on top of `2415bda046a73c63b3e7755a7d8906d913734ca2`; this snapshot makes it available to a remote agent.
3. Read [PRODUCT.md](../PRODUCT.md), [DESIGN.md](../DESIGN.md), [remaining screens](shared-instrument-screens.md), and [interface detail](interface-detail.md). The latter two dated handoffs supersede historical statements that secondary routes retain the old layout or have no detail preference. PRODUCT's old default-Material evidence sentence is historical and should be reconciled in the next implementation/documentation pass.
4. Read [native Home verification](../.impeccable/review/final-verification.md), [remaining-route verification](../.impeccable/review/remaining/verification.md), and [detail-mode verification](../.impeccable/review/interface-detail/verification.md). Review scope is explicit in each record.

## Approved direction

Home Instrument is the visual world. Shared Instrument is the selected Home composition. Personal one-AC companion; comfort control and recorded energy have equal emphasis. Porcelain/slate/cobalt, native sans with equal tabular readings, flat grouped panels, quiet rules and familiar native Material controls. Five destinations: Home, Device, Energy, History, Settings. Simple is the saved default interface-detail mode; Detailed adds explanations and initially expands informational sections. Both retain warnings, units, freshness, confirmations and all safety gates.

This is continuation work, not an invitation to rerun identity selection or replace the world with a generic dashboard, glowing thermostat or discarded concept.

## Visual evidence

- [Approved generated Shared Instrument comp](../.impeccable/mocks/home-shared.png): composition reference, not a screenshot or real data.
- [Native Home light](../.impeccable/review/phone-light.png) / [dark](../.impeccable/review/phone-dark.png): earlier Home milestone captures.
- [Remaining screens evidence](shared-instrument-screens.md): dated first-viewport captures for four routes, before the later Simple/Detailed preference.
- [Simple settings](../.impeccable/review/interface-detail/interface-simple-light-1.0.png) / [Detailed settings](../.impeccable/review/interface-detail/interface-detailed-light-1.0.png): latest preference milestone. Light/dark and 1.3 font-scale variants are in the same directory.

Native images use isolated, labeled synthetic fixtures. They do not establish real AC or signed-in physical-phone acceptance. Generated concepts and native captures never ship as controls or backgrounds.

## Verification and limits

Latest recorded checks: Android debug/instrumentation builds, Android debug lint, 314 shared tests, six native tests and an additional 1.3-text capture test passed. These results are recorded from this design session, not a new remote CI run. Persistence was exercised across Activity recreation and fresh Activity/preference instances; forced OS process-kill persistence was not separately tested.

The old Home pixel gate remains failed/open at 74.69 percent with hard region mismatches. Its actual state/spec/diff reports are included. Native review resolved the listed application findings; no exact-pixel pass is claimed. Preserve native semantics, disabled states and responsive text rather than falsifying the gate or baking screenshots into the UI.

Physical hardware, real AC commands, production sign-in, tablets, landscape, maximum text scale, iOS adapters and comprehensive full-app accessibility acceptance remain outside the current evidence. Existing historical device acceptance in the baseline repository is separate.

## Snapshot contents and hygiene

[Manifest](ui-ux-handoff-manifest.json) records the scoped source, tests, documents, generated concepts with provenance, native captures and review evidence selected for publication. It excludes unrelated local skill deletions, local.properties, local credentials/cache, configuration edits, APKs and emulator/browser debug logs. Baseline tracked repository files remain unchanged except the selected UI changes.

The snapshot was assembled with a separate Git index, preserving the working branch, existing index and unrelated checkout changes. Do not assume every historical document's scope sentence is current; consult the dated extension handoffs and the GitHub issue. No real AC operation was authorized by publishing this handoff.
