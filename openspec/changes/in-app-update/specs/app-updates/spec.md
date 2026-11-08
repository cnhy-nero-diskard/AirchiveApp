# Spec Delta

## Purpose

Lets the installed Airchive find a newer signed release, download it with the owner's consent, verify it, and install it with the owner's confirmation, without blocking the app, sending AC commands, or losing local data.

## ADDED Requirements

### Requirement: In-app updates exist only in Android release builds
Android release builds SHALL offer in-app updates. Debug builds and iOS SHALL report that in-app updates are unavailable, SHALL make no update request, and SHALL still show the current version.

#### Scenario: Debug build opens the updates section
- **WHEN** the owner opens App version & updates in a debug build
- **THEN** it SHALL show the current version and that in-app updates apply to release builds, and SHALL make no network request

#### Scenario: Release build opens the updates section
- **WHEN** the owner opens App version & updates in an Android release build
- **THEN** it SHALL show the current version, the last check time, and a Check for updates action

### Requirement: Update checks are bounded and never block the app
On start or resume, a release build SHALL check the release feed only when the last successful check is at least 6 hours old, backing off after failures. Check for updates in Settings SHALL always check. Opening Settings SHALL NOT check. A failed check SHALL NOT change Home, history, device state, or AC controls.

#### Scenario: Resume soon after a check
- **WHEN** the app resumes less than 6 hours after a successful check
- **THEN** it SHALL make no feed request

#### Scenario: Feed unreachable
- **WHEN** an update check fails because the device is offline, the request times out, or the feed returns an error
- **THEN** the updates section SHALL show the check as unavailable with its cause and a retry, and Home, history and controls SHALL behave as if no check had run

### Requirement: Update requests are isolated from owner credentials
Update requests SHALL use HTTPS only, follow at most 5 redirects, reach only `github.com` and `*.githubusercontent.com`, and read at most 64 KiB of manifest. They SHALL carry no ThinQ token, Firebase credential, cookie, or other owner identifier, and SHALL never contact LG or Firebase.

#### Scenario: Redirect to another host
- **WHEN** a feed or download response redirects to a host outside the allowed hosts, or to plain HTTP
- **THEN** the request SHALL stop and the check or download SHALL fail without following it

#### Scenario: Oversized manifest
- **WHEN** the manifest response exceeds 64 KiB
- **THEN** the check SHALL fail as a feed error without parsing it

### Requirement: Only a matching, newer manifest offers an update
The app SHALL offer an update only for a version-1 manifest whose package is `cloud.airchive.personal`, whose signer certificate equals the app's compiled-in pin, and whose version code is higher than the installed one. An unknown schema version, a malformed manifest, or a mismatched package or signer SHALL be reported as unavailable, never as an update.

#### Scenario: Same or lower version
- **WHEN** the manifest's version code is equal to or lower than the installed version code
- **THEN** the app SHALL report that it is up to date

#### Scenario: Signer mismatch in the manifest
- **WHEN** the manifest names a signer certificate different from the pin
- **THEN** the app SHALL report the update as unavailable and SHALL NOT download anything

#### Scenario: Unknown schema version
- **WHEN** the manifest's `schemaVersion` is not 1
- **THEN** the app SHALL report that this version cannot read the update feed, and SHALL NOT offer an update

### Requirement: Updates are offered without interrupting the owner
When an update is available, Settings SHALL show its version, plain-text release notes, and download size. Home SHALL show one small, dismissible notice outside the instrument panel, never a modal. The notice SHALL wait while a temperature draft, confirmation, or command is pending, and SHALL NOT return for a dismissed version.

#### Scenario: Update found while a command is pending
- **WHEN** an update becomes available while an AC command or confirmation is pending
- **THEN** the Home notice SHALL appear only after it settles, and the command flow SHALL be unchanged

#### Scenario: Owner dismisses the notice
- **WHEN** the owner dismisses the Home notice for a version
- **THEN** it SHALL NOT reappear for that version, and Settings SHALL still offer the update

### Requirement: Downloads start only with the owner's consent
The APK SHALL be downloaded only after the owner taps Update, into app-private storage, after confirming enough free space. The download SHALL show progress, allow Cancel, survive rotation and leaving the screen, and abort when it exceeds the manifest size or 200 MB. Partial files SHALL be deleted on cancel, failure, or the next start.

#### Scenario: Owner cancels a download
- **WHEN** the owner taps Cancel during a download
- **THEN** the partial file SHALL be deleted and the update SHALL be offered again

#### Scenario: Not enough storage
- **WHEN** free space is less than the APK size plus headroom
- **THEN** the download SHALL NOT start and the owner SHALL see that storage is the cause

### Requirement: A downloaded APK is verified before any install
Before installation the app SHALL confirm the file's size and SHA-256 equal the manifest's, the package is `cloud.airchive.personal`, the version code is higher than installed and equals the manifest's, it is not debuggable, and it has exactly one signer whose certificate equals the compiled-in pin. Any failure SHALL delete the file and name the reason.

#### Scenario: Hash mismatch
- **WHEN** the downloaded file's SHA-256 differs from the manifest
- **THEN** the file SHALL be deleted, installation SHALL NOT start, and the owner SHALL see a verification failure

#### Scenario: Different signer
- **WHEN** the downloaded APK is signed by any certificate other than the pin
- **THEN** the file SHALL be deleted and installation SHALL NOT start

### Requirement: Installation always needs the owner's confirmation
Installation SHALL go through the system installer and SHALL require the owner's confirmation on every update, including after Airchive becomes the installer of record. When the app may not install packages, it SHALL explain why and open that permission's system setting, and SHALL NOT fall back to any other install route.

#### Scenario: Install permission missing
- **WHEN** the owner taps Install and Airchive is not allowed to install unknown apps
- **THEN** the app SHALL explain the permission, open the setting for Airchive, and re-check on return

#### Scenario: Owner cancels the system dialog
- **WHEN** the owner cancels the system install confirmation
- **THEN** the app SHALL show that the update was cancelled and offer Install again

#### Scenario: Later update after an in-app install
- **WHEN** an update is installed after a previous in-app update made Airchive the installer of record
- **THEN** the system confirmation SHALL still be shown

### Requirement: The update outcome is reported after restart
Before installing, the app SHALL record the target version. On the next start it SHALL show "Updated to <version>" once if the installed version reached the target, or that the update did not finish with a retry if it did not.

#### Scenario: Successful update
- **WHEN** the app starts after an install and its version code is at least the recorded target
- **THEN** it SHALL show the updated version once and clear the record

#### Scenario: Update did not finish
- **WHEN** the app starts with a recorded target higher than its version code and no install in progress
- **THEN** it SHALL show that the update did not finish and offer it again

### Requirement: Updating never touches the household or local data
No update step SHALL send an AC command, read or write ThinQ or Firebase, uninstall the app, or clear its data. An update SHALL preserve the owner's sign-in, Room history, preferences, and the protected ThinQ token. Every exported Room schema SHALL migrate to the current schema without losing rows.

#### Scenario: Migrate from an exported schema
- **WHEN** a database created at any exported schema version is opened by the current build
- **THEN** it SHALL migrate without data loss or a destructive fallback

#### Scenario: Data after an in-app update
- **WHEN** the owner completes an in-app update
- **THEN** the owner SHALL remain signed in with the same cached history, preferences, and stored ThinQ token
