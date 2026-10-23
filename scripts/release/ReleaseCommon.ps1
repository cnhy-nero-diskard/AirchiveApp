Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Set-ProcessSecret([string]$Name, [Security.SecureString]$Secret) {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Secret)
    try { [Environment]::SetEnvironmentVariable($Name, [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer), 'Process') }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
}

function Protect-LocalSigningFile([string]$Path) {
    # Modify only the DACL, not owner/audit sections that require elevated privileges.
    $acl = [IO.File]::GetAccessControl($Path, [Security.AccessControl.AccessControlSections]::Access)
    $acl.SetAccessRuleProtection($true, $false)
    $owner = [Security.Principal.WindowsIdentity]::GetCurrent().User
    foreach ($rule in @($acl.Access)) { $null = $acl.RemoveAccessRuleSpecific($rule) }
    foreach ($sid in @($owner, (New-Object Security.Principal.SecurityIdentifier('S-1-5-18')))) {
        $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($sid, 'FullControl', 'Allow')))
    }
    [IO.File]::SetAccessControl($Path, $acl)
}

function Get-ReleaseTools {
    $sdk = if ($env:ANDROID_HOME) { $env:ANDROID_HOME } else { Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
    $tools = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory |
        Where-Object { $_.Name -match '^\d+\.\d+\.\d+$' } |
        Sort-Object { [version]$_.Name } -Descending | Select-Object -First 1
    if (!$tools) { throw 'Android SDK build-tools are missing.' }
    return @{ Signer = Join-Path $tools.FullName 'apksigner.bat'; Aapt = Join-Path $tools.FullName 'aapt.exe'; Adb = Join-Path $sdk 'platform-tools\adb.exe' }
}

function Assert-ReleaseCertificate([string]$Apk, [string]$Signer) {
    $result = & $Signer verify --print-certs $Apk 2>&1 | Out-String
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $expected = '2cf5480e933008a24e469d7936cb4abd76cc2352e26ddf3c2a8b4effd4b54202'
    $digests = [regex]::Matches($result, 'Signer #\d+ certificate SHA-256 digest: ([a-fA-F0-9]+)')
    if ($digests.Count -ne 1 -or $digests[0].Groups[1].Value -ne $expected) {
        throw 'APK does not have the pinned Airchive release signing identity.'
    }
}
