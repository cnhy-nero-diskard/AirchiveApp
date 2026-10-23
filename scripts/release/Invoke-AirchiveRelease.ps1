[CmdletBinding()]
param(
    [ValidateRange(1, 2100000000)][int]$VersionCode = 2,
    [switch]$Install,
    [string]$DeviceSerial
)
. "$PSScriptRoot\ReleaseCommon.ps1"
if ($Install -and !$DeviceSerial) { throw 'Installation requires an explicit DeviceSerial.' }
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$credentialPath = Join-Path $env:USERPROFILE '.airchive\signing-credentials.clixml'
if (!(Test-Path -LiteralPath $credentialPath -PathType Leaf)) { throw 'Run Setup-AirchiveSigning.ps1 once to enable unattended builds.' }
$tools = Get-ReleaseTools
$credentials = Import-Clixml -LiteralPath $credentialPath
if ($credentials.StorePassword -isnot [Security.SecureString] -or $credentials.KeyPassword -isnot [Security.SecureString]) { throw 'Invalid protected signing credentials.' }
$names = @('AIRCHIVE_RELEASE_STORE_FILE', 'AIRCHIVE_RELEASE_KEY_ALIAS', 'AIRCHIVE_RELEASE_STORE_PASSWORD', 'AIRCHIVE_RELEASE_KEY_PASSWORD')
$previous = @{}
foreach ($name in $names) { $previous[$name] = [Environment]::GetEnvironmentVariable($name, 'Process') }
$temporary = Join-Path ([IO.Path]::GetTempPath()) ('airchive-upgrade-' + [guid]::NewGuid())
Push-Location $root
try {
    $env:AIRCHIVE_RELEASE_STORE_FILE = $credentials.StoreFile
    $env:AIRCHIVE_RELEASE_KEY_ALIAS = $credentials.KeyAlias
    Set-ProcessSecret 'AIRCHIVE_RELEASE_STORE_PASSWORD' $credentials.StorePassword
    Set-ProcessSecret 'AIRCHIVE_RELEASE_KEY_PASSWORD' $credentials.KeyPassword
    & .\gradlew.bat --no-configuration-cache --no-daemon :androidApp:assembleRelease "-Pairchive.versionCode=$VersionCode"
    if ($LASTEXITCODE -ne 0) { throw 'Signed release build failed; no install attempted.' }
    # Do not pass signing secrets to verification or device tools.
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $null, 'Process') }
    $apk = Join-Path $root 'androidApp\build\outputs\apk\release\androidApp-release.apk'
    Assert-ReleaseCertificate $apk $tools.Signer
    $badging = & $tools.Aapt dump badging $apk 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0 -or $badging -notmatch "package: name='cloud\.airchive\.personal' versionCode='$VersionCode'" -or $badging -match 'application-debuggable') {
        throw 'APK package, build number, or release status did not match.'
    }
    Write-Host "Verified signed release build $VersionCode."
    if ($Install) {
        $adb = $tools.Adb
        $state = & $adb -s $DeviceSerial get-state 2>&1 | Out-String
        if ($LASTEXITCODE -ne 0 -or $state.Trim() -ne 'device') { throw 'Target phone is not connected/authorized; APK is ready for later installation.' }
        $package = & $adb -s $DeviceSerial shell dumpsys package cloud.airchive.personal | Out-String
        if ($LASTEXITCODE -ne 0 -or $package -notmatch 'versionCode=(\d+)') { throw 'Existing Airchive installation not found; refusing a fresh install/reset.' }
        if ([int]$Matches[1] -ge $VersionCode) { throw 'Requested build must be newer than the installed build; choose a higher VersionCode.' }
        $paths = & $adb -s $DeviceSerial shell pm path cloud.airchive.personal
        if ($LASTEXITCODE -ne 0) { throw 'Cannot locate installed APK.' }
        $base = @($paths | Where-Object { $_ -match '^package:.*/base\.apk\s*$' })
        if ($base.Count -ne 1) { throw 'Cannot identify the installed base APK.' }
        New-Item -ItemType Directory -Path $temporary | Out-Null
        $installed = Join-Path $temporary 'installed.apk'
        & $adb -s $DeviceSerial pull ($base[0] -replace '^package:', '').Trim() $installed
        if ($LASTEXITCODE -ne 0) { throw 'Cannot verify installed signing identity; stopping.' }
        Assert-ReleaseCertificate $installed $tools.Signer
        & $adb -s $DeviceSerial install -r $apk
        if ($LASTEXITCODE -ne 0) { throw 'Phone rejected the update; no uninstall or data clear attempted.' }
        & $adb -s $DeviceSerial shell am start -n cloud.airchive.personal/.MainActivity
        if ($LASTEXITCODE -ne 0) { throw 'Update installed but app launch failed.' }
        Write-Host "Build $VersionCode updated in place and launched. Verify sign-in, cached history and PAT in the app."
    }
} finally {
    foreach ($name in $names) { [Environment]::SetEnvironmentVariable($name, $previous[$name], 'Process') }
    $credentials.StorePassword.Dispose(); $credentials.KeyPassword.Dispose()
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Recurse -Force }
    Pop-Location
}
