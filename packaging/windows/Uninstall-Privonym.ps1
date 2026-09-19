[CmdletBinding()]
param(
    [string]$InstallDirectory = "$env:ProgramFiles\Privonym",
    [string]$DataDirectory = "$env:ProgramData\Privonym",
    [switch]$RemoveData
)

$ErrorActionPreference = 'Stop'
$taskName = 'Privonym Server'
$identity = [Security.Principal.WindowsIdentity]::GetCurrent()
$principal = [Security.Principal.WindowsPrincipal]::new($identity)
if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
    throw 'Run this uninstaller from an elevated PowerShell window.'
}

if (Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue) {
    Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
}
if (Test-Path -LiteralPath $InstallDirectory) {
    Remove-Item -LiteralPath $InstallDirectory -Recurse -Force
}
if ($RemoveData -and (Test-Path -LiteralPath $DataDirectory)) {
    Remove-Item -LiteralPath $DataDirectory -Recurse -Force
    Write-Host 'Privonym application and data removed.' -ForegroundColor Green
} else {
    Write-Host "Privonym application removed. Configuration, key and logs retained in $DataDirectory" -ForegroundColor Green
}
