# Design

## Context

See proposal.md for motivation and specs/ci-gates/spec.md for the required behaviour.

**Current state of `main` at `f07764e`:**

*Build toolchain*
- Gradle `9.7.0`, AGP `9.5.0`, Kotlin `2.4.20`, with two modules (`:shared` and `:androidApp`).
- `gradle/gradle-daemon-jvm.properties` requires a Java 25 daemon. Locally that is Android Studio's JBR 25.
- `gradle.properties` enables the configuration cache.
- `:shared` also declares `iosArm64` and `iosSimulatorArm64`. These are disabled on Linux through `kotlin.native.ignoreDisabledTargets=true`.

*What CI needs to build*
- A debug build needs only the tracked `config/airchive-mobile.properties`. There is no google-services plugin.
- `local.properties` is ignored by git, so CI provides the SDK through `ANDROID_HOME`.

*Today's local verification (the `make-pr` skill)*
- `:shared:testAndroidHostTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest :androidApp:lintDebug`
- 383 host tests pass.
- Debug lint at `f07764e` (re-run 2026-11-07) reports 0 errors, 6 warnings, and 1 hint: `OldTargetApi`, `GradleDependency`, `DataExtractionRules`, `MissingApplicationIcon`, `UseKtx`, `ObsoleteLintCustomCheck`, and `AutoboxingStateCreation`. `ObsoleteLintCustomCheck` is missing from the PR #3 record.
- No `lint {}` block is configured.

*Instrumentation tests*
- There are 11 `androidTest` classes. The Firebase-backed ones reach the Auth and Firestore emulators at `10.0.2.2:9099/19080` through the `firebase.test.json` harness (project `demo-airchive-telemetry-poller`).
- `LiveOwnerAccessVerificationTest` and `LiveHistoryCacheVerificationTest` address production. They are skipped via `Assume` unless the runner receives `airchive.liveAccessCheck=true` or `airchive.liveHistoryCheck=true`.
- The previously failing `SharedInstrumentUiTest` cases were test-side defects (non-scrolling waits, stale expectations, a selection race). They were repaired in PRs #6 and #7. Single tests take up to about 6 minutes, and batches larger than a few tests can time out.
- `AGENTS.md` and `scripts/test-emulator.sh` require host-GPU rendering for the Mac emulator. That rule applies to the owner's M5. A hosted Linux runner has no GPU, so CI uses the runner's software renderer.
- There is no standalone Firestore rules test suite.

*Repository settings*
- The repository is public.
- Auto-merge is allowed.
- Secret scanning, non-provider patterns, push protection, and Dependabot security updates are all disabled.
- The `default` ruleset (`25732031`) has deletion, non-fast-forward, update, and creation rules. Repository roles 2 and 5 and integration `1144995` have `always` bypass on it.
- The `merge-pr` skill merges with `gh pr merge --auto --merge`. Its version-bump mode pushes straight to `main`.

## Goals / Non-Goals

**Goals:**
- One deterministic required check whose result depends only on the repository contents at the tested commit.
- Fork pull requests are safe by construction: no secrets, and no write access beyond uploading analysis results.
- A gate that even the owner cannot quietly skip, with one visible emergency override.

**Non-Goals:**
- Release signing and publishing (`release-pipeline`).
- iOS compilation. It needs macOS runners, and iOS is a scaffold.
- Making instrumentation a required PR check. It is too slow for every PR today, and is promoted after a green record.
- Burning down the lint baseline. That belongs to the later hardening change.
- Dependabot version-update PRs and required reviews.

## Decisions

### 1. Three workflows, one required check

**`verify.yml`** runs on `pull_request` to `main` and on `push` to `main`, with no path filters.
- Permissions are `contents: read`.
- Concurrency is grouped by ref, and in-progress runs are cancelled only for pull requests.
- **Job `build`:** one Gradle invocation with `--continue`, running `:shared:testAndroidHostTest :androidApp:assembleDebug :androidApp:assembleDebugAndroidTest :androidApp:lintDebug`. One invocation shares configuration and cache, and `--continue` reports every failing task in a single run. Reports are uploaded with `if: always()`.
- **Job `dependencies`:** scans the committed lockfiles for vulnerabilities (§4). It needs no Gradle setup and runs in parallel with `build`.
- **Job `required`:** `needs: [build, dependencies]` with `if: always()`. It fails unless every needed result is exactly `success`, so a failure, cancellation, or skip anywhere fails it. This job's name is the only check the ruleset requires.

