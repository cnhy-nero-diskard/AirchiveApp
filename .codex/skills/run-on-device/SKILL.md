---
name: run-on-device
description: Rebuild AirchiveApp from the current checkout or a named worktree, install the Android debug build on an explicitly selected connected device, and launch it over ADB. Use when the user asks to rebuild, install, launch, or run the app on their phone.
allowed-tools: Bash(git:*), Bash(gradlew:*), Bash(gradlew.bat:*), Bash(adb:*), Bash(powershell:*)
license: MIT
metadata:
  author: cline
  version: "1.1"
---

# Run On Device — AirchiveApp

## Usage

- `$run-on-device`: build, install, and launch from the current repo.
- `$run-on-device <worktree>`: use that checkout, for example
  `AirchiveApp-feat-energy-history`.

Android module: `androidApp`. Application ID: `cloud.airchive.personal`.
**Debug and release currently share this ID; there is no `.debug` suffix.**
An existing signed release cannot be replaced by a differently signed debug APK.
Never uninstall, clear data, downgrade, or bypass signature checks to make installation
work: local Room history and the Android Keystore ThinQ credential must be preserved.
If the phone has a release installation, stop and offer a same-key release update
using `docs/android-release.md` and `scripts/release/release.sh --install <serial>`.
The release path requires an existing installation, pinned signer, and lower
version code; it does not remove app data.

## Workflow

### 1. Resolve checkout and device

1. Resolve the repo root and `git worktree list --porcelain`. Prefer a registered
   worktree path; accept an explicit absolute path. Do not assume every worktree is
   directly under `D:\Codez\Projects` (workspace containers may also be used).
2. Verify `gradlew.bat` and `androidApp/build.gradle.kts` exist, and report directory
   and branch. Do not switch branches in the original checkout.
3. Resolve ADB from `PATH`, the SDK path in `local.properties`, `ANDROID_HOME`,
   `ANDROID_SDK_ROOT`, or the existing `$env:LOCALAPPDATA\Android\Sdk` directory.
   Do not read unrelated credentials or invent SDK paths.
4. Run `& $adb devices -l`. If multiple devices are connected, ask which serial to
   use unless one was explicitly supplied. Stop for unauthorized/offline devices.
5. Check whether `cloud.airchive.personal` is already installed and compare its
   signing identity with the intended APK using available package/apksigner tooling.
   If the identity cannot be established safely, build only and ask before installing
   over an existing installation. Do not infer release/debug identity from the ID.

### 2. Build and install

Run in the target checkout with a foreground tool timeout of at least 600000 ms:

```powershell
.\gradlew.bat :androidApp:assembleDebug
```

Verify `BUILD SUCCESSFUL`, then inspect
`androidApp/build/outputs/apk/debug/androidApp-debug.apk` as needed for the
signature comparison. Once the identity check passes, install through Gradle:

```powershell
$env:ANDROID_SERIAL = '<selected-serial>'
.\gradlew.bat :androidApp:installDebug
```

Restore any previous process `ANDROID_SERIAL` in a `finally` block. Verify both
the install task success and the intended device; do not continue on build failure,
signature mismatch, version downgrade rejection, or a tool timeout. Do not start
an unmonitored background install or report success while a build is still running.
If logs are needed, write them outside the repository.

### 3. Launch

```powershell
& $adb -s <selected-serial> shell monkey -p cloud.airchive.personal -c android.intent.category.LAUNCHER 1
```

Verify `Events injected: 1`. Launch confirmation is not proof that sign-in,
history sync, or AC controls work. Do not read the saved PAT or send AC commands
without explicit command values and authorization.

### 4. Report

Report checkout, branch, device serial, build/install outcome, and launch result.
State blockers precisely and distinguish observed results from user-reported checks.
Do not commit artifacts, provision signing credentials, or publish a release as a
side effect of this skill.
