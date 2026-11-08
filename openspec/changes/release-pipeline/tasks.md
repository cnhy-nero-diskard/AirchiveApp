# Tasks

## 1. Version source and build identity

- [x] 1.1 Add `config/airchive-version.properties` (`versionName=0.1.0`, `versionCode=2`) and make `androidApp/build.gradle.kts` read it through a configuration-cache-safe provider. Remove `airchive.versionCode`, and fail the build with a message naming the version file when that property is passed. Update the version section of `docs/android-release.md`. Verify:
  - `./gradlew :shared:testAndroidHostTest :androidApp:assembleDebug` passes
  - `aapt dump badging` on the debug APK shows `versionCode='2' versionName='0.1.0-debug'`
  - `./gradlew :androidApp:assembleDebug -Pairchive.versionCode=9` fails and names `config/airchive-version.properties`
- [x] 1.2 Embed the build commit as `<meta-data android:name="cloud.airchive.build.commit">` through a manifest placeholder. Release builds take it from `-Pairchive.buildCommit` and fail before signing unless it is 40 lowercase hex characters. Debug builds use `dev`. Verify:
  - `aapt dump xmltree <debug apk> AndroidManifest.xml` shows `dev`
  - `./gradlew :androidApp:assembleRelease` with no commit fails with the commit message, not the signing-preflight message

## 2. Shared APK check script

- [x] 2.1 Add `scripts/release/check-apk.sh` (bash 3.2, macOS and Linux) per design §5. It finds the newest `build-tools` under `ANDROID_HOME`, takes the APK plus expected name, code and commit, reports every failed check, exits non-zero on any failure, and prints SHA-256 and size on success. The pinned certificate is fixed in the entry point. Only an internal function used by tests takes a different pin. Verify `bash -n` and `shellcheck` pass.
- [x] 2.2 Add `scripts/release/test-release-scripts.sh` covering `check-apk.sh` with fixtures generated at test time and never committed:
  - the debug APK (debuggable, other signer) fails
  - an APK with two signers fails
  - wrong package, code or commit fails
  - a non-debuggable APK signed with a throwaway key passes only through the test-pin function

  Verify it passes on the Mac.
- [x] 2.3 Add a step to the `build` job in `.github/workflows/verify.yml`, after assembly, that runs `shellcheck scripts/release/*.sh` and `test-release-scripts.sh`. Verify on the PR that the step runs and that a temporary broken assertion fails `required` (then revert it).

## 3. Mac signing setup and release script

- [x] 3.1 Add `scripts/release/setup-signing.sh` per design §2:
  - set `~/.airchive` to `700` and the keystore to `600`
  - require the certificate SHA-256 to equal the pin before prompting
  - store both passwords with `security add-generic-password -U … -w` (prompting, never as arguments)
  - validate them with `keytool`'s `:env` options

  It never creates a key. Verify through `test-release-scripts.sh`: a throwaway keystore passed via `--keystore` is rejected on fingerprint, and `security find-generic-password -s airchive-release-store-password` still finds nothing. Do not run it against the real keystore here (see 6.1).
- [x] 3.2 Add `scripts/release/release.sh <versionName>` per design §2 and §4:
  - refuse on a dirty tree, HEAD not in `origin/main`, an existing local or remote tag, or a version-file mismatch
  - export Keychain passwords only to one `./gradlew --no-daemon --no-configuration-cache :androidApp:assembleRelease -Pairchive.buildCommit=<HEAD>` child
  - run `check-apk.sh`, then create and push `v<versionName>`
  - upload a draft release with asset `airchive-<versionName>-<versionCode>.apk` and notes from `--notes-file`

  Verify each refusal in `test-release-scripts.sh` against a temporary git repository, with `gradlew`, `security` and `gh` shimmed on `PATH`. Assert the shimmed Gradle never receives a password in its arguments.
