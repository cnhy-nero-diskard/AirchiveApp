# Private Android release signing and upgrade guide

## Signing setup status

Android Studio's **Build → Generate Signed Bundle / APK → APK** wizard is
also supported. Select the existing keystore and alias, enter the passwords
locally, and choose **release**. There is no need to create another key or set
PowerShell environment variables when using this wizard. AGP validates the
wizard's signing credentials; the release preflight accepts its complete signing
configuration and still rejects builds with no signing setup.

Gradle is configured to sign a private release APK from these **process
environment variables** (not BuildConfig, resources, or the APK):

- `AIRCHIVE_RELEASE_STORE_FILE`
- `AIRCHIVE_RELEASE_STORE_PASSWORD`
- `AIRCHIVE_RELEASE_KEY_ALIAS`
- `AIRCHIVE_RELEASE_KEY_PASSWORD`

The `verifyPrivateReleaseSigning` preflight fails closed when any value is
missing or the keystore path does not exist. Debug builds do not require signing
variables. No release key has been configured or registered in this workflow yet.

## 7.1 — Create and register the private release key

Create the key outside the repository. `keytool` prompts for passwords; do not
put passwords in command arguments, source files, `local.properties`, or chat:

```powershell
$releaseDir = Join-Path $env:USERPROFILE '.airchive'
New-Item -ItemType Directory -Force $releaseDir | Out-Null
keytool -genkeypair -v `
  -keystore (Join-Path $releaseDir 'airchive-release.jks') `
  -alias airchive-release `
  -keyalg RSA -keysize 4096 -validity 10000 -storetype JKS
```

Back up the `.jks` file and its passwords securely outside Git. To obtain the
public certificate fingerprints (not the private key):

```powershell
keytool -list -v `
  -keystore (Join-Path $releaseDir 'airchive-release.jks') `
  -alias airchive-release
```

In Firebase Console, open the existing project’s Android app
`cloud.airchive.personal` and add the certificate’s **SHA-1** fingerprint to
its SHA certificate fingerprints. Add SHA-256 as well if the project’s app
protections require it, and verify Google sign-in is enabled. See Firebase’s
[Google sign-in setup](https://firebase.google.com/docs/auth/android/google-signin)
and Android’s [app-signing guidance](https://developer.android.com/studio/publish/app-signing).

Set the keystore path and alias locally. The following prompts keep password
typing out of shell history and command output; Gradle receives them only in
this PowerShell process:

```powershell
$env:AIRCHIVE_RELEASE_STORE_FILE = Join-Path $releaseDir 'airchive-release.jks'
$env:AIRCHIVE_RELEASE_KEY_ALIAS = 'airchive-release'

function Set-AirchiveSecret([string]$Name, [string]$Prompt) {
  $secure = Read-Host -Prompt $Prompt -AsSecureString
  $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
  try {
    $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    [Environment]::SetEnvironmentVariable($Name, $plain, 'Process')
  } finally {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
    $secure.Dispose()
  }
}

Set-AirchiveSecret 'AIRCHIVE_RELEASE_STORE_PASSWORD' 'Keystore password'
Set-AirchiveSecret 'AIRCHIVE_RELEASE_KEY_PASSWORD' 'Key password'
```

The first signed release used version code 1. The default is now 2 for the
same-key upgrade test. Build the signed release:

```powershell
.\gradlew.bat --no-configuration-cache --no-daemon :androidApp:assembleRelease
```

Use a temporary PowerShell process for the secret variables, do not persist them
as user/system environment variables, and use `--no-configuration-cache` so
Gradle does not store their values in its configuration cache.

The APK is at
`androidApp\build\outputs\apk\release\androidApp-release.apk`. Verify it and
compare its signing certificate with the SHA fingerprint registered in Firebase:

```powershell
$buildTools = Get-ChildItem (Join-Path $env:LOCALAPPDATA 'Android\Sdk\build-tools') -Directory |
  Sort-Object Name -Descending | Select-Object -First 1
& (Join-Path $buildTools.FullName 'apksigner.bat') verify --verbose --print-certs `
  androidApp\build\outputs\apk\release\androidApp-release.apk
```

Remove the password variables from this PowerShell process when finished:

```powershell
Remove-Item Env:AIRCHIVE_RELEASE_STORE_PASSWORD, Env:AIRCHIVE_RELEASE_KEY_PASSWORD `
  -ErrorAction SilentlyContinue
```

## 7.2 — Test a same-key release upgrade

The phone currently has a debug-signed app. Android will reject a release APK
signed by a different key as an in-place update. Uninstalling the debug app
deletes its local Room history and Android Keystore PAT; history can be synced
again, but the PAT must be entered again. **Do not uninstall until you are
ready for that one-time reset.**

To verify the required upgrade preservation, install a signed release first,
sign in, sync history, and enter the PAT. Then build a higher-version APK with
the same key and install it as an update, without uninstalling:

```powershell
.\gradlew.bat --no-configuration-cache --no-daemon `
  :androidApp:assembleRelease -Pairchive.versionCode=2
$adb = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
& $adb -s <phone-serial> install -r `
  androidApp\build\outputs\apk\release\androidApp-release.apk
```

Verify sign-in, Room history, masked PAT status, navigation, and the supported
profile-gated controls survive the same-signing-identity update. Do not send
control commands without explicit values/authorization. Increment
`airchive.versionCode` for every subsequent release build.

## Current boundary

The owner reports registering SHA-1 and SHA-256 in Firebase. The version-code-1
release APK passed `apksigner verify` with a 4096-bit RSA release certificate
(SHA-256 `2cf5480e933008a24e469d7936cb4abd76cc2352e26ddf3c2a8b4effd4b54202`),
distinct from the debug certificate. After the approved debug uninstall, ADB
installed and launched it on phone `9XDQCM6HLB5DO7YH`. The owner reported finishing
sign-in, history sync, and PAT entry. No PAT was read by the assistant.

The same-key version-code-2 update and post-update preservation checks have not
yet run. This evidence alone does not complete 7.1's tracked-file secret scan
or 7.2's upgrade and control acceptance requirements.
