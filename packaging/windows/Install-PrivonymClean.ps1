[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$RepositoryUrl,
    [Parameter(Mandatory = $true)]
    [string]$RepositoryUser,
    [string]$InstallDirectory = "$env:ProgramFiles\Privonym",
    [string]$DataDirectory = "$env:ProgramData\Privonym",
    [string]$BindAddress = '127.0.0.1',
    [string]$JavaPath,
    [ValidateRange(1, 65535)]
    [int]$HttpPort = 8080,
    [bool]$InstallStartupTask = $true
)

$ErrorActionPreference = 'Stop'
$installer = Join-Path $PSScriptRoot 'Install-Privonym.ps1'
if (-not (Test-Path -LiteralPath $installer -PathType Leaf)) {
    throw "Privonym installer is missing: $installer"
}

Write-Host 'Privonym clean installation' -ForegroundColor Cyan
Write-Host 'The Oracle repository schema must be empty.'
Write-Host 'Only Privonym model and system metadata will be installed.'
Write-Host 'No environment, connection, catalogue, Rule Set, Job or Privonym user will be created.'
Write-Host ''

$parameters = @{
    RepositoryUrl = $RepositoryUrl
    RepositoryUser = $RepositoryUser
    InstallDirectory = $InstallDirectory
    DataDirectory = $DataDirectory
    BindAddress = $BindAddress
    HttpPort = $HttpPort
    InitializeRepository = $true
    InstallStartupTask = $InstallStartupTask
}
if (-not [string]::IsNullOrWhiteSpace($JavaPath)) {
    $parameters.JavaPath = $JavaPath
}

& $installer @parameters

Write-Host ''
Write-Host 'Clean installation verified: system metadata is complete and user metadata is empty.' -ForegroundColor Green
