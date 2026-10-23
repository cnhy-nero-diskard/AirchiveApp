# Noninteractive smoke checks. Uses dummy secrets only, never real signing credentials.
. "$PSScriptRoot\ReleaseCommon.ps1"
$temporary = Join-Path ([IO.Path]::GetTempPath()) ('airchive-signing-tests-' + [guid]::NewGuid())
$previousProfile = $env:USERPROFILE
$secret = ConvertTo-SecureString 'nonsecret-test-placeholder' -AsPlainText -Force
try {
    Get-ChildItem "$PSScriptRoot\*.ps1" | ForEach-Object {
        $tokens = $null; $errors = $null
        $null = [Management.Automation.Language.Parser]::ParseFile($_.FullName, [ref]$tokens, [ref]$errors)
        if ($errors.Count) { throw "Parse error in $($_.Name)" }
    }
    New-Item -ItemType Directory -Path $temporary | Out-Null
    $path = Join-Path $temporary 'dummy.clixml'
    [pscustomobject]@{ Password = $secret } | Export-Clixml -LiteralPath $path
    Protect-LocalSigningFile $path
    if ((Get-Content -LiteralPath $path -Raw).Contains('nonsecret-test-placeholder')) { throw 'Dummy password was saved in plaintext.' }
    $acl = Get-Acl -LiteralPath $path
    if (!$acl.AreAccessRulesProtected -or $acl.Access.Count -ne 2) { throw 'Credential ACL not restricted.' }
    $loaded = Import-Clixml -LiteralPath $path
    Set-ProcessSecret 'AIRCHIVE_AUTOMATION_TEST_SECRET' $loaded.Password
    if ($env:AIRCHIVE_AUTOMATION_TEST_SECRET -ne 'nonsecret-test-placeholder') { throw 'DPAPI round trip failed.' }
    $loaded.Password.Dispose()
    $env:USERPROFILE = $temporary
    try {
        & "$PSScriptRoot\Invoke-AirchiveRelease.ps1"
        throw 'Missing credential guard failed.'
    } catch {
        if ($_.Exception.Message -notmatch 'Run Setup-AirchiveSigning.ps1 once') { throw }
    }
    try {
        & "$PSScriptRoot\Invoke-AirchiveRelease.ps1" -Install
        throw 'Explicit device guard failed.'
    } catch {
        if ($_.Exception.Message -notmatch 'explicit DeviceSerial') { throw }
    }
    Write-Host 'PASS: script parsing, DPAPI round trip, restricted ACL, missing credentials and explicit-device guards.'
} finally {
    $env:USERPROFILE = $previousProfile
    Remove-Item Env:AIRCHIVE_AUTOMATION_TEST_SECRET -ErrorAction SilentlyContinue
    $secret.Dispose()
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Recurse -Force }
}
