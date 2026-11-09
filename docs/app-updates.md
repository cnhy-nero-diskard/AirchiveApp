# In-app app updates

Airchive checks the GitHub release feed in Android **release builds**. The
updater is disabled in debug builds. A check reads only the public manifest;
an APK is downloaded only after the owner taps **Update**. The updater does not
send ThinQ or Firebase requests, issue AC commands, or access the credential
store. Its check time, dismissed version, and pending install record are stored
in installation-wide private preferences.

## Update states

| State | What it means |
| --- | --- |
| `Idle` | No update result is currently shown. The owner can start a check. |
| `Checking` | The app is fetching and validating the release manifest. |
| `UpToDate` | No newer compatible release is available. |
| `Unavailable` | The feed could not be used. The cause and retry action are shown. |
| `Available` | A newer release passed package and signer checks; its version, notes, and size are shown. |
| `Downloading` | The owner started the download. Progress and **Cancel** are available. |
| `Verifying` | The completed APK is checked before it can be installed. |
| `Failed` | A download, APK verification, or install step failed. The reason and retry action are shown. |
| `ReadyToInstall` | The APK is verified and ready for the Android install flow. |
| `NeedsInstallPermission` | Airchive needs the Android “install unknown apps” permission before continuing. |
| `Installing` | Android is handling the install session and may ask the owner to confirm. |
| `Cancelled` | The owner cancelled Android’s system confirmation. The app returns to `ReadyToInstall` so Install can be tried again. |
| `Updated` | After restart, the installed version reached the target. “Updated to …” is shown once and the pending record is cleared. |
| `NotCompleted` | After restart, the installed version is still below the target and no install session remains. The owner can retry. |

After `NotCompleted`, retrying the check returns to `Available` if the release
is still newer. An active Android install session defers this outcome until a
later start.

## Unavailable causes and failure reasons

Feed checks can report these causes:

| Cause | Message shown | Meaning |
| --- | --- | --- |
| `OFFLINE` | You’re offline | The feed request could not connect. |
| `TIMEOUT` | The update check timed out | The feed did not answer before the request deadline. |
| `FEED_ERROR` | The release feed could not be read | The response was unsuccessful, malformed, or otherwise unusable. |
| `UNKNOWN_SCHEMA` | The release feed is not supported | The manifest uses a schema version this app cannot read. |
| `PACKAGE_MISMATCH` | The release is for a different app | The manifest package does not match Airchive. |
| `SIGNER_MISMATCH` | The release signer did not match | The manifest signer differs from the certificate pinned in the app. |
| `RELEASE_BUILDS_ONLY` | Available in release builds only | This build does not enable in-app updates. |

Download and APK verification failures are reported in `Failed`:

| Reason | Message shown |
| --- | --- |
| `STORAGE` | Not enough storage space |
| `DOWNLOAD` | The download failed |
| `DOWNLOAD_TOO_LARGE` | The download was larger than expected |
| `APK_SIZE_MISMATCH` | The downloaded file size did not match |
| `APK_INVALID` | The downloaded file could not be read |
| `HASH_MISMATCH` | The downloaded file did not match its checksum |
| `APK_PACKAGE_MISMATCH` | The downloaded file was for a different app |
| `APK_VERSION_MISMATCH` | The downloaded version was not newer |
| `APK_SIGNER_MISMATCH` | The downloaded file has an unexpected signer |
| `APK_HAS_MULTIPLE_SIGNERS` | The downloaded file has multiple signers |
| `APK_DEBUGGABLE` | The downloaded file is a debug build |

Android installation failures are also shown in `Failed`. Cancelling Android’s
confirmation is a separate `Cancelled` outcome and returns to `ReadyToInstall`.

| Reason | Message shown |
| --- | --- |
| `INSTALL_ABORTED` | Android canceled the installation (the normal confirmation cancel is handled as `Cancelled`) |
| `INSTALL_CONFLICT` | The installation conflicts with another package |
| `INSTALL_INCOMPATIBLE` | The update is not compatible with this device |
| `INSTALL_STORAGE` | Android needs more storage to install the update |
| `INSTALL_BLOCKED` | Android blocked the installation |
| `INSTALL_ERROR` | Android could not install the update |

Any partial download or rejected APK is deleted. Leftover partial files are
removed at the next app start.

## Install permission and confirmation

Android may require the owner to allow Airchive to install unknown apps.
`REQUEST_INSTALL_PACKAGES` is declared for the Android package installer; the
owner grants the per-app setting in Android. If it is missing, Airchive explains
why and opens the setting for its own package. Return to Airchive and it checks
the permission again.

Every update uses an Android `PackageInstaller` session and requires the
owner’s system confirmation, including on Android 12 and later after Airchive
has become the installer of record. Airchive does not silently install or use
another install route. The APK is streamed to app-private storage, capped at
the manifest size and 200 MiB, then checked for size, SHA-256, package, version,
debuggable flag, and the pinned single signer before Android is asked to install
it.

## ADB recovery route

The first release carrying the updater must be installed directly. This is also
the recovery route if a broken updater prevents a later in-app update. On the
release Mac, from a clean checkout whose commit is already in `origin/main`, run
the checked install command with an explicit device serial:

```sh
scripts/release/release.sh --install <device-serial>
```

The script builds and verifies the signed release APK, confirms the selected
device is connected, and requires an existing lower-version Airchive signed by
the pinned release certificate. It then runs `adb install -r`. It refuses a
fresh install, a same-or-newer installed version, or a different signer; it
does not uninstall Airchive or clear app data. Release signing must be set up on
the Mac first. See [the Android release runbook](android-release.md#checked-install-recovery)
for signing and release prerequisites.

## Acceptance checklist

Complete the phone acceptance run after the bootstrap release and the next
release are published:

1. Before building the bootstrap version, run the host tests, debug APK and
   instrumentation APK builds, and lint. Run the updater instrumentation tests
   on both the API 27 and API 37 emulators.
2. Install the bootstrap release over the existing app with the ADB recovery
   command above. Confirm the app reports `UpToDate`.
3. Publish the next version. From the phone, follow the Home notice to Settings
   → Update, review the version, release notes, and size, tap **Update**, grant
   the install permission if requested, and confirm Android’s installer dialog.
   After restart, confirm `Updated` and “Updated to <version>”.
4. Compare before and after: sign-in, Room history rows, installation
   preferences, the protected ThinQ token, all five tabs, charts and coverage,
   polling, and control gating. Send no AC command during the update.
5. Repeat and record the failure cases: offline, timeout, install permission
   denied, install cancelled then retried, storage pressure, process death
   during download, long text, and TalkBack across the update UI.
6. Record the results in the go/no-go table on issue #4. Do not call the phone
   acceptance complete until every preservation and failure check is recorded.
