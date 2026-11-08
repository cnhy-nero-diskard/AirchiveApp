# Design

## Context

See proposal.md for motivation.

**Current state of `main` at `f07764e`:**
- `scripts/release/*.ps1` call `gradlew.bat`, `aapt.exe`, and `apksigner.bat`, and read DPAPI-protected passwords from `%USERPROFILE%\.airchive\signing-credentials.clixml`. They run only on the Windows PC.
- The Mac has no `~/.airchive`, no `pwsh`, Android build-tools `36.0.0`, `keytool` from Android Studio's JBR, and `gh`.
- `androidApp/build.gradle.kts` hard-codes `versionName = "0.1.0"` and reads `versionCode` from `-Pairchive.versionCode`, defaulting to `2`. The owner's phone runs code 2, which was installed over ADB.
- The repository is public. It has no tags and no releases.
- `config/airchive-mobile.properties` is tracked, so every BuildConfig value in the APK is already public.

**Owner decisions, recorded in the issue #4 exploration on 2026-11-06:**

| ID | Decision |
|---|---|
| D1 | Releases are public GitHub Releases on this repository. |
| D2 | The Mac signs, with passwords in the Keychain. An offline keystore backup exists. |
| D3 | There is no separate manifest-signing key. |
| D4 | `main` requires checks with no bypass, so version bumps arrive through PRs (see `add-pr-ci`). |
| D5 | There are no forced updates. |

## Goals / Non-Goals

**Goals:**
- Every published APK passes the same checks twice: on the Mac before upload, and in CI before publishing.
- The phone can always read the newest release from one stable URL, with no token and no GitHub API rate limit.
- A version code can never be reused or lowered, and a tag always identifies exactly the commit that was built.
- The private key and its passwords never leave the Mac, apart from the owner's offline backup.

**Non-Goals:**
- Google Play distribution, AABs, beta or staged channels, and forced updates (D5).
- A CI-held signing key or a manifest-signing key (D3).
- Key rotation through a v3 signing lineage.
- Reproducible-build comparison, and SBOM or provenance attestations. These can be added later without changing this design.
- R8/minification. That decision belongs to a later hardening change.
- iOS distribution.

## Decisions

### 1. Distribution: public GitHub Releases (D1)

The feed URL is `https://github.com/cnhy-nero-diskard/AirchiveApp/releases/latest/download/update.json`.
- It is a browser download URL, not the REST API, so the app needs no token and is not subject to the 60 requests per hour limit on unauthenticated API calls.
- `latest` never resolves to a draft or a prerelease, so drafts serve as the staging area.
- Inside the manifest, the APK URL uses the versioned path `releases/download/v<versionName>/<asset>`, so a manifest and its APK cannot mismatch while a newer release is being published.

*Alternatives considered:*
- **Firebase Storage gated by owner sign-in.** Rejected: an authentication bug could not be fixed through the updater, and it adds a production rules deployment.
- **A private repository read with a token.** Rejected: issue #4 forbids long-lived tokens in the APK.

### 2. Signing on the Mac with Keychain (D2)

The owner copies `airchive-release.jks` to `~/.airchive/` (directory `700`, file `600`).

**`setup-signing.sh`, run once by the owner:**
- confirms the keystore's certificate SHA-256 equals the pinned value
- stores the store and key passwords as Keychain generic passwords (services `airchive-release-store-password` and `airchive-release-key-password`, account `$USER`), using `security add-generic-password -U … -w` with `-w` last so the tool prompts instead of taking the password as an argument
- validates both passwords against the keystore through `keytool`'s `:env` password options, without exporting the key

**`release.sh`:**
- reads the passwords from the Keychain into the environment of a single `./gradlew --no-daemon --no-configuration-cache` child process
- builds the APK and then runs the shared check script
- creates and pushes the tag, then uploads a draft release
- has an optional `--install <serial>` path that keeps the old script's protections: it checks the installed version is lower, checks the installed certificate, uses `adb install -r`, and never uninstalls or clears data

Keychain items readable without a prompt match the old DPAPI arrangement: protected at rest, but not from programs running as the owner. That is what allows unattended builds. Setup can instead restrict access to require approval in a dialog on every signing run, at the cost of unattended builds.

*Alternatives considered:*
- **Keep signing on the Windows PC.** Rejected: it is no longer the main machine.
- **A GitHub Environment secret.** Rejected: GitHub would hold the key on a public repository, and the pipeline would no longer be "the key never leaves the owner".

### 3. One version source

A new `config/airchive-version.properties` holds `versionName` and `versionCode`. It is non-secret and tracked, alongside the existing mobile config.
- Gradle reads the file, and the `-Pairchive.versionCode` property is removed.
- The tag is `v<versionName>`.
- Bumps arrive in a PR: either their own PR or part of the change being released.
- The first published release must use code 3 or higher, because the phone has code 2.

### 4. Build identity and refusal rules

`release.sh` refuses to run when:
- the working tree is dirty
- HEAD is not contained in `origin/main`
- the tag already exists
- the version file disagrees with the requested tag

The release build embeds the full commit as `<meta-data android:name="cloud.airchive.build.commit">`, set through a manifest placeholder that the script supplies. Release builds without it fail. Because it is manifest metadata, CI can read it with `aapt` and the app can read it at runtime. Debug builds use `dev`.

