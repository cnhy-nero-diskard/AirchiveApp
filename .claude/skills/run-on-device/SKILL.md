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

Package id used for launch: **`cloud.airchive.personal.debug`** — the debug
variant sets `applicationIdSuffix = ".debug"` in `androidApp/build.gradle.kts`, so
`cloud.airchive.personal` is not installed and `monkey`/`am start` against it fail
with "No activities found". Back up anything valuable from a differently-signed
install first.

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

Launch with `am start`, **never `monkey`**. On Xiaomi/HyperOS, `adb shell monkey`
flips the system `accelerometer_rotation` setting from `0` to `1`, silently
unlocking the phone's rotation lock; the app itself does not touch that setting.
(`am start` leaves it alone — verified on 2406APNFAG / Android 16.)

Guard the rotation lock: read it before and after launch, and restore it if
anything changed.

```bash
ADB=~/Library/Android/sdk/platform-tools/adb   # or adb on PATH
SERIAL=<device serial from `adb devices`>
PRE_AUTO=$($ADB -s "$SERIAL" shell settings get system accelerometer_rotation | tr -d '\r')
PRE_ROT=$($ADB -s "$SERIAL" shell settings get system user_rotation | tr -d '\r')

$ADB -s "$SERIAL" shell am start -W \
  -n cloud.airchive.personal.debug/cloud.airchive.personal.MainActivity

POST_AUTO=$($ADB -s "$SERIAL" shell settings get system accelerometer_rotation | tr -d '\r')
if [ "$POST_AUTO" != "$PRE_AUTO" ]; then
  $ADB -s "$SERIAL" shell settings put system accelerometer_rotation "$PRE_AUTO"
fi
```

Confirm the `am start` output ends with `Status: ok` / `Complete` (not
`Error: Activity not started`) and that the activity is resumed:

```bash
$ADB -s "$SERIAL" shell pidof cloud.airchive.personal.debug
$ADB -s "$SERIAL" shell dumpsys activity activities | grep -m1 topResumedActivity
```

When several devices are attached (emulator + phone), always pass `-s <serial>`
and set `ANDROID_SERIAL=$SERIAL` for the Gradle install step.

### 4. Report

Summarize: build directory, branch, `BUILD SUCCESSFUL`, `Installed on <device>`, and launch confirmation.

## Guardrails

- **Run everything inside the target worktree.** Never switch branches in the original repo.
- **Do not launch if the build failed.** Stop and report the error.
- **Only commit log files if intended.** `build_install*.log` are build artifacts; prefer to leave them untracked.
- **Never launch with `monkey`.** It unlocks rotation on HyperOS; use `am start -W -n <pkg>/<activity>` instead.
- **Verify before reporting success.** Confirm the `installDebug` task, the `am start` success status, and the resumed activity actually appear in the output.