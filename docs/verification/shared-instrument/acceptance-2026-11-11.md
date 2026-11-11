# Remaining-change acceptance — 2026-11-11

## Revision and scope

Starting revision: `ed670431a111b586b0e618ba84990d4d5edd0135` (`main`).
Working branch: `chore/finish-openspec-acceptance-1`.

This follow-up covers the five remaining tasks across `deliver-shared-instrument-ui`,
`adaptive-landscape-navigation`, `discrete-controls-and-power-header`, and
`foreground-refresh`. Earlier verification and captures keep their original revision
attribution; they are not relabelled as current results.

## Bounded independent review

A separate reviewer inspected the actual source, focused tests and recorded evidence
at the starting revision. No confirmed material source defects were found. Reviewed:

- Owner/credential generation fences, protected credential commits and invalidation
  in `ThinQContextCoordinator`, `ThinQLifecycleBoundary`, and setup/invalidation tests.
- Device-global command ownership and the final pre-send guard in `KtorThinQClient`.
- Shell-held temperature drafts, context-bound Settings forms and feedback, and
  presentation fences in `ProductionAirchiveApp` and `HomeInstrument`.
- Simple/Detailed disclosure defaults and visible/expanded semantics in `InstrumentLayout`.
- Future-slot exclusion, known-unit normalization, coverage/source distinctions in
  `EnergyHistoryPresentation`, and virtualized partition-bound History selection.
- Debug-only non-exported preview manifest and revision-linked verification evidence.

The reviewer treated paused temperature sending, foreground scheduling, automatic
historical-range loading, discrete sliders and adaptive navigation as intentional later
changes. The older proposal's Apply/explicit-download wording was not treated as a
current regression. Historical full TalkBack, phone and packaged-release limits were
retained. No material source finding required a corrective UI batch.

## Physical phone and TalkBack — owner-reported

The owner answered **“no issues”** to the following bounded acceptance request in this
session: check portrait and landscape at normal and maximum text size; confirm all five
tabs are reachable, labels/readings are spoken in sensible order, and mode/fan controls
announce current values and adjustment actions. The request explicitly excluded sending
AC commands. This is owner-reported acceptance, not assistant-observed speech or phone
instrumentation. Phone model, OS, TalkBack version and exact maximum scale were not
supplied, so none are inferred. Earlier synthetic phone/emulator results retain their
original attribution.

## Foreground lifecycle verification

Extracted the existing timer collection into `ForegroundRefreshLoop`, used by both
production loops. History still attempts immediately subject to its durable freshness
gate; live state waits five minutes to avoid duplicating entry. Each loop retains its
monotonic deadline/backoff across Activity pause/resume, and `collectLatest` cancels work
on context changes. The live schedule key includes coordinator identity as well as the
owner/credential key, so replacement graphs do not inherit another graph's schedule.
`MainActivity.onPause` still cancels both jobs. No new polling policy, profile request,
command retry, backend operation, or dependency was added.

Three Android instrumentation tests run the production loop in an empty debug-only
Activity with synthetic callbacks and accelerated monotonic time:

- Pause cancels an in-flight read; resume observes the latest owner; sign-out stops work.
- Failure backoff survives pause/resume; an owner change cancels the old deadline.
- The live loop waits its baseline; context changes and pause cancel in-flight work.

Existing host tests cover the five-minute freshness boundary, manual bypass, bounded
backoff/reset, owner-result fencing, and the shared non-queuing read/command slot.
No production Firebase, protected credential or AC graph is created by the lifecycle host.

## Verification results

- Shared Android host tests: **495 passed, 0 failed, 0 skipped**.
- Android debug app/test builds and debug lint: **passed**.
- Focused native checks on `emulator-5554`, `Medium_Phone_API_37.0`, Android 17/API 37,
  host GPU confirmed by `scripts/test-emulator.sh start`: **11 distinct tests passed**
  across the batches below (including corrected tests).
- Strict OpenSpec validation: **16 passed, 0 failed**; the pre-existing informational
  bootstrap archive conflict remains.
- `git diff --check`: passed.

Commands used the wrapper and always targeted `ANDROID_SERIAL=emulator-5554`:

```sh
ANDROID_SERIAL=emulator-5554 ./gradlew :shared:testAndroidHostTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest :androidApp:lintDebug
ANDROID_SERIAL=emulator-5554 ./gradlew :androidApp:connectedDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=<selected classes/methods below>'
openspec validate --all --strict --no-interactive
```

The initial nine-test batch passed all three `ForegroundRefreshLifecycleTest` methods,
plus these `SharedInstrumentUiTest` methods: `modeSliderSendsOnceOnRelease`,
`unknownSelectorHasNoThumbAndSendsOnlyAfterAChoice`,
`temperatureSendsFinalValueAfterOneSecondAndStaleStateDisablesControls`,
`interfaceDetailSelectionPreservesSetupFormAndConfirmationDialogWithoutPortCalls`,
and `productionDisclosureDefaultsRespectVisibleRouteSections`. The tapping test
initially failed with zero submissions after its fixed 100 ms sleep.

One attempted visibility guard incorrectly required the accessibility node itself to
have the entire 48 dp touch-target height. That assumption caused both slider tests
to fail; it was removed. Slider accessibility bounds and the expanded native touch
target are distinct. The helper now waits for idle and clears cached accessibility
nodes after scrolling, and the tap test awaits exactly one submission rather than a
fixed sleep. A focused tap probe passed. The landscape test also exposed an obsolete
expectation for the removed “Refresh history” button; it now invokes the actual
accessible “Reload history” action, verifies a new synthetic telemetry read, and
checks that the visible “Open Settings” action remains above navigation.