### 5. One shared check script

`scripts/release/check-apk.sh` is bash 3.2 compatible, so it runs on macOS and Linux CI. Given an APK and the expected name, code, and commit, it checks the following, then prints the SHA-256 and size. Any mismatch exits non-zero.

| Check | Tool |
|---|---|
| Package is `cloud.airchive.personal`, with the expected `versionCode` and `versionName` | `aapt dump badging` |
| No `application-debuggable` flag | `aapt dump badging` |
| Exactly one signer, whose certificate SHA-256 equals the pinned value | `apksigner verify --print-certs` |
| Embedded commit metadata is as expected | `aapt` |

The app's pre-install check in `in-app-update` is the same rule set, written in Kotlin.

### 6. Draft, check, publish

GitHub does not start workflows when a draft release is created, so `release-publish.yml` is a `workflow_dispatch` with inputs `tag` and `dry_run`. It runs in a `release` environment that only `v*` tags may deploy to, with `contents: write` and no other secrets.

**Steps:**
1. Confirm the tag commit is an ancestor of `main`.
2. Confirm the draft for the tag holds exactly one APK.
3. Run `check-apk.sh`, comparing the expected values with the version file at the tag commit and the tag commit itself.
4. Confirm the code is greater than every published release's code and greater than 2.
5. Generate `update.json`.
6. In a dry run, upload it as a workflow artifact and stop.
7. Otherwise attach it and publish with `make_latest=true`.

Immutable releases are enabled for the repository, so a published tag and its assets cannot be swapped afterwards.

### 7. Update manifest, schema version 1

The asset name is `airchive-<versionName>-<versionCode>.apk`.

```json
{
  "schemaVersion": 1,
  "channel": "stable",
  "packageName": "cloud.airchive.personal",
  "versionCode": 3,
  "versionName": "0.2.0",
  "commit": "<40 hex>",
  "publishedAt": "2026-11-07T00:00:00Z",
  "releaseNotes": "<plain text, at most 4000 characters>",
  "apk": {
    "url": "https://github.com/cnhy-nero-diskard/AirchiveApp/releases/download/v0.2.0/airchive-0.2.0-3.apk",
    "sha256": "<64 hex>",
    "size": 12345678,
    "signingCertSha256": "2cf5480e933008a24e469d7936cb4abd76cc2352e26ddf3c2a8b4effd4b54202"
  }
}
```

- The release notes come from the GitHub release body.
- `minSupportedVersionCode` is deliberately absent (D5). Forced updates would require schema version 2 together with manifest signing.
- `signingCertSha256` lets the app reject a release before downloading it. The app's compiled-in pin remains the authority.
- The manifest is unsigned (D3). Android's same-signer and no-downgrade checks, plus the app's pre-install checks, are the trust anchor.

### 8. Withdrawal and rollback

- **Withdrawal:** delete the release, or demote it to a prerelease. `latest` then falls back to the previous release, and phones on the withdrawn version see nothing newer.
- **Rollback:** always publish a higher version.
- **The ADB path in `release.sh`** remains the recovery route if a shipped updater is broken.

## Risks / Trade-offs

- **The keystore is damaged or lost while moving it.** → Copy, never move. Check the fingerprint on the Mac before touching the Windows copy, and keep the offline backup separate. Remove the Windows keystore and `.clixml` only after a Mac-signed build passes the checks, and only as an owner action.
- **Any process running as the owner can read the Keychain passwords.** → This matches the previous DPAPI exposure. Per-signing approval is available at setup. Gradle never stores passwords in its configuration cache or build scans.
- **CI checks a locally built APK but cannot prove it was built from the tag.** → The embedded commit, clean-tree and ancestor rules, and the tag check make substitution deliberate rather than accidental. A reproducible-build comparison can come later.
- **Someone controlling the GitHub account could publish a manifest or withhold releases.** → They cannot sign. Android and the app reject any APK with another signer. Withholding updates is an accepted risk (D3).
- **A version code is reused, or a debug-signed build is published.** → Rejected by the version-file check, the comparison with published codes, the debuggable flag, and the pinned-certificate check.
- **GitHub changes the host it redirects release downloads to.** → The app's host rule must tolerate this (see `in-app-update`). The dry run records the actual redirect chain.

## Migration Plan

1. `add-pr-ci` lands. Version bumps now go through PRs.
2. The owner copies the keystore and runs `setup-signing.sh`. The fingerprint is checked.
3. Land the version file, the commit metadata, the scripts, and the workflow.
4. Run a dry run: build, check, upload a draft, then run the workflow with `dry_run=true`. Delete the draft and tag.
5. Remove the `.ps1` scripts and update `docs/android-release.md` and the `merge-pr` skills.
6. The first real publication is the bootstrap build vN from `in-app-update`, installed on the phone through `release.sh --install`. That is the last ADB install.

**If this change has to be backed out:** the Windows scripts remain in git history, and the key is unchanged, so they can be restored as-is.

## Open Questions

- **Resolved (default):** no `v*` tag ruleset. Immutable releases protect published tags, and tags for unpublished drafts are deliberately disposable (dry run). This can be revisited without changing the design.
- **Deferred:** SBOM and provenance format, if wanted later.
