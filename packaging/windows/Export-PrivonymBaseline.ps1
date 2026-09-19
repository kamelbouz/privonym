[CmdletBinding()]
param(
    [string]$ConfigurationPath = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'config\privonym.properties'),
    [string]$OutputPath = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'database\generated\V2_001_privonym_repository_oracle.sql'),
    [string]$MetadataOutputPath = (Join-Path (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)) 'database\generated\V2_001_1_privonym_system_metadata_oracle.sql')
)

$ErrorActionPreference = 'Stop'

if (-not (Test-Path -LiteralPath $ConfigurationPath -PathType Leaf)) {
    throw "Privonym configuration was not found: $ConfigurationPath"
}
$sqlplus = Get-Command sqlplus.exe -ErrorAction SilentlyContinue
if (-not $sqlplus) { throw 'Oracle SQL*Plus is required to export the reference repository.' }

$configuration = @{}
foreach ($line in Get-Content -LiteralPath $ConfigurationPath) {
    if ($line -match '^\s*([^#!][^=]*)=(.*)$') { $configuration[$Matches[1].Trim()] = $Matches[2].Trim() }
}
$url = $configuration['repository.url']
$user = $configuration['repository.user']
if ([string]::IsNullOrWhiteSpace($url) -or [string]::IsNullOrWhiteSpace($user)) {
    throw 'repository.url and repository.user are required in the Privonym configuration.'
}
if ($url -notmatch '^jdbc:oracle:thin:@(?<connect>.+)$') {
    throw "Unsupported Oracle JDBC URL: $url"
}

$outputDirectory = Split-Path -Parent $OutputPath
New-Item -ItemType Directory -Force -Path $outputDirectory | Out-Null
$metadataOutputDirectory = Split-Path -Parent $MetadataOutputPath
New-Item -ItemType Directory -Force -Path $metadataOutputDirectory | Out-Null
$exportScript = Join-Path $PSScriptRoot 'Export-PrivonymBaseline.sql'
if (-not (Test-Path -LiteralPath $exportScript -PathType Leaf)) {
    throw "Baseline export SQL is missing: $exportScript"
}

Write-Host "Exporting the Privonym repository model from $user@$($Matches.connect)"
Write-Host 'SQL*Plus will request the repository password. It is not saved.' -ForegroundColor Cyan
& $sqlplus.Source "$user@$($Matches.connect)" "@$exportScript" $OutputPath $MetadataOutputPath
if ($LASTEXITCODE -ne 0) { throw "Repository export failed with SQL*Plus exit code $LASTEXITCODE." }
if (-not (Test-Path -LiteralPath $OutputPath -PathType Leaf)) { throw 'The baseline file was not generated.' }
if (-not (Test-Path -LiteralPath $MetadataOutputPath -PathType Leaf)) { throw 'The system metadata file was not generated.' }
Write-Host "Baseline generated: $OutputPath" -ForegroundColor Green
Write-Host "System metadata generated: $MetadataOutputPath" -ForegroundColor Green