Because `verify.yml` has no path filters, a documentation-only PR still produces the check. Using job-level `if:` on covered jobs would turn into a skip, which `required` treats as failure. So event-specific behaviour uses step-level conditions.

**`instrumentation.yml`** is scheduled daily and can be dispatched manually. It is not a required check (§5).

**`codeql.yml`** runs on pull requests, `main`, and weekly. It is advisory and not required (§6).

*Alternative considered:* separate required checks per job. Rejected: every job rename would need a ruleset edit, and skipped jobs would count as passing.

### 2. Toolchain

- Runner: `ubuntu-latest`.
- Java: `actions/setup-java` with Temurin 25, to satisfy the daemon JVM criteria without Gradle downloading a toolchain from foojay on each run.
- Android SDK: the platform `android-36` and build-tools `36.0.0` are installed explicitly with `sdkmanager`, so a runner-image change cannot alter what is compiled against.
- Caching: `gradle/actions/setup-gradle`, which writes the cache only from the default branch, so pull requests read it but cannot poison it.
- Third-party actions are pinned to full commit SHAs, with the version in a comment.

### 3. Lint gate

`androidApp/build.gradle.kts` gains:

```kotlin
lint {
    baseline = file("lint-baseline.xml")
    warningsAsErrors = true
    abortOnError = true
    disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
}
```

- The disabled checks compare against upstream release metadata, so their results change with time rather than with the repository. Dependency currency is handled by the lockfiles (§4).
- `OldTargetApi` stays enabled. Its knowledge comes from the AGP version in the repository, so it is deterministic.
- The baseline is generated once and records today's remaining findings. Any new finding fails, and baseline findings remain in the report for the hardening change to clear.
- Lint runs on the debug variant. On the release variant, lint would pull in `preReleaseBuild`, which depends on the signing preflight, and CI must never have signing material.

### 4. Locked dependencies and vulnerability scanning

- **Locking.** Both modules enable Gradle dependency locking for the configurations CI resolves on Linux: the Android compile and runtime classpaths for debug, release, and tests, plus the classpaths used by the shared host tests. This produces one committed `gradle.lockfile` per module.
- **Updating locks.** A documented root task resolves those configurations, and `./gradlew <task> --write-locks` refreshes the lockfiles.
- **Strict mode.** Strict locking makes any build that resolves differently from the lockfile fail with the affected configuration named. This satisfies "Dependency resolution is locked".
- **Scanning.** The `dependencies` job runs OSV-Scanner over the lockfiles. It fails on any advisory of high or critical severity and lists each dependency with its advisory ID. Accepted exceptions live in a committed OSV config file, each with a reason and an expiry date.

*Alternative considered:* GitHub's dependency-review action. Rejected: Gradle builds need the dependency graph submitted per commit, which needs `contents: write` while running build scripts from the pull request. That breaks least privilege for forks, or needs a second privileged `workflow_run` workflow. Lockfiles also make builds reproducible, which issue #4 asks for.

### 5. Instrumentation run

`instrumentation.yml` runs on an `ubuntu-latest` runner with KVM enabled and an x86_64 API 36 emulator. It uses the same Java and SDK setup as `verify.yml`, plus a pinned `firebase-tools` installed through npm. The test command is:

```
firebase emulators:exec --config firebase.test.json --project demo-airchive-telemetry-poller \
  --only auth,firestore "./gradlew :androidApp:connectedDebugAndroidTest"
```

- Only `class` runner arguments are passed, for batching. The live-test arguments never are. A guard step fails the workflow if any workflow file in the repository mentions `liveAccessCheck` or `liveHistoryCheck`.
- A summary step reads the JUnit XML from `androidApp/build/outputs/androidTest-results/connected/` and writes executed, passed, failed, and skipped counts to the job summary. The run fails when no tests executed or any test failed.
- JUnit XML and logcat are uploaded.
- The suite runs in batches of a few test classes per `connectedDebugAndroidTest` invocation (with `-Pandroid.testInstrumentationRunnerArguments.class=…`) with a per-batch timeout, so one slow test cannot hang the run. A failed batch fails the run.
- After seven consecutive green scheduled runs, a follow-up change adds the run to the merge gate or to a pre-merge label-triggered check.

