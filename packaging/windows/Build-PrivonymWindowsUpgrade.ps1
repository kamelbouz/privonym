[CmdletBinding()]
param(
    [string]$Version = '2.5.0',
    [string]$JarDirectory = '',
    [switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$target = [IO.Path]::GetFullPath((Join-Path $projectRoot 'target')).TrimEnd('\')
$staging = [IO.Path]::GetFullPath((Join-Path $target "Privonym-Upgrade-$Version-Windows")).TrimEnd('\')
$archive = "$staging.zip"
$jarSource = if ([string]::IsNullOrWhiteSpace($JarDirectory)) {
    $target
} else {
    [IO.Path]::GetFullPath($JarDirectory).TrimEnd('\')
}
$applicationJars = @(
    'privonym-domain.jar', 'privonym-application.jar',
    'privonym-oracle-adapter.jar', 'privonym-http-adapter.jar'
)

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
    'database\migrations\V2_002_transformer_review_publish_oracle.sql',
    'packaging\windows\Upgrade-Privonym.ps1', 'packaging\windows\Rollback-Privonym.ps1',
    'packaging\windows\Start-PrivonymServer.ps1', 'packaging\windows\Uninstall-Privonym.ps1',
    'packaging\windows\README-UPGRADE.txt', 'packaging\windows\RELEASE-NOTES-2026-08-23.txt',
    'privonym-job.ps1', 'privonym-job.cmd', 'privonym-job.sh'
)
$applicationJars | ForEach-Object {
    $path = Join-Path $jarSource $_
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        throw "Package application JAR is missing: $path"
    }
}
foreach ($relative in $required) {
    $path = Join-Path $projectRoot $relative
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Package input is missing: $path" }
}

if (Test-Path -LiteralPath $staging) { Remove-Item -LiteralPath $staging -Recurse -Force }
if (Test-Path -LiteralPath $archive) { Remove-Item -LiteralPath $archive -Force }
New-Item -ItemType Directory -Force -Path $staging,
    (Join-Path $staging 'payload\app'),
    (Join-Path $staging 'payload\lib'),
    (Join-Path $staging 'payload\database\migrations') | Out-Null

Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Upgrade-Privonym.ps1'),
    (Join-Path $PSScriptRoot 'Rollback-Privonym.ps1'),
    (Join-Path $PSScriptRoot 'Start-PrivonymServer.ps1'),
    (Join-Path $PSScriptRoot 'Uninstall-Privonym.ps1'),
    (Join-Path $PSScriptRoot 'README-UPGRADE.txt'),
    (Join-Path $PSScriptRoot 'RELEASE-NOTES-2026-08-23.txt'),
    (Join-Path $projectRoot 'privonym-job.ps1'),
    (Join-Path $projectRoot 'privonym-job.cmd'),
    (Join-Path $projectRoot 'privonym-job.sh') -Destination $staging
$applicationJars | ForEach-Object {
    Copy-Item -LiteralPath (Join-Path $jarSource $_) -Destination (Join-Path $staging 'payload\app')
}
Copy-Item -Path (Join-Path $projectRoot 'lib\*.jar') -Destination (Join-Path $staging 'payload\lib')
Copy-Item -Path (Join-Path $projectRoot 'database\migrations\*.sql') -Destination (Join-Path $staging 'payload\database\migrations')
Copy-Item -LiteralPath (Join-Path $projectRoot 'database\target-functions') -Destination (Join-Path $staging 'payload\database') -Recurse
[IO.File]::WriteAllText((Join-Path $staging 'VERSION.txt'), $Version + [Environment]::NewLine,
        [Text.UTF8Encoding]::new($false))

$checksums = Get-ChildItem -LiteralPath $staging -Recurse -File |
    Where-Object { $_.Name -ne 'SHA256SUMS.txt' } |
    Sort-Object FullName |
    ForEach-Object {
        $relative = $_.FullName.Substring($staging.Length + 1).Replace('\', '/')
        "$(Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256 | Select-Object -ExpandProperty Hash)  $relative"
    }
[IO.File]::WriteAllLines((Join-Path $staging 'SHA256SUMS.txt'), $checksums,
        [Text.UTF8Encoding]::new($false))

Compress-Archive -LiteralPath $staging -DestinationPath $archive -CompressionLevel Optimal
Write-Host "Windows upgrade package generated: $archive" -ForegroundColor Green
