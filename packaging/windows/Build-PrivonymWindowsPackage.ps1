[CmdletBinding()]
param(
    [string]$Version = '2.0-current',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$target = [IO.Path]::GetFullPath((Join-Path $projectRoot 'target')).TrimEnd('\')
$staging = [IO.Path]::GetFullPath((Join-Path $target ("Privonym-Windows-$Version"))).TrimEnd('\')
$archive = "$staging.zip"

function Assert-TargetChild([string]$Path) {
    $resolved = [IO.Path]::GetFullPath($Path).TrimEnd('\')
    if ([IO.Directory]::GetParent($resolved).FullName.TrimEnd('\') -ne $target) {
        throw "Unsafe package output outside target: $resolved"
    }
}

Assert-TargetChild $staging
Assert-TargetChild $archive

if (-not $SkipBuild) { & (Join-Path $projectRoot 'build.ps1') }
$required = @(
    'target\privonym-domain.jar', 'target\privonym-application.jar',
    'target\privonym-oracle-adapter.jar', 'target\privonym-http-adapter.jar',
    'database\generated\V2_001_privonym_repository_oracle.sql',
    'database\generated\V2_001_1_privonym_system_metadata_oracle.sql',
    'lib\postgresql-42.7.13.jar',
    'lib\mssql-jdbc-13.4.0.jre11.jar',
    'privonym-job.ps1', 'privonym-job.cmd', 'privonym-job.sh',
    'packaging\windows\Upgrade-Privonym.ps1', 'packaging\windows\Rollback-Privonym.ps1',
    'packaging\windows\README-UPGRADE.txt', 'packaging\windows\RELEASE-NOTES-2026-08-23.txt'
)
foreach ($relative in $required) {
    $path = Join-Path $projectRoot $relative
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Package input is missing: $path" }
}

if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }
if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive -Force }
New-Item -ItemType Directory -Force -Path $staging,(Join-Path $staging 'payload\app'),(Join-Path $staging 'payload\lib'),(Join-Path $staging 'payload\database') | Out-Null

Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Install-Privonym.ps1'),
    (Join-Path $PSScriptRoot 'Install-PrivonymClean.ps1'),
    (Join-Path $PSScriptRoot 'Install-PrivonymReset.ps1'),
    (Join-Path $PSScriptRoot 'Start-PrivonymServer.ps1'),
    (Join-Path $PSScriptRoot 'Uninstall-Privonym.ps1'),
    (Join-Path $PSScriptRoot 'Upgrade-Privonym.ps1'),
    (Join-Path $PSScriptRoot 'Rollback-Privonym.ps1'),
    (Join-Path $PSScriptRoot 'README-INSTALL.txt'),
    (Join-Path $PSScriptRoot 'README-UPGRADE.txt'),
    (Join-Path $PSScriptRoot 'RELEASE-NOTES-2026-08-23.txt'),
    (Join-Path $projectRoot 'privonym-job.ps1'),
    (Join-Path $projectRoot 'privonym-job.cmd'),
    (Join-Path $projectRoot 'privonym-job.sh') -Destination $staging
Copy-Item -Path (Join-Path $target 'privonym-*.jar') -Destination (Join-Path $staging 'payload\app')
Copy-Item -Path (Join-Path $projectRoot 'lib\*.jar') -Destination (Join-Path $staging 'payload\lib')
Copy-Item -LiteralPath (Join-Path $projectRoot 'database\generated') -Destination (Join-Path $staging 'payload\database') -Recurse
Copy-Item -LiteralPath (Join-Path $projectRoot 'database\migrations') -Destination (Join-Path $staging 'payload\database') -Recurse
Copy-Item -LiteralPath (Join-Path $projectRoot 'database\target-functions') -Destination (Join-Path $staging 'payload\database') -Recurse
[IO.File]::WriteAllText((Join-Path $staging 'VERSION.txt'), $Version + [Environment]::NewLine,
        [Text.UTF8Encoding]::new($false))

Compress-Archive -LiteralPath $staging -DestinationPath $archive -CompressionLevel Optimal
Write-Host "Windows package generated: $archive" -ForegroundColor Green