### 6. Advisory CodeQL

`codeql.yml` analyses the `java-kotlin` language with a manual build (`./gradlew :androidApp:assembleDebug`).
- Its job has `security-events: write` to upload results. That is the only write, and fork runs are downgraded to read-only automatically.
- It is not part of `required`. Code scanning on Kotlin 2.4.20 may lag. It joins the gate in a later change once a run shows complete Kotlin extraction.

### 7. The gate ruleset

`.github/rulesets/main-gate.json` is reviewed in the PR and applied with
`gh api --method POST repos/cnhy-nero-diskard/AirchiveApp/rulesets --input …`.
It uses the GitHub REST API payload shape: `target: "branch"`, with
`conditions.ref_name.include: ["~DEFAULT_BRANCH"]` to target only the current
default branch. It defines:
- enforcement `active`, `bypass_actors: []`
- `pull_request` with 0 required approvals and no code-owner, last-push, or thread-resolution requirements
- `required_status_checks` with `strict_required_status_checks_policy: false` and the single context `required`, restricted to `integration_id: 15368` (GitHub Actions), so no other app can satisfy the check by posting a status with the same name

`strict: false` avoids rebase churn for a solo maintainer. `pull_request` runs already test the merge result, and the `push` run on `main` catches any interaction afterwards.

The `default` ruleset is left untouched. Integration `1144995` is identified and documented, but it cannot bypass the new ruleset.

### 8. Secret scanning instead of a CI secret scanner

Secret scanning and push protection are enabled through the repository's `security_and_analysis` settings. Push protection rejects a secret before it is stored. Non-provider patterns remain disabled because this repository is user-owned; GitHub currently lists generic-pattern scanning as available for organization-owned repositories on GitHub Team with GitHub Secret Protection. Revisit this setting if the repository moves to an eligible organization. A CI scanner could only report a secret after it had already been pushed to a public repository.

### 9. `merge-pr` version bumps through a PR

In both copies of the skill, the version-bump mode now creates a `chore/bump-version-<name>` branch, commits the bump, opens a PR, and queues `--auto --merge`. `release-pipeline` later changes which file the bump edits.

## Risks / Trade-offs

- **Dependency locking in a multiplatform build can catch configurations it shouldn't** (iOS configurations resolved on a Mac but not on Linux). → Lock only the configurations listed in §4, and generate lockfiles on Linux in CI as well as on the Mac before committing. Disabled native targets are not resolved.
- **Lockfiles add friction to every dependency change.** → One documented `--write-locks` task. Strict-mode failures name the configuration.
- **A lint baseline hides existing problems.** → The baseline is committed, visible, and reported every run. The hardening change owns burning it down.
- **Required-check outages or flaky host tests block the owner.** → No automatic retries that hide failures. Reruns are visible. The documented emergency override is disabling the ruleset, which shows in its history.
- **A PR tested against an older `main` can break the combined result** (`strict: false`). → The push run on `main` flags it immediately, and later PRs stay red until it is fixed.
- **Action SHA pins go stale.** → Accepted for now. See Open Questions.
- **The emulator workflow is slow or flaky on hosted runners.** → It is outside the gate. Results are reported, never retried quietly.

## Migration Plan

1. Open the PR with the workflows, lint config and baseline, lockfiles, ruleset JSON, docs, and `merge-pr` updates. Its own `verify.yml` run must be green.
2. Merge. Confirm the `required` check reports green on `main`.
3. With the owner's confirmation, enable secret scanning and push protection. Enable non-provider patterns when the repository's GitHub ownership and plan are eligible; otherwise record the limitation and leave that setting pending.
4. With the owner's confirmation, create the gate ruleset from `main-gate.json`.
5. Check that it works:
   - open a throwaway draft PR containing a failing host test, confirm merging is refused, then close it
   - confirm `gh api` reports the ruleset with no bypass actors

   No commit is pushed to `main` to test the rejection; the ruleset definition is evidence enough.

**Rollback:** delete or disable the gate ruleset. The workflows can be removed in a PR. Lockfiles and the lint block can be reverted independently.

## Open Questions

- Whether to lint `:shared` (its Android target) in a later change. It holds most of the UI code, but has never been linted.
- Whether to let Dependabot propose updates for the `github-actions` ecosystem only, to keep SHA pins current.
- Whether to require conversation resolution on PRs, once agent reviews leave threads.
