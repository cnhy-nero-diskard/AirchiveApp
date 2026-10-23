---
name: make-pr
description: Open a GitHub pull request for the current branch. Pushes the branch, runs pre-PR verification (unit tests plus the Roborazzi baseline-currency check), re-records stale screenshot goldens before opening when the UI changed, and creates the PR with gh. Use when the user asks to open, create, or make a PR or pull request.
allowed-tools: Bash(git:*, gh:*, ./gradlew:*, python:*, powershell:*)
license: MIT
compatibility: Requires gh CLI (authenticated) and Git.
metadata:
  author: cline
  version: "1.0"
---

# Make PR

Open a pull request for the current branch against `master`, after making sure CI
will not fail on something checkable locally — above all the Roborazzi screenshot
gate, which is the most common surprise: any edit under a path in
`FIXTURE_RENDERED_SOURCES` (`scripts/check_visual_baseline.py`) without a matching
baseline change fails both the `Visual baseline check` workflow and
`:app:verifyRoborazziDebug`.

## 1. Preconditions

- Be on the feature branch the PR should come from. Never create a PR from `master`.
- The branch must have commits ahead of `master`:
  ```bash
  git log --oneline master..HEAD
  ```
  Nothing ahead? Stop — there is no PR to open.
- The working tree must be clean (`git status --porcelain` empty). If dirty, stop
  and ask; never smuggle uncommitted work into the PR.

## 2. Roborazzi pre-check (seconds, before Gradle)

Reproduce the `Visual baseline check` workflow locally. Build the changed-file list
against the merge base and run the same script CI runs:

```bash
git merge-base HEAD master
git diff --name-only <merge-base>...HEAD > "$RUNNER_TEMP/changed.txt"   # bash (CI)
# PowerShell: git diff --name-only <merge-base>...HEAD > "$env:TEMP/changed.txt"
python scripts/check_visual_baseline.py check \
  --changed-file "$RUNNER_TEMP/changed.txt" \
  --labels '[]'
```

- Exit 0 with no fixture inputs changed: nothing to do, continue to step 3.
- Exit 1: the branch edits rendered UI and records no baseline. Confirm the UI
  change is intentional (review the diff), then re-record:
  ```powershell
  .\gradlew.bat :app:recordRoborazziDebug `
    --tests com.example.backlogium.ui.screenshot.RoborazziSpikeTest `
    --tests com.example.backlogium.ui.screenshot.MainScreenScreenshotNarrowTest `
    --tests com.example.backlogium.ui.screenshot.MainScreenScreenshotStandardTest
  ```
  Recording re-renders the whole suite, so `git status -- app/src/test/snapshots/`
  may show PNGs beyond the edited screen — that is expected. Review that every
  changed PNG corresponds to an intended pixel change (spot-check the diff), then
  verify before committing:
  ```powershell
  .\gradlew.bat :app:verifyRoborazziDebug `
    --tests com.example.backlogium.ui.screenshot.RoborazziSpikeTest `
    --tests com.example.backlogium.ui.screenshot.MainScreenScreenshotNarrowTest `
    --tests com.example.backlogium.ui.screenshot.MainScreenScreenshotStandardTest
  ```
  Commit only the baselines, separately from code, and push:
  ```bash
  git add app/src/test/snapshots/
  git commit -m "test: (refresh <screen> screenshots for <reason>)"
  git push
  ```
  Never commit anything under `app/build/` (actual/compare/report outputs) —
  those are ignored build artifacts, not baselines.
- If the covered edit provably cannot move a rendered pixel (ViewModel-only edit,
  log line, untouched screen), do not re-record: note in the PR body that the
  `no-golden-change` label applies, and apply the label after creating the PR
  (`gh pr edit <number> --add-label no-golden-change`).

## 3. Verification (scoped to what changed)

CI runs everything; locally, run what the branch touches:

```bash
./gradlew test                        # always: Android unit tests + compileReleaseKotlin happens in CI
./gradlew lintDebug                   # always: cheap enough to just run
npm --prefix functions run build && npm --prefix functions test   # when functions/ changed
python -m unittest discover -v        # in scripts/, when scripts/ changed
```

Never open the PR on red verification. The instrumented `MigrationTest` only runs
in CI (needs the pinned emulator); no local equivalent is required.

## 4. Validate the release note before creating

The `Release note check` workflow reads the `## Release note` section from the PR
body and falls back to the raw title when it is missing. Reproduce it locally
before opening:

```bash
printf '%s' "<proposed body>" > /tmp/pr-body.md   # PowerShell: Set-Content -NoNewline
python scripts/release_notes.py check --body-file /tmp/pr-body.md --title "<proposed title>"
```

Rules the body must satisfy: 1–2 bullets of user-visible outcome in plain
language, each under 180 characters, no implementation details, or exactly
`None` when nothing user-visible changed. User-visible changes need a
conventional title prefix (`feat:`, `fix:`, `perf:`); anything else files under
Maintenance.

## 5. Push and create

```bash
git push -u origin <branch>   # -u only when no upstream exists
gh pr create --base master --title "<prefix>: <summary>" --body "<filled template>"
```

Fill every template section (`Summary`, `Changes`, `Release note`, `Testing`,
`Notes`); check the `Testing` boxes that were actually run, including the
Roborazzi line. If `no-golden-change` applies, say why in `Notes` and add the
label.

## 6. Report

```
## PR ready

**PR:** #<n> — <title> (<url>)
**Head:** <branch> @ <short-sha>
**Pre-PR checks:** unit tests ✓, lint ✓, baseline-currency ✓ (re-recorded N PNGs / no UI touched / `no-golden-change`)
**Watch:** `gh pr checks <n> --watch`
```

## Guardrails

- Never open a PR from `master`, and never target any base other than `master`.
- Never re-record baselines for a UI change you have not reviewed — a pixel diff
  you did not intend is a bug, not a stale golden.
- Never stage files unrelated to the PR; never commit secrets or build artifacts
  (respect `.gitignore`); never commit `app/build/` Roborazzi outputs.
- Never force-push. If the push is rejected because the remote moved, stop and ask.
- If verification cannot be made green, report and stop — do not open a PR you
  know CI will reject.
