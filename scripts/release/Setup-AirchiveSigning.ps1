# Run once interactively. Passwords stay in a masked local dialog, never chat.
[CmdletBinding()]
param(
    [string]$StoreFile = (Join-Path $env:USERPROFILE '.airchive\airchive-release.jks'),
    [string]$KeyAlias = 'airchive-release',
    [switch]$BuildAfterSetup,
    [switch]$Install,
    [string]$DeviceSerial,
    [ValidateRange(1, 2100000000)][int]$VersionCode = 2
)
. "$PSScriptRoot\ReleaseCommon.ps1"
if ($Install -and (!$BuildAfterSetup -or !$DeviceSerial)) { throw 'Install requires BuildAfterSetup and an explicit DeviceSerial.' }
if (!(Test-Path -LiteralPath $StoreFile -PathType Leaf)) { throw 'Existing release keystore not found. No new key will be created.' }
$credentialPath = Join-Path $env:USERPROFILE '.airchive\signing-credentials.clixml'
if (Test-Path -LiteralPath $credentialPath) { throw 'Signing credentials already exist. Use Invoke-AirchiveRelease.ps1; setup will not overwrite them.' }
$keytool = (Get-Command keytool.exe -ErrorAction Stop).Source
Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
$form = New-Object Windows.Forms.Form
$form.Text = 'Airchive - one-time release signing setup'
$form.ClientSize = New-Object Drawing.Size(460, 225)
$form.StartPosition = 'CenterScreen'
$form.TopMost = $true
$form.FormBorderStyle = 'FixedDialog'
$form.MaximizeBox = $false
$label = New-Object Windows.Forms.Label
$label.Text = "Enter the password for your EXISTING Airchive keystore.`nSaved encrypted for this Windows account on this PC."
$label.SetBounds(15, 15, 430, 40)
$store = New-Object Windows.Forms.TextBox
$store.UseSystemPasswordChar = $true
$store.SetBounds(15, 65, 425, 25)
$same = New-Object Windows.Forms.CheckBox
$same.Text = 'Key uses the same password (usual default)'
$same.Checked = $true
$same.SetBounds(15, 100, 425, 25)
$key = New-Object Windows.Forms.TextBox
$key.UseSystemPasswordChar = $true
$key.Enabled = $false
$key.SetBounds(15, 130, 425, 25)
$same.Add_CheckedChanged({ $key.Enabled = !$same.Checked })
$save = New-Object Windows.Forms.Button
$save.Text = 'Save and continue'
$save.SetBounds(270, 175, 170, 30)
$save.DialogResult = [Windows.Forms.DialogResult]::OK
$form.Controls.AddRange(@($label, $store, $same, $key, $save))
$form.AcceptButton = $save
$storeSecret = $null
$keySecret = $null
try {
    if ($form.ShowDialog() -ne [Windows.Forms.DialogResult]::OK) { throw 'Signing setup canceled; nothing was saved.' }
    if (!$store.Text -or (!$same.Checked -and !$key.Text)) { throw 'Passwords cannot be empty.' }
    $storeSecret = ConvertTo-SecureString $store.Text -AsPlainText -Force
    $keySecret = if ($same.Checked) { $storeSecret.Copy() } else { ConvertTo-SecureString $key.Text -AsPlainText -Force }
} finally {
    $store.Clear(); $key.Clear(); $form.Dispose()
}
$oldStore = $env:AIRCHIVE_SETUP_STORE_PASSWORD
$oldKey = $env:AIRCHIVE_SETUP_KEY_PASSWORD
$temporary = Join-Path ([IO.Path]::GetTempPath()) ('airchive-signing-' + [guid]::NewGuid())
try {
    New-Item -ItemType Directory -Path $temporary | Out-Null
    Set-ProcessSecret 'AIRCHIVE_SETUP_STORE_PASSWORD' $storeSecret
    Set-ProcessSecret 'AIRCHIVE_SETUP_KEY_PASSWORD' $keySecret
    # A certificate request proves the private key can be unlocked; it exports no private key.
    # Native keytool warnings on stderr are not PowerShell exceptions.
    $oldPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        $null = & $keytool -certreq -keystore $StoreFile -alias $KeyAlias `
            -storepass:env AIRCHIVE_SETUP_STORE_PASSWORD -keypass:env AIRCHIVE_SETUP_KEY_PASSWORD `
            -file (Join-Path $temporary 'validation.csr') 2>&1
        $keytoolExit = $LASTEXITCODE
    } finally { $ErrorActionPreference = $oldPreference }
    if ($keytoolExit -ne 0) { throw 'Keystore validation failed. Check passwords and alias; nothing was saved.' }
    $staged = Join-Path $temporary 'credentials.clixml'
    # Export-Clixml encrypts SecureStrings with Windows CurrentUser DPAPI.
    [pscustomobject]@{ StoreFile = [IO.Path]::GetFullPath($StoreFile); KeyAlias = $KeyAlias; StorePassword = $storeSecret; KeyPassword = $keySecret } |
        Export-Clixml -LiteralPath $staged
    Protect-LocalSigningFile $staged
    New-Item -ItemType Directory -Force -Path (Split-Path $credentialPath) | Out-Null
    Move-Item -LiteralPath $staged -Destination $credentialPath
    Protect-LocalSigningFile $credentialPath
    Write-Host 'Signing credentials validated and saved with Windows account protection.'
} finally {
    [Environment]::SetEnvironmentVariable('AIRCHIVE_SETUP_STORE_PASSWORD', $oldStore, 'Process')
    [Environment]::SetEnvironmentVariable('AIRCHIVE_SETUP_KEY_PASSWORD', $oldKey, 'Process')
    $storeSecret.Dispose(); $keySecret.Dispose()
    if (Test-Path -LiteralPath $temporary) { Remove-Item -LiteralPath $temporary -Recurse -Force }
}
if ($BuildAfterSetup) {
    & "$PSScriptRoot\Invoke-AirchiveRelease.ps1" -VersionCode $VersionCode -Install:$Install -DeviceSerial $DeviceSerial
}
