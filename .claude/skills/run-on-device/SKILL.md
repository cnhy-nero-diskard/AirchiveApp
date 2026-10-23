---
name: run-on-device
description: Rebuild the Android app from a given worktree (or the current repo), install it on a connected device via Gradle, and launch it over ADB. Use when the user invokes this skill, asks to rebuild/install/launch the app on their phone, start the app from a worktree, or deploy the debug build to a device.
allowed-tools: Bash(git:*, gradlew:*, adb:*)
license: MIT
metadata:
  author: cline
  version: "1.0"
---

# Run On Device

Rebuild the Android app, install the debug APK on a connected device, and launch it over ADB.

## Usage

- `$run-on-device` — build + install + launch from the **current** repo directory.
- `$run-on-device <worktree>` — build + install + launch from the named worktree (e.g. `$run-on-device AirchiveApp-owner-mobile-auth-access`).

Package id used for launch: `cloud.airchive.personal` (debug and release share
the application id, so a debug install replaces any same-signature build on the
device; back up anything valuable from a differently-signed install first).

## Workflow

### 1. Resolve the build directory

- If a `<worktree>` argument is given:
  - Find it under `D:\Codez\Projects\` (parent of this repo).
  - Verify it exists: `Test-Path <path>\gradlew.bat`.
  - Confirm it is on the expected feature branch: `git -C <path> rev-parse --abbrev-ref HEAD`.
- If no argument, use the current working directory.
- Report which directory and branch you are building from.

### 2. Build + Install (single blocking call — no background process)

Run the build in the foreground with an extended tool timeout. Do NOT use
`Start-Process` + log polling: that two-step pattern is the recurring hang source —
the run starts the build, ends the turn, and the install result never gets checked.
One blocking call reaches a terminal state in the same tool result, so there is no
second half to drop.

```powershell
.\gradlew.bat :androidApp:installDebug
```

Pass an explicit long timeout to the shell tool (at least 600000ms / 10 minutes;
observed builds range from ~10s warm to ~95s cold, and a cold Gradle daemon plus
`packageDebug` can exceed the default). Wait for the single result:

- Success terminal state: `> Task :androidApp:installDebug` … `Installed on 1 device.` and `BUILD SUCCESSFUL`.
- Failure terminal state: `BUILD FAILED`.

Only proceed once one of those two states appears in the result. If `BUILD FAILED`,
report the error output — do not proceed to launch.

Fallback, only if the foreground call itself times out at the tool level: rerun the
build writing to `build_install.log` / `build_install_err.log` and poll that file
with `Start-Sleep` in a loop *without ending your turn* until one of the two
terminal states above appears. An ended turn with a build still running looks like
a hang to the user.

### 3. Launch on device

```powershell
C:\Users\cnhyn\AppData\Local\Android\Sdk\platform-tools\adb.exe shell monkey -p cloud.airchive.personal 1
```

Confirm `Events injected: 1` in the output.

### 4. Report

Summarize: build directory, branch, `BUILD SUCCESSFUL`, `Installed on <device>`, and launch confirmation.

## Guardrails

- **Run everything inside the target worktree.** Never switch branches in the original repo.
- **Do not launch if the build failed.** Stop and report the error.
- **Only commit log files if intended.** `build_install*.log` are build artifacts; prefer to leave them untracked.
- **Verify before reporting success.** Confirm the `installDebug` task and the monkey `Events injected` line actually appear in the output.