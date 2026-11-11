## Why

Updating the installed app still needs a computer running ADB. Issue #4 asks for the installed Airchive to find a newer signed build and install it with the owner's consent. The update must not lose sign-in, Room history, preferences, or the Keystore-protected ThinQ token.

## What Changes

- **A platform update boundary.**
  - Manifest parsing, version comparison, and update state live in shared common code, so they can be host-tested.
  - Android implements download, checks, and installation.
  - iOS and debug builds report that in-app updates are unavailable.
- **When the app checks.** It checks the release feed on start or resume, using its own backoff and at most once every 6 hours. Settings also offers "Check for updates". A failed check never blocks Home, history, or AC controls.
- **A new "App version & updates" section in Settings.** It shows:
  - current version and last check
  - the available version with release notes and size
  - download progress with Cancel
  - Retry, already-current, and unavailable states

  Opening the section never triggers a check.
- **A small, dismissible Home notice** when a newer version is found. It is never a modal, and it never interrupts a pending AC command or confirmation.
- **Downloading starts only when the owner taps Update.** The APK goes to app-private storage. Before installation the app checks:
  - size
  - SHA-256
  - the package name
  - a strictly higher `versionCode`
  - the pinned signer certificate
- **Installation uses PackageInstaller and always requires the owner's confirmation.** The app guides the owner through "Install unknown apps" when needed. It detects whether the update finished, failed, or was cancelled after the app restarts. The `REQUEST_INSTALL_PACKAGES` permission is added.
- **Explicitly excluded:** silent install, uninstall, data clearing, root or ADB use, forced updates, and any ThinQ or Firebase request as part of updating.
- **Verification.**
  - A Room migration test runs from every exported schema version to the current one.
  - A real-phone acceptance run installs the first updater build (vN) over ADB, for the last time, and then installs vN+1 from inside the app.

## Capabilities

### New Capabilities
- `app-updates`: In-app discovery, owner-consented download, pre-install verification, and installation of newer signed releases, with failure, cancellation, and post-restart outcomes. Also the guarantees that updating never blocks the app, sends AC commands, or loses local data.

### Modified Capabilities
- `mobile-experience`: The "Main routes cover the owner's everyday tasks" requirement extends Settings' responsibilities from credentials, refresh, and local data to also include app version and updates.

## Impact

- `androidApp`:
  - the manifest permission
  - the update service and PackageInstaller status receiver
  - an update check alongside the existing foreground refresh in `MainActivity.onResume`
  - BuildConfig fields for the feed URL and pinned certificate
- `shared/commonMain`:
  - the update model and state machine
  - the manifest parser
  - the Settings section and Home notice
  - an injected binding, alongside `ProductionThinQBindings`
- Tests: host tests using the existing Ktor MockEngine fake feed, and Room migration tests over `shared/schemas`.
- Docs and acceptance evidence.
- **Consumes** the update-manifest format defined by `release-pipeline` (`release-distribution`). It can be built and tested against a fake feed before that change lands, but real acceptance needs two published releases.
