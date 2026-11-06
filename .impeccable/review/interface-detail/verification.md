# Interface detail verification

2026-10-27. Saved Settings > Interface detail choice (Simple default / Detailed), applies immediately across all production screens. Presentation only; no data or device command authority changes.

- Build, Android debug/instrumentation APKs, lintDebug: passed (../interface-detail-build.log).
- Shared host tests: 314, zero failures/errors, including absent/unknown preference fallback.
- Native class: six tests passed (../interface-detail-native.log). Covers switching modes, a cross-screen Device description change, activity recreation, a new preference/Activity instance retaining the choice, Simple restoration, explicit Apply/stale state, period selection, sample detail, cache cancellation, and captures.
- Font1.3 capture test: one pass (../interface-detail-large-text.log).
- Eight current Settings first-viewport screenshots: Simple/Detailed × light/dark × font1.0/1.3. Native Compose UI over debug-only in-memory data; preference file is isolated from production. All8 opened and validated by root.
- Initial instrumentation was blocked by a SystemUI ANR dialog after cold boot. Dismissing that system dialog was followed by a clean complete rerun. No app code repair was made for that issue.
- Phone Android15, Medium_Phone_API_35,1080x2400 density420, emulator-5640; font restored1.0 and tests restore original system theme.

Limits: emulator/fixture evidence, not real AC, physical hardware, production sign-in, iOS, tablet, landscape or maximum-font acceptance. Android persistence was tested across Activity recreation and fresh preference/Activity instances, not a forced OS process kill. Old Home pixel gate and historic remaining-route captures are untouched. No generated raster or screenshot ships as the UI.