The final normal-scale batch passed all three selected methods:
`modeSliderSupportsTappingAStop`, `modeSliderSendsOnceOnRelease`, and
`productionShellPhoneLandscapeKeepsRoutesReachable`; final debug lint passed too.
The separate 2.0-font-scale batch passed
`productionLandscapeAtLargeTextKeepsDestinationsReachable`, checking all five
selected destinations. The effective font scale is asserted inside that test, and the
emulator's original 1.0 scale was restored by shell cleanup. No additional cosmetic
review loop or UI redesign was performed.

The lifecycle host exercises real Android Activity pause/resume with synthetic work;
it does not instantiate `MainActivity`'s production account/credential graph. The same
extracted loop is wired to that Activity's existing resume/pause jobs; coordinator
freshness, context fences, and command exclusion are covered separately by host tests.

Gradle generated an untracked local `buildSrc/.gradle/` cache (64 KiB). It is not part
of the source changes and was preserved; broader cleanup remains separate.

## Spec maintenance

Long requirement clauses were moved into named scenarios without dropping the normative
statements. The corresponding later safety/energy deltas preserve those scenario names
with their newer behavior, preventing archive from deleting scenarios. Strict validation
passes all 16 active changes/main specs. The existing informational bootstrap archive
conflict remains: its ADDED route requirement already exists and has been superseded.
At the acceptance stage, no change was archived or behaviorally synced as a side effect. The subsequent explicitly requested sync/archive is recorded below.

## Limits

- No assistant-run phone test, real AC command, production account/data mutation,
  private signing, or release install was performed.
- This pass does not certify packaged signed-release isolation, iOS, tablets, or the
  historical failed/open pixel gate.
- Automated accessibility-node checks are distinct from the owner's reported TalkBack pass.

## Tested source content fingerprints

These SHA-256 values identify the uncommitted source tested above, independently of
subsequent evidence/task-only edits.

- `androidApp/src/main/kotlin/cloud/airchive/personal/MainActivity.kt`: `fb50fb6990ca4d3c8b389eab50d09d490140ab4e509cf43599baa4a129583112`
- `shared/src/commonMain/kotlin/cloud/airchive/shared/data/sync/ForegroundRefreshLoop.kt`: `92e378f5b1149da0e2552dcc214389384774d297348390fa2e975d345bb0faba`
- `androidApp/src/debug/AndroidManifest.xml`: `61ccb8800f79a05fdae26b2c91d5d959d5546be6bd742a51d57ced1d88cbf729`
- `androidApp/src/debug/kotlin/cloud/airchive/personal/preview/ForegroundRefreshTestActivity.kt`: `8f9f27bb4d63999e912c0738b600518032bdf22aab69c4380e386481f6014063`
- `androidApp/src/androidTest/kotlin/cloud/airchive/personal/preview/ForegroundRefreshLifecycleTest.kt`: `c5167046acdef447f760a2c895f3b9fcfc568b10d1eff2593e6c0b380a9e7b0e`
- `androidApp/src/androidTest/kotlin/cloud/airchive/personal/preview/SharedInstrumentUiTest.kt`: `dcf042311f4a409bb9720718bdd7601d5e4e47849f05a96003d44c11a13e38c0`

## Requested sync and archival

All nine completed changes were synchronized and archived on 2026-11-11, in this order:

1. `add-personal-airchive-mobile-app`
2. `deliver-shared-instrument-ui`
3. `adaptive-landscape-navigation`
4. `discrete-controls-and-power-header`
5. `improve-hourly-energy-history`
6. `foreground-refresh`
7. `owner-and-control-safety`
8. `add-pr-ci`
9. `in-app-update`

Each is under `openspec/changes/archive/2026-11-11-<name>/`. All artifacts and
tracked tasks were checked complete before archival. Current spec instructions had no
additional artifact rules, and archive instructions supplied no additional context
or guidance. Every delta path came from its change's status response. Each main spec
was validated before its change was moved, and each delta's requirement/scenario
headings were checked present. Existing spec titles and Purpose sections were
preserved, and existing scenarios were retained when later deltas replaced bodies.

The bootstrap was already synced; its route and release requirements were preserved
with the newer updater/public-GitHub-release extensions rather than overwritten by
the historical baseline. Later changes supersede the earlier explicit Apply,
eight-three-hour-bin, explicit historical-download, bottom-navigation-only and
ten-minute refresh behavior. The new `discrete-control-presentation` main spec has
an authored Purpose, not an unresolved placeholder. The existing archived
`2026-11-08-release-pipeline` change and release-distribution spec were preserved.

Final reconciliation also makes same-context live-state reuse explicit in the older
Device-entry and interface-detail scenarios, and states that navigation cancels an
unsent temperature countdown without rearming a restored draft. These are the
already-tested behaviors in `tabEntriesReuseCurrentAndFailedReadingsUntilAnExplicitRefresh`
and `navigationCancelsUnsentTemperatureAndReturningDoesNotRearmIt`; no new application
behavior was introduced by sync. Historical requirement names remain stable even
where later deltas have superseded their original wording.

Validation after archive: all eight main specs pass strict validation; all ten
archived changes (including release-pipeline) pass archived-task validation;
`openspec list --json` has zero active changes. Source fingerprints above still
match the tested application/test files, so the acceptance test results remain
applicable without rerunning unchanged application code.
