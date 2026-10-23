---
name: make-pr
description: Open a GitHub pull request for the current branch. Pushes the branch, runs scoped pre-PR verification (shared host tests plus Android compilation, with Firebase emulator instrumentation and release-script smoke checks when touched), and creates the PR with gh. Use when the user asks to open, create, or make a PR or pull request.
allowed-tools: Bash(git:*, gh:*, ./gradlew:*, python:*, powershell:*)
license: MIT
compatibility: Requires gh CLI (authenticated) and Git.
metadata:
  author: cline
  version: "1.0"
---

# Make PR

Open a pull request for the current branch against `main`, after making sure
locally-runnable checks pass — above all the shared host tests and Android
compilation. Note the instrumentation gap: `:androidApp:assembleDebugAndroidTest`
only compiles the test APK; the Firebase-backed instrumentation tests execute
only under the local emulator harness (step 2), and iOS changes need a macOS
host.

## 1. Preconditions

- Be on the feature branch the PR should come from. Never create a PR from `main`.
- The branch must have commits ahead of `main`:
  ```bash
  git log --oneline main..HEAD
  ```
  Nothing ahead? Stop — there is no PR to open.
- The working tree must be clean (`git status --porcelain` empty). If dirty, stop
  and ask; never smuggle uncommitted work into the PR.

## 2. Emulator-backed instrumentation (only when Firebase/instrumentation touched)

`:androidApp:assembleDebugAndroidTest` compiles the test APK but does not execute
it. When the branch touches androidApp Firebase code, `tests/firebase/firestore.rules`,
or instrumentation tests, run them under the local emulator harness:

```powershell
firebase emulators:exec --config firebase.test.json --project demo-airchive-telemetry-poller --only auth,firestore "gradlew.bat :androidApp:connectedDebugAndroidTest"
```

The instrumentation test dials the emulator from the local AVD (see the header
of `firebase.test.json` for ports); the harness fails if no emulator/device is
attached. If the covered edit provably cannot move runtime behavior (comment-only
edit, log line, untouched screen), skip the emulator run but say why in the PR
body `Notes`.

- Nothing Firebase/instrumentation touched: skip this step, continue to step 3.

## 3. Verification (scoped to what changed)

Run what the branch touches, on Windows:

```powershell
.\gradlew.bat :shared:testAndroidHostTest              # always: shared Kotlin + host tests
.\gradlew.bat :androidApp:assembleDebug                # always: Android compilation
.\gradlew.bat :androidApp:assembleDebugAndroidTest     # when instrumentation tests changed (compiles only, does not execute)
```

Plus, conditionally:

- `scripts/release/` changed:
  ```powershell
  powershell -NoProfile -ExecutionPolicy Bypass -File scripts/release/Test-ReleaseAutomation.ps1
  ```
- androidApp Firebase code or `tests/firebase/firestore.rules` changed: the
  emulator harness in step 2.
- `iosApp/` changed: verify on a suitable macOS host when needed.

Never open the PR on red verification. Device-run instrumentation
(`connectedDebugAndroidTest` outside the emulator harness) only runs with a
connected device; when no device is available, say so in the PR body `Testing`
and note the APK at least compiles.

## 4. Write the PR body before creating

There is no PR template in this repo; fill every body section yourself
(`Summary`, `Changes`, `Release note`, `Testing`, `Notes`).

The `Release note` rules: 1–2 bullets of user-visible outcome in plain
language, each under 180 characters, no implementation details, or exactly
`None` when nothing user-visible changed. User-visible changes need a
conventional title prefix (`feat:`, `fix:`, `perf:`); anything else files under
Maintenance.

## 5. Push and create

```bash
git push -u origin <branch>   # -u only when no upstream exists
gh pr create --base main --title "<prefix>: <summary>" --body "<filled body>"
```

Fill every body section (`Summary`, `Changes`, `Release note`, `Testing`,
`Notes`); check the `Testing` boxes that were actually run. Say in `Notes` when
a check could not be performed (no device, no macOS host) and why.

## 6. Report

```
## PR ready

**PR:** #<n> — <title> (<url>)
**Head:** <branch> @ <short-sha>
**Pre-PR checks:** shared host tests ✓, assembleDebug ✓, assembleAndroidTest ✓ (compiled / emulator ran / skipped — Firebase untouched), release-script smoke ✓ (or skipped — scripts untouched), iOS ✓ (macOS host / skipped — iosApp untouched)
**Watch:** `gh pr checks <n> --watch`
```

## Guardrails

- Never open a PR from `main`, and never target any base other than `main`.
- Never stage files unrelated to the PR; never commit secrets or build artifacts
  (respect `.gitignore`); never commit `local.properties`, signing material, or
  `build/` outputs.
- Never force-push. If the push is rejected because the remote moved, stop and ask.
- If verification cannot be made green, report and stop — do not open a PR you
  know CI will reject.
