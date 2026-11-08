## Why

Releases exist only as a Windows-only PowerShell flow. It signs with passwords held in Windows DPAPI and installs over ADB, and nothing is ever published. The version is edited by hand: `versionName` is fixed at `0.1.0`, and `versionCode` defaults to `2` unless overridden with `-Pairchive.versionCode`. The signing key is not on the Mac that is now the owner's main machine. The in-app updater (`in-app-update`) needs a versioned, verified release feed to read.

## What Changes

- **Signing moves to the Mac.** The keystore lives at `~/.airchive/` and its passwords in the macOS Keychain. An owner-run setup script stores them. A release script builds, signs, checks, and uploads a draft GitHub release. The signing identity is unchanged (certificate SHA-256 `2cf5480e933008a24e469d7936cb4abd76cc2352e26ddf3c2a8b4effd4b54202`), and no new key is created. The checked ADB install path is kept for the bootstrap build that first carries the updater.
- **One shared bash check script** runs locally and in CI. It checks:
  - the package is `cloud.airchive.personal`
  - `versionCode`
  - the single pinned signer certificate
  - the APK is not debuggable
  - SHA-256
- **One version source** holds `versionName` and `versionCode` and is read by Gradle. The release tag is `v<versionName>`. Codes strictly increase and are never reused. **BREAKING:** the `-Pairchive.versionCode` override is removed.
- **Release builds embed their commit.** Signing refuses a dirty working tree, or a HEAD that is not the tagged commit on `main`.
- **A manually dispatched `release-publish` workflow:**
  - downloads the draft's APK and re-runs the same checks
  - confirms the code is higher than every published release and that the tag matches the embedded commit
  - generates `update.json` (schema version 1), attaches it, and publishes the release as latest

  A dry-run mode verifies without publishing.
- **GitHub immutable releases are enabled.** A bad release is withdrawn by deleting it or demoting it to prerelease. Rollback is always a higher version, because Android refuses downgrades.
- **The `merge-pr` version-bump mode** edits the new version source through a PR and points at the Mac scripts.
- **The Windows `.ps1` scripts are removed** after the first verified Mac-signed build.
- **A release runbook** replaces the Windows sections of `docs/android-release.md`. It covers setup, dry run, publishing, withdrawal, and key backup and recovery.

## Capabilities

### New Capabilities
- `release-distribution`: How release builds are versioned, signed with the pinned identity, verified, and published to GitHub Releases with a version-1 update manifest. It also covers how releases are withdrawn and rolled forward.

### Modified Capabilities
- `mobile-experience`: The "Private Android release and iOS preparation" requirement gets one clarification. "Installed privately" means direct installation outside an app store. Release APKs are publicly downloadable signed builds on GitHub Releases. Upgrades still preserve local data.

## Impact

- `scripts/release/`: new bash setup, release, and check scripts; the `.ps1` scripts are removed later.
- `androidApp/build.gradle.kts` and a new version file: the version source, the commit field, and removal of the override.
- New `.github/workflows/release-publish.yml`.
- Repository settings: immutable releases, and a `release` environment restricted to tags.
- `docs/android-release.md`, and the `merge-pr` skills (`.claude/` and `.codex/`).
- **Owner-only steps:**
  - transferring the keystore and checking its fingerprint
  - Keychain setup
  - approving any real publication
- **Depends on `add-pr-ci`.** It defines the manifest format that `in-app-update` consumes.
