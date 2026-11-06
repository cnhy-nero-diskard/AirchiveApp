# Native verification - remaining screens

2026-10-27. Scope: production Device, Energy, History, Settings, and shared configuration/signed-out framing. Established Shared Instrument extension, with no replacement palette or font.

- Gradle shared host tests: 313 tests, zero failures/errors.
- Android debug and instrumentation APK builds: passed.
- Android debug lint: passed.
- SharedInstrumentUiTest: four tests passed, including temperature Apply/stale gating, day/week/month selection, cached sample detail and cancellation of cache deletion.
- Enlarged text capture test: one passed at font scale 1.3.
- Native screenshots: 16 files, one for every Device/Energy/History/Settings × light/dark × 1.0/1.3 combination. First viewport captures, not full scroll contents. Native source is the real route code over a debug-only in-memory fixture. Fixture data and fake owner/device labels are explicit. No Firebase, protected real credential or real ThinQ/AC graph is constructed.
- Phone: Medium_Phone_API_35, Android 15, 1080×2400, density 420. Windows reserved the default emulator ports; emulator-5640 was used successfully without changing Windows networking.
- Font scale restored to 1.0; capture tests restore original system night mode.
- Logs: ../routes-build.log, ../routes-instrumentation.log and ../routes-large-text.log.

Independent finish review is recorded alongside this file. A screenshot confirms visible layout, not the complete behavior of every offscreen state. Tablets, landscape, maximum font scale, live signed-in hardware acceptance and real AC commands were not verified. The old Home comp pixel gate remains failed/open; this extension does not change or certify it.

Finish: the independent review found one P2 inset-zero Settings disclosure clipping defect. The RectangleShape correction was rebuilt and the same native matrix recaptured. finish-verdict.md scores P2 resolved, disposition ship, within the reviewed phone theme/text-scale scope. It does not certify untested hardware or offscreen scenarios.
