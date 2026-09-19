[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$RepositoryUrl,
    [Parameter(Mandatory = $true)]
    [string]$RepositoryUser,
    [Parameter(Mandatory = $true)]
    [string]$JavaPath,
    [string]$InstallDirectory = "$env:ProgramFiles\Privonym",
    [string]$DataDirectory = "$env:ProgramData\Privonym",
    [string]$BindAddress = '127.0.0.1',
    [ValidateRange(1, 65535)]
    [int]$HttpPort = 8080,
    [bool]$InstallStartupTask = $true
)

$ErrorActionPreference = 'Stop'
$installer = Join-Path $PSScriptRoot 'Install-Privonym.ps1'
if (-not (Test-Path -LiteralPath $installer -PathType Leaf)) {
    throw "Privonym installer is missing: $installer"
}

Write-Host 'PRIVONYM DESTRUCTIVE CLEAN INSTALLATION' -ForegroundColor Yellow
Write-Host "Repository schema: $RepositoryUser"
Write-Host 'An existing Privonym repository in this schema will be completely erased.'
Write-Host 'Connections, catalogues, Rule Sets, Jobs, reports metadata and Privonym users will be deleted.'
Write-Host ''

& $installer `
    -RepositoryUrl $RepositoryUrl `
    -RepositoryUser $RepositoryUser `
    -JavaPath $JavaPath `
    -InstallDirectory $InstallDirectory `
    -DataDirectory $DataDirectory `
    -BindAddress $BindAddress `
    -HttpPort $HttpPort `
    -InitializeRepository $true `
    -ResetRepository $true `
    -InstallStartupTask $InstallStartupTask
