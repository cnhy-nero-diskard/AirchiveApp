## Why

`main` has no CI: there is no `.github/workflows` directory, and the only ruleset (`default`) lets repository roles and one integration (ID `1144995`) bypass every rule. Verification today is whatever someone runs locally. Issue #4 requires checks that actually block merges before a release pipeline can rely on version bumps arriving through validated PRs.

## What Changes

- Add a pull-request and `main` workflow that needs no secrets. It runs:
  - shared host tests
  - debug APK and debug test-APK assembly
  - Android debug lint, failing on any finding not recorded in a committed baseline
  - a vulnerability check of the dependency set, which is pinned by newly committed Gradle lockfiles so resolution is reproducible and dependency changes show up in diffs

  Test and lint reports are uploaded as evidence artifacts.
- Add a summary job that fails when any job it depends on failed, was cancelled, or was skipped. Required workflows use no path filters, so a check can never stay pending forever or report a skip as success.
- Add a scheduled, manually dispatchable instrumentation workflow. It runs `connectedDebugAndroidTest` on an Android emulator under the existing Firebase Auth and Firestore emulator harness (`firebase.test.json`). It never passes the opt-in arguments of the live production verification tests. The previously failing `SharedInstrumentUiTest` cases were repaired in PRs #6 and #7. Some tests take several minutes each and the suite needs small batches, so it starts as a nightly, non-required run and becomes required after a sustained green record. Its report distinguishes tests executed, failed, and skipped from tests only compiled.
- Add an advisory CodeQL workflow for Kotlin. It is not required until it extracts this project's Kotlin version cleanly.
- Turn on GitHub secret scanning, including non-provider patterns, and push protection. All of them are currently disabled on this public repository.
- Add a second ruleset on `main` with **no bypass actors**. It requires a pull request (0 approvals, since a sole maintainer cannot approve their own PR) and the summary check. The existing `default` ruleset is unchanged. The documented break-glass is the owner temporarily disabling this ruleset, which shows in its history.
- Fork PRs run only under `pull_request`. No workflow uses `pull_request_target` with PR code.
- **BREAKING (workflow):** direct pushes to `main` stop working. This includes the version-bump mode of the `merge-pr` skill (`.claude/` and `.codex/` copies), which moves to a PR. Its immediate-merge fallback will be refused while required checks are pending or failing, which is intended.

## Capabilities

### New Capabilities
- `ci-gates`: Repository verification that runs without secrets on every pull request, and the rule that a failed, cancelled, skipped, or missing required check blocks merging to `main`.

### Modified Capabilities

None.

## Impact

- New `.github/workflows/` (PR verification, scheduled instrumentation, and CodeQL).
- New `.github/rulesets/` holding the reviewed ruleset definition.
- Build configuration changes, with no app behaviour change:
  - `androidApp/build.gradle.kts` gains a `lint` block and a committed `lint-baseline.xml`
  - the Gradle builds enable dependency locking, with committed `gradle.lockfile`s
- Repository settings, applied with the owner's confirmation:
  - the new `main` ruleset
  - secret scanning and push protection

  Auto-merge is already allowed.
- `.claude/skills/merge-pr/SKILL.md` and `.codex/skills/merge-pr/SKILL.md` version-bump mode.
- `docs/` gains a short CI and branch-gate note, including the break-glass procedure.
- No app code or release signing material is involved. `release-pipeline` depends on this change.
