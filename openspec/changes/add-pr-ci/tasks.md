# Tasks

## 1. Reproducible build inputs

- [x] 1.1 Enable strict Gradle dependency locking in `:shared` and `:androidApp` for the configurations in design §4. Add the root task that resolves them, generate the `gradle.lockfile`s, and document `--write-locks` in `docs/ci.md`. Verify:
  - `./gradlew :shared:testAndroidHostTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest` passes on the Mac
  - a temporary version change in `gradle/libs.versions.toml` without `--write-locks` fails and names the configuration (then revert it)
- [x] 1.2 Add the `lint {}` block from design §3 to `androidApp/build.gradle.kts` and generate `androidApp/lint-baseline.xml`. Verify:
  - `:androidApp:lintDebug` passes, and its report still lists the baseline findings
  - a temporary new lint warning fails the task (then revert it)
- [x] 1.3 Document the baseline and the disabled time-dependent checks in `docs/ci.md`. Verify the section names every baseline finding and explains why `GradleDependency`, `NewerVersionAvailable`, and `AndroidGradlePluginVersion` are disabled.

## 2. Required verification workflow

- [ ] 2.1 Add the `build` job in `.github/workflows/verify.yml` (design §1–2), triggered on `pull_request` to `main` and `push` to `main` with no path filters, with `contents: read`, SHA-pinned actions, Temurin 25, the explicit SDK install, `setup-gradle`, and the single `--continue` invocation. Verify on the branch's PR that:
  - the run executes all shared host tests
  - it uploads test and lint reports that identify the commit
- [ ] 2.2 Add the `dependencies` job: OSV-Scanner over the lockfiles, failing on high or critical advisories, with a committed exceptions config that requires a reason and expiry for each entry. Verify:
  - the job passes on the branch
  - a temporary lockfile entry pinned to a version with a known critical advisory fails it and names the advisory ID (then revert it)
- [ ] 2.3 Add the `required` job (`needs: [build, dependencies]`, `if: always()`, which fails unless every result is `success`). Verify with temporary commits, each reverted:
  - a failing host test makes `required` fail
  - a covered job forced to skip makes `required` fail
  - a documentation-only change still produces the `required` check
- [ ] 2.4 Check least privilege. Verify:
  - each run's "GITHUB_TOKEN Permissions" log shows only read access
  - `grep -rn -E 'pull_request_target|secrets\.' .github/workflows` returns nothing
  - every `uses:` line references a full commit SHA

## 3. Advisory instrumentation and code scanning

- [ ] 3.1 Add the instrumentation summary script, which reads JUnit XML, writes executed/passed/failed/skipped counts to the job summary, and exits non-zero on any failure or zero executed tests. Verify it against fixture XML files for these cases:
  - all passed
  - one failure
  - live tests skipped
  - no tests executed
- [ ] 3.2 Add `.github/workflows/instrumentation.yml` (design §5): daily schedule and manual dispatch, KVM, an x86_64 API 36 emulator, pinned `firebase-tools`, the `firebase emulators:exec` command with no live-test runner arguments (class filters for batching are allowed), and a guard step that fails if any workflow mentions `liveAccessCheck` or `liveHistoryCheck`. Verify a manual dispatch:
  - runs the suite
  - reports both `Live*VerificationTest` classes as skipped
  - passes every batch, or names each failing test
  - uploads JUnit XML and logcat
- [ ] 3.3 Document the batch list and the promotion rule (seven consecutive green scheduled runs) in `docs/ci.md`. Verify that every `androidTest` class appears in exactly one batch.
- [ ] 3.4 Add `.github/workflows/codeql.yml` (design §6): `java-kotlin` with a manual `assembleDebug` build, `security-events: write` as the only write, and runs on pull requests, `main`, and weekly. It is not part of `required`. Verify a run completes, and record in `docs/ci.md` whether Kotlin extraction was complete.

## 4. Gate definition, tooling, and docs

- [ ] 4.1 Add `.github/rulesets/main-gate.json` per design §7. Verify `jq` confirms all of:
  - `bypass_actors` is `[]`
  - the single required context is `required` with `integration_id` 15368
  - `strict_required_status_checks_policy` is false
  - the `pull_request` rule has 0 required approvals
- [ ] 4.2 Rewrite the version-bump mode in `.claude/skills/merge-pr/SKILL.md` and `.codex/skills/merge-pr/SKILL.md` to work through a `chore/bump-version-<name>` branch, a PR, and `gh pr merge --auto --merge`. Verify:
  - neither copy contains a direct `git push` to `main`
  - the two sections are identical
- [ ] 4.3 Complete `docs/ci.md`. It covers:
  - what runs, and which check is required
  - the local command that reproduces `build`
  - lockfile updates and the lint baseline
  - the instrumentation batches, counts, and promotion rule
  - the live-test guard
  - CodeQL status
  - the identity of integration `1144995`, as reported by the owner
  - the emergency procedure: disable the gate ruleset, land the change, re-enable it, and note the window in the PR

  Verify the documented local command runs green on the Mac.

## 5. Rollout

- [ ] 5.1 Open the PR referencing #4 and merge it once its own `required` check is green. Verify:
  - the PR's evidence artifacts download
  - the `push` run on `main` reports `required` green
- [ ] 5.2 With the owner's confirmation, enable secret scanning, non-provider patterns, and push protection. Verify `gh api repos/cnhy-nero-diskard/AirchiveApp --jq .security_and_analysis` shows each as `enabled`.
- [ ] 5.3 With the owner's confirmation, create the gate ruleset from `.github/rulesets/main-gate.json`. Verify `gh api repos/cnhy-nero-diskard/AirchiveApp/rulesets/<id>` shows it `active`, with no bypass actors and the expected rules, and that the `default` ruleset is unchanged.
- [ ] 5.4 Verify the gate end to end: open a throwaway draft PR containing a failing host test, and confirm `gh pr view --json mergeStateStatus` reports `BLOCKED` and `gh pr merge` is refused. Then close it and delete its branch. Record the result in the PR thread of 5.1.

## Workflow follow-up

- After merge, sync the `ci-gates` spec and archive this change, as the `merge-pr` gate requires.
- Start `release-pipeline` once the gate is active.