- [ ] 3.3 Add `release.sh --install <serial>` with the old script's protections (design §2). Verify in `test-release-scripts.sh` with a shimmed `adb`:
  - a missing serial is refused before building
  - an equal or higher installed code is refused
  - an installed APK with another signer is refused
  - the only install command is `install -r`

  Also verify that `grep -nE 'uninstall|pm clear' scripts/release/*.sh` returns nothing.

## 4. Manifest and publication workflow

- [ ] 4.1 Add `scripts/release/make-manifest.sh` that writes the design §7 schema-version-1 `update.json` from the APK, tag, commit, publish time and release body. It fails on notes over 4000 characters and never emits `minSupportedVersionCode`. Verify in `test-release-scripts.sh` against a golden file, with an oversized-notes case and a check that the APK URL uses `releases/download/v<versionName>/`.
- [ ] 4.2 Add `.github/workflows/release-publish.yml` per design §6:
  - `workflow_dispatch` with inputs `tag` and `dry_run` (default `true`)
  - `environment: release`, `contents: write`, SHA-pinned actions, no `secrets.` references
  - checks: tag commit is an ancestor of `main`; exactly one APK on the draft; `check-apk.sh` with values from `git show <tag>:config/airchive-version.properties`; the code is greater than every published code and greater than 2
  - generates the manifest, uploads it as an artifact in a dry run, and otherwise attaches it to the draft and publishes with `make_latest=true`

  Verify that `grep -n 'secrets\.' .github/workflows/release-publish.yml` is empty and every `uses:` is a full SHA. End-to-end verification is task 6.2.
- [ ] 4.3 With the owner's confirmation, enable immutable releases and create the `release` environment allowing only `v*` tags. Verify with `gh api repos/{owner}/{repo}/environments/release` showing the tag policy, and with the repository settings showing immutable releases on.

## 5. Runbook and skills

- [ ] 5.1 Replace the Windows sections of `docs/android-release.md` with a Mac runbook: keystore transfer (copy, never move; check the fingerprint first), setup, release, dry run, publish, install recovery, withdrawal, and key backup and recovery. Check GitHub's immutable-release documentation for which withdrawal actions (delete, demote to prerelease) are allowed, and document only those. Verify every command in the runbook exists in `scripts/release/` with the documented flags.
- [ ] 5.2 Update merge-pr Mode B in `.claude/skills/merge-pr/SKILL.md` and `.codex/skills/merge-pr/SKILL.md` to edit `config/airchive-version.properties` (code strictly increasing) and to point at `release.sh`. Update the release-script check in both `make-pr` skills to `test-release-scripts.sh`. Verify `grep -rn 'airchive.versionCode\|\.ps1' .claude .codex` returns nothing.

## 6. Owner key transfer, dry run, and Windows retirement

- [ ] 6.1 Owner step: copy `airchive-release.jks` to `~/.airchive/` and run `setup-signing.sh`. Verify the printed certificate SHA-256 equals the pin, and that both Keychain items exist.
- [ ] 6.2 Owner-approved dry run:
  - merge a version-bump PR to the planned bootstrap version (code 3 or higher)
  - run `release.sh` to a draft, then `release-publish` with `dry_run=true`
  - record the manifest artifact and the actual download redirect chain in the PR

  Then delete the draft and the tag. Verify the manifest's SHA-256 and size match the draft APK, and that `gh release list` shows no published release.
- [ ] 6.3 After 6.2 passes, remove `scripts/release/*.ps1` and any remaining Windows references in docs. Verify that `ls scripts/release` lists only the bash scripts and that `grep -rn 'Invoke-AirchiveRelease\|Setup-AirchiveSigning\|Test-ReleaseAutomation' --exclude-dir=openspec .` returns nothing.

## Workflow follow-up

- Owner-only: remove the Windows keystore copy and `.clixml` after a Mac-signed build has passed `check-apk.sh`. Keep the offline backup.
- The first real publication is the `in-app-update` bootstrap build vN, installed with `release.sh --install`.
- Open the PR referencing #4, then sync and archive after merge.
