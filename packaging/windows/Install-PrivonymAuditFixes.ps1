[CmdletBinding(SupportsShouldProcess)]
param([string]$InstallDirectory = 'C:\Privonym')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Verify-PrivonymPackage.ps1')
Test-PrivonymPackage $PSScriptRoot
if (-not [IO.Path]::IsPathRooted($InstallDirectory)) { throw 'An absolute installation directory is required.' }
$root = [IO.Path]::GetFullPath($InstallDirectory).TrimEnd('\')
if (-not (Test-Path -LiteralPath (Join-Path $root 'target\privonym-oracle-adapter.jar') -PathType Leaf)) {
    throw 'Expected the development installation layout with target\privonym-oracle-adapter.jar.'
}
if (-not (Test-Path -LiteralPath (Join-Path $root 'config\privonym-secret.key') -PathType Leaf)) {
    throw 'The existing local encryption key is required. Restore it before installing these fixes.'
}
$files = @('target\privonym-domain.jar','target\privonym-application.jar',
        'target\privonym-http-adapter.jar','target\privonym-oracle-adapter.jar',
        'lib\postgresql-42.7.14.jar')
$oldDriver = Join-Path $root 'lib\postgresql-42.7.13.jar'
$locks = @()
try {
    # Hold every old binary exclusively until replacement starts; never stop a running job.
    foreach ($path in @($files | ForEach-Object { Join-Path $root $_ }) + @($oldDriver)) {
        if (Test-Path -LiteralPath $path -PathType Leaf) {
            try { $locks += [IO.File]::Open($path, 'Open', 'ReadWrite', 'None') }
            catch { throw 'Stop the Privonym server and any Privonym CLI job before installing the fixes.' }
        }
    }
} finally { foreach ($lock in $locks) { $lock.Dispose() } }
if (-not $PSCmdlet.ShouldProcess($root, 'Install audit correction binaries with backup; no database migration')) { return }
$backup = Join-Path $root ('backups\audit-fixes-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff'))
New-Item -ItemType Directory -Force -Path (Join-Path $backup 'target'),(Join-Path $backup 'lib') | Out-Null
$existing = @{}
foreach ($relative in $files) {
    $current = Join-Path $root $relative
    if (Test-Path -LiteralPath $current -PathType Leaf) {
        Copy-Item -LiteralPath $current -Destination (Join-Path $backup $relative)
        $existing[$relative] = $true
    }
}
if (Test-Path -LiteralPath $oldDriver -PathType Leaf) {
    Copy-Item -LiteralPath $oldDriver -Destination (Join-Path $backup 'lib\postgresql-42.7.13.jar')
}
try {
    foreach ($relative in $files) {
        $source = Join-Path $PSScriptRoot ('payload\' + $relative)
        $destination = Join-Path $root $relative
        Copy-Item -LiteralPath $source -Destination $destination -Force
        if ((Get-FileHash -LiteralPath $source).Hash -ne (Get-FileHash -LiteralPath $destination).Hash) {
            throw "Installed binary checksum mismatch: $relative"
        }
    }
    if (Test-Path -LiteralPath $oldDriver -PathType Leaf) { Remove-Item -LiteralPath $oldDriver -Force }
} catch {
    foreach ($relative in $files) {
        if ($existing.ContainsKey($relative)) {
            Copy-Item -LiteralPath (Join-Path $backup $relative) -Destination (Join-Path $root $relative) -Force
        } elseif (Test-Path -LiteralPath (Join-Path $root $relative)) {
            Remove-Item -LiteralPath (Join-Path $root $relative) -Force
        }
    }
    $savedDriver = Join-Path $backup 'lib\postgresql-42.7.13.jar'
    if (Test-Path -LiteralPath $savedDriver) { Copy-Item -LiteralPath $savedDriver -Destination $oldDriver -Force }
    throw
}
Write-Output "Audit fixes installed. Backup: $backup. Start Privonym with its usual launcher."
