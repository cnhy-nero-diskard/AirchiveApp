# Android release runbook

Airchive releases are signed on the owner's Mac with the existing release
keystore. GitHub Releases hosts public APKs and the updater manifest. CI checks
and publishes a draft but never has access to the signing key or passwords.

## Release identity and versioning

The only version source is `config/airchive-version.properties`. Both Android
build types use its `versionName` and `versionCode`; the release tag is
`v<versionName>`. Make version changes through a pull request and increase
`versionCode` for every build. Never reuse or lower a code. The first public
release must use code 3 or higher because code 2 is already installed.

## Transfer and set up the signing key

Keep the original keystore and an offline backup until a Mac-signed APK passes
the release checks. Copy the existing `airchive-release.jks`; never move it.
Before removing or changing the source copy, inspect the transferred copy's
public certificate and confirm the SHA-256 fingerprint is
`2cf5480e933008a24e469d7936cb4abd76cc2352e26ddf3c2a8b4effd4b54202`:

```sh
keytool -list -v -keystore /path/to/transfer/airchive-release.jks -alias airchive-release
```

Keytool prompts for the store password locally. Create the private directory,
copy the file, and restrict its permissions:

```sh
mkdir -p -m 700 ~/.airchive
cp /path/to/transfer/airchive-release.jks ~/.airchive/airchive-release.jks
chmod 600 ~/.airchive/airchive-release.jks
```

Run the setup script. It checks the pinned certificate before asking for either
password, stores the passwords as macOS Keychain items, then validates both
against the keystore:

```sh
scripts/release/setup-signing.sh
```

The script does not create or replace a key. Never put passwords in command
arguments, shell history, repository files, CI, or chat.

## Build and stage a draft

Set the version in a merged pull request before releasing. From a clean checkout
whose commit is already contained in `origin/main`, prepare a plain-text release
notes file and run:

```sh
scripts/release/release.sh 0.2.0 --notes-file /path/to/release-notes.txt
```

Replace `0.2.0` with the exact `versionName` in the tracked version file. The
script refuses a dirty tree, a commit outside `origin/main`, a reused local or
remote tag, or a version mismatch. It reads passwords from Keychain for the
single Gradle build process, checks the signed APK, pushes tag `v<versionName>`,
and uploads the APK and notes as a draft release. The draft is not public as the
latest release.

## Verify a draft and publish

Open **Actions → release-publish → Run workflow**. Select the release tag as the
workflow ref, enter the same draft tag (for example `v0.2.0`), and leave
**dry_run** enabled first. The workflow checks that the
tag's commit is in `main`, verifies the APK and monotonically increasing code,
and creates `update.json` as an artifact while leaving the release as a draft.
Download that artifact and confirm its checksum, size, release notes, and
versioned APK URL. Inspect the GitHub download redirect chain for the APK URL
before publishing.

After the dry run is reviewed, run the same workflow and tag with **dry_run**
disabled. CI attaches `update.json` and publishes the release as latest. The
stable manifest URL is:

`https://github.com/cnhy-nero-diskard/AirchiveApp/releases/latest/download/update.json`

The manifest points to the APK under its versioned release URL, so a newer
publication cannot make an older manifest point to a different APK.

## Checked install recovery

Use this path only when an existing installation needs a direct update, such as
the first build that carries the updater:

```sh
scripts/release/release.sh --install <device-serial>
```

The serial is required before the build starts. The script reads the tracked
version, checks the signed APK, confirms the named device is available, and
requires an existing Airchive installation with a lower version code and the
pinned signer. It runs only `adb install -r`; it never removes the app or clears
its data.

## Withdraw or roll forward

If a published release is bad, open its GitHub release page and either mark it as
a prerelease or delete the release. With immutable releases enabled, you cannot
replace or delete published assets, or move/delete the associated tag while the
release exists. Deleting an immutable release permits deleting its tag, but that
tag name can never be reused. A prerelease can be switched back to a regular
release, so use deletion when permanent withdrawal is required. GitHub still
allows editing the title and notes and changing the prerelease/latest status.
See GitHub's [immutable releases guidance](https://docs.github.com/en/code-security/concepts/supply-chain-security/immutable-releases).

Never replace a published APK or reuse its version code. To restore older
behavior, prepare and publish a new release with a higher version code.

## Key backup and recovery

Keep the offline keystore backup separate from the Mac and maintain a separate,
secure record of both passwords. Keep the original source copy until a Mac
release APK has passed `check-apk.sh`. If the Mac is replaced, copy the offline
keystore backup to `~/.airchive/airchive-release.jks` and run
`scripts/release/setup-signing.sh`; setup must print the same pinned
certificate fingerprint. If the key or passwords are lost, stop release
signing and plan a signing-key migration. Do not generate a replacement key for
the existing app identity.
