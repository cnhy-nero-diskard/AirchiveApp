# Shared Instrument verification

Implemented 2026-10-24 for native Android/Compose. The owner selected Home Instrument and then Shared Instrument. No commit, push, production credential access, or real AC command was performed for this work.

## Confirmed checks

- Shared host suite: 313 tests, zero failures (including profile/live-state/command gating, numeric formatting, grouped coverage and usable-sample freshness).
- Android debug app and instrumentation APK builds passed.
- Android debug lint passed.
- Native offline preview test class: two tests passed, covering explicit Apply versus local draft, stale-state control gating, and light/dark captures.
- Additional screenshot runs passed at 1.3 text scale and at the comp's exact 863x1823 viewport.
- Emulator: Medium_Phone_API_35, Android15. Theme, font, viewport/density, and animation settings used for verification were restored. Preview data and UI actions were synthetic; the preview activity is debug-only and not exported.

## Native finish review

Independent reviewer disposition after one fix batch: six application findings resolved; no observed native UI defect warrants another batch within the scored scope. Fixed readout scale/divider and record-title weight, readable three-hour bars with gaps/partial coverage, selector cues and gear icon, usable-sample freshness, explicit failed-read copy/retry, and system-bar contrast. Source and captures were rechecked. Initial reviewer claim that retry was hidden is withdrawn: it was always rendered independently of setupMessage; the misleading failure copy was repaired.

## Comp fidelity and tooling limits

Approved comp: .impeccable/mocks/home-shared.png. Custom grounded-world quality reference: .impeccable/mocks/decision/home-instrument.png. There is no selected foreign catalog world.

Native screenshots are real emulator captures, not image-generated screenshots. The comparable capture is .impeccable/review/hero-repro.png. Final diagnostic comparisons are in .impeccable/review/diff/final, copied from the diagnostic run over the final native render; report.json retains the actual failing score/vetoes. This is not a new passed gate.

The automated pixel gate remains open and failed (approximately75% aggregate, with hard region mismatches). It compares fixed image crops, marking visibly present native controls as missing. Original grid-registration errors were corrected to explicit normalized boxes. Remaining native differences include a real disabled Apply button until a draft changes, a real Material switch, platform glyphs, native navigation/system bars, responsive type, real-data/source labels, and scrollable source context. The scripted gate is NOT represented as passed, forced, or waived; .impeccable/build/state.json retains its failure. The independent native review is the evidence for the listed application fixes; it does not certify exact pixel reproduction. Do not resume a redesign merely to eliminate these diagnostic flags without checking this record and the native behavior first.

## Validation boundaries

- Screens use a labeled offline preview fixture. Existing production controllers/history are wired into the new Home, but a signed-in physical-phone acceptance pass remains outstanding.
- No additional real device control acceptance is claimed. Existing historical acceptance records remain separate.
- Phone portrait, light/dark, and 1.3 text scale were captured. Tablets, landscape and maximum system text scale were not newly validated.
- At enlarged text, readings stack and lower energy detail requires scrolling. This is intentional native accessibility behavior.
- The change provides the shared color/type/shape system across production routes and the new Home composition. It does not claim every secondary route has been recomposed to match a newly generated screen.
