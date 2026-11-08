# Release Distribution Specification

## Purpose

Defines how Android release builds are versioned, signed with the pinned Airchive identity, verified, published to public GitHub Releases with a version-1 update manifest, and withdrawn or rolled forward.

## Requirements

### Requirement: One tracked version source
Release and debug builds SHALL take `versionName` and `versionCode` from one tracked, non-secret version file. The build SHALL NOT accept a version override from the command line. A release's tag SHALL be `v<versionName>`, and version changes SHALL reach `main` only through a pull request.

#### Scenario: Build reads the version file
- **WHEN** any Android build runs
- **THEN** its `versionName` and `versionCode` SHALL equal the values in the version file

#### Scenario: Removed override is passed
- **WHEN** a build is started with the removed `airchive.versionCode` property
- **THEN** the build SHALL fail with a message naming the version file instead of using either value

### Requirement: Version codes only increase
A version code SHALL never be reused or lowered. Publication SHALL refuse a release whose code is not greater than every published release's code and greater than 2, the code already installed on the owner's phone.

#### Scenario: Reused code
- **WHEN** publication is requested for a code equal to or lower than a published release's code
- **THEN** publication SHALL stop before anything is published and name the conflicting release

#### Scenario: First publication
- **WHEN** the first release is published and no release exists
- **THEN** its code SHALL be at least 3

### Requirement: Release builds identify their commit
Every release APK SHALL embed the full commit it was built from, readable from the APK without installing it and by the app at runtime. A release build without a commit SHALL fail. Debug builds SHALL report `dev`.

#### Scenario: Release build without a commit
- **WHEN** a release build is started without a commit value
- **THEN** the build SHALL fail

#### Scenario: Read the commit from an APK
- **WHEN** the check script inspects a release APK
- **THEN** it SHALL read the same 40-character commit that the build embedded

### Requirement: Signing refuses an unidentifiable build
The release script SHALL refuse to build or sign when the working tree is dirty, HEAD is not contained in `origin/main`, the release tag already exists, or the version file disagrees with the requested tag.

#### Scenario: Dirty working tree
- **WHEN** the release script runs with uncommitted changes
- **THEN** it SHALL exit non-zero before building, without creating a tag or release

#### Scenario: Tag already exists
- **WHEN** the release script runs for a version whose tag already exists locally or on the remote
- **THEN** it SHALL exit non-zero without changing the tag

### Requirement: Every release APK passes the same checks
One check script SHALL run both on the owner's Mac before upload and in CI before publication. It SHALL require package `cloud.airchive.personal`, the expected `versionName`, `versionCode` and commit, no debuggable flag, and exactly one signer whose certificate SHA-256 is `2cf5480e933008a24e469d7936cb4abd76cc2352e26ddf3c2a8b4effd4b54202`.

#### Scenario: Debug-signed APK
- **WHEN** the check script is given an APK signed with any other certificate, or with more than one signer
- **THEN** it SHALL exit non-zero and nothing SHALL be uploaded or published

#### Scenario: Debuggable APK
- **WHEN** the check script is given an APK marked debuggable
- **THEN** it SHALL exit non-zero

#### Scenario: Valid APK
- **WHEN** the check script is given a correctly signed release APK with the expected identity
- **THEN** it SHALL exit zero and print the APK's SHA-256 and size

### Requirement: Signing material stays with the owner
The private key and its passwords SHALL exist only on the owner's Mac and the owner's offline backup. Passwords SHALL be read from the macOS Keychain and SHALL NOT appear in command arguments, logs, Gradle caches, the repository, or CI. No release step SHALL create a new signing key.

#### Scenario: Keystore with a different certificate
- **WHEN** signing setup finds a keystore whose certificate SHA-256 differs from the pinned value
- **THEN** setup SHALL stop without storing any password

#### Scenario: CI publication
- **WHEN** the publication workflow runs
- **THEN** it SHALL have no access to the keystore or its passwords and SHALL only verify and publish an APK already signed on the Mac

### Requirement: Releases are published only after a second verification
The release script SHALL upload only a draft release. Only a manually started publication workflow SHALL publish, and only after re-running the APK checks, confirming the tag commit is on `main`, and confirming the draft holds exactly one APK. A dry run SHALL perform every check and produce the manifest without publishing.

#### Scenario: Dry run
- **WHEN** the publication workflow runs in dry-run mode for a valid draft
- **THEN** it SHALL produce the manifest as a workflow artifact and the release SHALL remain a draft

#### Scenario: Check fails in CI
- **WHEN** any publication check fails
- **THEN** the release SHALL remain a draft and no manifest SHALL be attached

#### Scenario: Successful publication
- **WHEN** every check passes outside dry-run mode
- **THEN** the release SHALL be published as the latest release with the APK and `update.json` attached

### Requirement: Version-1 update manifest at a stable URL
Each published release SHALL carry `update.json` with `schemaVersion` 1, `channel`, `packageName`, `versionCode`, `versionName`, `commit`, `publishedAt`, plain-text `releaseNotes` of at most 4000 characters, and an `apk` object with a versioned URL, SHA-256, size and signer certificate SHA-256. It SHALL be readable without a token at the repository's latest-release download URL.

#### Scenario: Read the latest manifest
- **WHEN** a client requests `https://github.com/cnhy-nero-diskard/AirchiveApp/releases/latest/download/update.json`
- **THEN** it SHALL receive the newest published release's manifest without authentication

#### Scenario: Manifest matches its APK
- **WHEN** the manifest is generated
- **THEN** its APK URL SHALL point at that release's own tag, and its SHA-256 and size SHALL equal the attached APK's

#### Scenario: No forced-update field
- **WHEN** a version-1 manifest is generated
- **THEN** it SHALL NOT contain a minimum supported version or any field that forces an update

### Requirement: Published releases are immutable and withdrawn by rolling forward
Published releases and their tags and assets SHALL NOT be replaceable. A bad release SHALL be withdrawn by deleting it or demoting it to a prerelease, after which the latest URL SHALL resolve to the previous release. A rollback SHALL always be a new release with a higher version code.

#### Scenario: Withdraw a release
- **WHEN** the newest release is demoted to a prerelease
- **THEN** the latest manifest URL SHALL serve the previous published release's manifest

#### Scenario: Roll back
- **WHEN** the owner needs the behaviour of an older release
- **THEN** it SHALL be published again under a new, higher version code

### Requirement: Checked direct install as the recovery path
The release script SHALL keep an install path that targets an explicitly named device, installs only over an existing Airchive installation with the pinned signer and a lower version code, and never uninstalls the app or clears its data.

#### Scenario: No explicit device
- **WHEN** install is requested without a device serial
- **THEN** the script SHALL refuse before building

#### Scenario: Installed build is not older
- **WHEN** the device's installed version code is equal to or higher than the new build's
- **THEN** the script SHALL refuse to install and leave the device unchanged

#### Scenario: Installed build has another signer
- **WHEN** the installed APK's signer differs from the pinned certificate
- **THEN** the script SHALL refuse to install and SHALL NOT uninstall the existing app
