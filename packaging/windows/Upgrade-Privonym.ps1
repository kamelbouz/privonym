[CmdletBinding()]
param(
    [string]$InstallDirectory = "$env:ProgramFiles\Privonym",
    [string]$DataDirectory = "$env:ProgramData\Privonym",
    [string]$JavaPath,
    [string]$TaskName = 'Privonym Server',
    [switch]$SkipRepositoryUpgrade,
    [switch]$NoStart,
    [ValidateRange(10, 300)]
    [int]$HealthTimeoutSeconds = 60
)

$ErrorActionPreference = 'Stop'
$packageRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$payload = Join-Path $packageRoot 'payload'
$runtimeReplacementStarted = $false
$backupDirectory = $null
$taskExists = $false

function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Run this upgrade from an elevated PowerShell window (Run as administrator).'
    }
}

function Resolve-AbsolutePath([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path)) { throw 'An empty filesystem path is not allowed.' }
    $expanded = [Environment]::ExpandEnvironmentVariables($Path)
    if (-not [IO.Path]::IsPathRooted($expanded)) { throw "An absolute path is required: $Path" }
    return [IO.Path]::GetFullPath($expanded).TrimEnd('\')
}

function Assert-ApplicationDirectory([string]$Path) {
    $root = [IO.Path]::GetPathRoot($Path).TrimEnd('\')
    if ($Path.TrimEnd('\') -eq $root) { throw "A drive root cannot be used as InstallDirectory: $Path" }
    $adapter = Join-Path $Path 'app\privonym-oracle-adapter.jar'
    if (-not (Test-Path -LiteralPath $adapter -PathType Leaf)) {
        throw "This is not an installed Privonym runtime (app\privonym-oracle-adapter.jar is missing): $Path"
    }
}

function Assert-DirectChild([string]$Parent, [string]$Child) {
    $resolvedParent = Resolve-AbsolutePath $Parent
    $resolvedChild = Resolve-AbsolutePath $Child
    if ([IO.Directory]::GetParent($resolvedChild).FullName.TrimEnd('\') -ne $resolvedParent) {
        throw "Unsafe upgrade target outside the expected directory: $resolvedChild"
    }
}

function Resolve-Java([object]$InstallationMetadata, [string]$RequestedPath) {
    $candidate = if (-not [string]::IsNullOrWhiteSpace($RequestedPath)) {
        $RequestedPath
    } elseif ($InstallationMetadata -and $InstallationMetadata.JavaPath) {
        [string]$InstallationMetadata.JavaPath
    } else {
        $command = Get-Command java.exe -ErrorAction SilentlyContinue
        if ($command) { $command.Source } else { $null }
    }
    if ([string]::IsNullOrWhiteSpace($candidate) -or
            -not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw 'Java 17 or newer is required; the installed Java executable was not found.'
    }
    $candidate = [IO.Path]::GetFullPath($candidate)
    $startInfo = [Diagnostics.ProcessStartInfo]::new()
    $startInfo.FileName = $candidate
    $startInfo.Arguments = '-version'
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $true
    $startInfo.RedirectStandardOutput = $true
    $startInfo.RedirectStandardError = $true
    try {
        $process = [Diagnostics.Process]::Start($startInfo)
        if (-not $process.WaitForExit(15000)) { $process.Kill(); throw 'Java version check timed out after 15 seconds.' }
        $versionText = (($process.StandardError.ReadToEnd() + "`n" + $process.StandardOutput.ReadToEnd()).Trim())
        if ($process.ExitCode -ne 0) { throw "java -version returned exit code $($process.ExitCode): $versionText" }
    } catch { throw "Unable to start Java executable '$candidate': $($_.Exception.Message)" }
    finally { if ($process) { $process.Dispose() } }
    if ($versionText -notmatch 'version\s+"(?<major>[0-9]+)') { throw "Unable to determine Java version from: $versionText" }
    if ([int]$Matches.major -lt 17) { throw "Java 17 or newer is required; detected Java $($Matches.major) at $candidate." }
    return $candidate
}

function Stop-PrivonymTask {
    param([string]$Name)
    $task = Get-ScheduledTask -TaskName $Name -ErrorAction SilentlyContinue
    if (-not $task) { return $false }
    if ($task.State -eq 'Running') { Stop-ScheduledTask -TaskName $Name }
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $task = Get-ScheduledTask -TaskName $Name -ErrorAction SilentlyContinue
        if (-not $task -or $task.State -ne 'Running') { return $true }
        Start-Sleep -Milliseconds 500
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Scheduled task '$Name' did not stop within 30 seconds."
}

function Wait-RuntimeUnlocked([string]$Directory) {
    $jars = Get-ChildItem -LiteralPath (Join-Path $Directory 'app') -Filter '*.jar' -File
    foreach ($jar in $jars) {
        $deadline = [DateTime]::UtcNow.AddSeconds(20)
        do {
            try {
                $stream = [IO.File]::Open($jar.FullName, [IO.FileMode]::Open,
                    [IO.FileAccess]::Read, [IO.FileShare]::None)
                $stream.Dispose()
                break
            } catch [IO.IOException] {
                if ([DateTime]::UtcNow -ge $deadline) {
                    throw "Runtime file is still in use: $($jar.FullName). Stop the remaining Privonym Java process and retry."
                }
                Start-Sleep -Milliseconds 500
            }
        } while ($true)
    }
}

function Copy-InstalledRuntime([string]$SourceRoot, [string]$DestinationRoot) {
    New-Item -ItemType Directory -Force -Path $DestinationRoot | Out-Null
    foreach ($directory in @('app', 'lib', 'database')) {
        $source = Join-Path $SourceRoot $directory
        if (Test-Path -LiteralPath $source -PathType Container) {
            Copy-Item -LiteralPath $source -Destination $DestinationRoot -Recurse -Force
        }
    }
    foreach ($script in @('Start-PrivonymServer.ps1', 'Uninstall-Privonym.ps1',
            'privonym-job.ps1', 'privonym-job.cmd', 'privonym-job.sh', 'VERSION.txt')) {
        $source = Join-Path $SourceRoot $script
        if (Test-Path -LiteralPath $source -PathType Leaf) {
            Copy-Item -LiteralPath $source -Destination $DestinationRoot -Force
        }
    }
}

function Remove-InstalledDirectory([string]$Root, [string]$Name) {
    $path = Join-Path $Root $Name
    Assert-DirectChild $Root $path
    if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Recurse -Force }
}

function Install-NewRuntime([string]$SourcePayload, [string]$DestinationRoot, [string]$SourcePackageRoot) {
    foreach ($directory in @('app', 'lib')) {
        Remove-InstalledDirectory $DestinationRoot $directory
        Copy-Item -LiteralPath (Join-Path $SourcePayload $directory) -Destination $DestinationRoot -Recurse -Force
    }
    $database = Join-Path $DestinationRoot 'database'
    New-Item -ItemType Directory -Force -Path $database | Out-Null
    $installedMigrations = Join-Path $database 'migrations'
    if (Test-Path -LiteralPath $installedMigrations) {
        Assert-DirectChild $database $installedMigrations
        Remove-Item -LiteralPath $installedMigrations -Recurse -Force
    }
    Copy-Item -LiteralPath (Join-Path $SourcePayload 'database\migrations') -Destination $database -Recurse -Force
    foreach ($script in @('Start-PrivonymServer.ps1', 'Uninstall-Privonym.ps1',
            'privonym-job.ps1', 'privonym-job.cmd', 'privonym-job.sh', 'VERSION.txt')) {
        Copy-Item -LiteralPath (Join-Path $SourcePackageRoot $script) -Destination $DestinationRoot -Force
    }
}

function Restore-InstalledRuntime([string]$BackupRoot, [string]$DestinationRoot) {
    foreach ($directory in @('app', 'lib', 'database')) {
        Remove-InstalledDirectory $DestinationRoot $directory
        $source = Join-Path $BackupRoot $directory
        if (Test-Path -LiteralPath $source -PathType Container) {
            Copy-Item -LiteralPath $source -Destination $DestinationRoot -Recurse -Force
        }
    }
    foreach ($script in @('Start-PrivonymServer.ps1', 'Uninstall-Privonym.ps1',
            'privonym-job.ps1', 'privonym-job.cmd', 'privonym-job.sh', 'VERSION.txt')) {
        $source = Join-Path $BackupRoot $script
        if (Test-Path -LiteralPath $source -PathType Leaf) {
            Copy-Item -LiteralPath $source -Destination $DestinationRoot -Force
        }
    }
}

function Read-Property([string]$Path, [string]$Name) {
    $prefix = "$Name="
    $line = Get-Content -LiteralPath $Path | Where-Object { $_.Trim().StartsWith($prefix) } | Select-Object -Last 1
    if ($line) { return $line.Trim().Substring($prefix.Length).Trim() }
    return $null
}

function Get-HealthUrl([object]$InstallationMetadata, [string]$ConfigurationPath) {
    if ($InstallationMetadata -and $InstallationMetadata.HttpUrl) {
        return ([string]$InstallationMetadata.HttpUrl).TrimEnd('/') + '/api/v1/health'
    }
    $hostName = Read-Property $ConfigurationPath 'server.host'
    $port = Read-Property $ConfigurationPath 'server.port'
    if ([string]::IsNullOrWhiteSpace($hostName)) { $hostName = '127.0.0.1' }
    if ($hostName -eq '0.0.0.0') { $hostName = '127.0.0.1' }
    if ([string]::IsNullOrWhiteSpace($port)) { $port = '8080' }
    return "http://$hostName`:$port/api/v1/health"
}

function Wait-Healthy([string]$Url, [int]$TimeoutSeconds) {
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    do {
        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -eq 200 -and $response.Content -match '"status"\s*:\s*"UP"') { return }
        } catch {
            # The server can refuse connections while the JVM is still starting.
        }
        Start-Sleep -Seconds 1
    } while ([DateTime]::UtcNow -lt $deadline)
    throw "Privonym did not report UP at $Url within $TimeoutSeconds seconds."
}

Assert-Administrator
if (-not (Test-Path -LiteralPath $payload -PathType Container)) { throw "Upgrade payload is missing: $payload" }

$DataDirectory = Resolve-AbsolutePath $DataDirectory
$metadataPath = Join-Path $DataDirectory 'installation.json'
$metadata = if (Test-Path -LiteralPath $metadataPath -PathType Leaf) {
    Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
} else { $null }
if (-not $PSBoundParameters.ContainsKey('InstallDirectory') -and $metadata -and $metadata.InstallDirectory) {
    $InstallDirectory = [string]$metadata.InstallDirectory
}
$InstallDirectory = Resolve-AbsolutePath $InstallDirectory
Assert-ApplicationDirectory $InstallDirectory

$configurationPath = Join-Path $DataDirectory 'config\privonym.properties'
if (-not (Test-Path -LiteralPath $configurationPath -PathType Leaf)) {
    throw "Existing Privonym configuration was not found: $configurationPath"
}
$requiredPayloadFiles = @(
    'app\privonym-domain.jar', 'app\privonym-application.jar',
    'app\privonym-oracle-adapter.jar', 'app\privonym-http-adapter.jar',
    'lib\ojdbc11-21.1.0.0.jar', 'database\migrations\V2_002_transformer_review_publish_oracle.sql'
)
foreach ($relativePath in $requiredPayloadFiles) {
    $path = Join-Path $payload $relativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Upgrade payload file is missing: $path" }
}
foreach ($script in @('Start-PrivonymServer.ps1', 'Uninstall-Privonym.ps1',
        'privonym-job.ps1', 'privonym-job.cmd', 'privonym-job.sh', 'Rollback-Privonym.ps1', 'VERSION.txt')) {
    $path = Join-Path $packageRoot $script
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Upgrade package file is missing: $path" }
}

$javaPath = Resolve-Java $metadata $JavaPath
$taskExists = Stop-PrivonymTask $TaskName
Wait-RuntimeUnlocked $InstallDirectory

$timestamp = [DateTimeOffset]::Now.ToString('yyyyMMdd-HHmmss')
$backupDirectory = Resolve-AbsolutePath (Join-Path $DataDirectory "backups\upgrade-$timestamp")
$backupInstall = Join-Path $backupDirectory 'install'
New-Item -ItemType Directory -Force -Path $backupDirectory | Out-Null
Copy-InstalledRuntime $InstallDirectory $backupInstall
Copy-Item -LiteralPath (Join-Path $packageRoot 'Rollback-Privonym.ps1') -Destination $backupDirectory -Force
$backupManifest = [ordered]@{
    BackupCreatedAt = [DateTimeOffset]::Now.ToString('O')
    InstallDirectory = $InstallDirectory
    DataDirectory = $DataDirectory
    TaskName = $TaskName
    RepositorySchemaRollbackIncluded = $false
} | ConvertTo-Json
[IO.File]::WriteAllText((Join-Path $backupDirectory 'backup.json'), $backupManifest,
        [Text.UTF8Encoding]::new($false))

try {
    if (-not $SkipRepositoryUpgrade) {
        Write-Host ''
        Write-Host 'Upgrading the Oracle repository (the password is not stored)...' -ForegroundColor Cyan
        $upgradeClassPath = "$(Join-Path $payload 'app\*');$(Join-Path $payload 'lib\*')"
        & $javaPath -classpath $upgradeClassPath com.privonym.install.OracleRepositoryUpgrader `
            $configurationPath (Join-Path $payload 'database\migrations')
        if ($LASTEXITCODE -ne 0) { throw "Oracle repository upgrade failed with exit code $LASTEXITCODE." }
    }

    Write-Host 'Installing the new Privonym runtime...' -ForegroundColor Cyan
    $runtimeReplacementStarted = $true
    Install-NewRuntime $payload $InstallDirectory $packageRoot

    if ($taskExists -and -not $NoStart) {
        Start-ScheduledTask -TaskName $TaskName
        $healthUrl = Get-HealthUrl $metadata $configurationPath
        Wait-Healthy $healthUrl $HealthTimeoutSeconds
        Write-Host "Health check passed: $healthUrl" -ForegroundColor Green
    } elseif (-not $taskExists -and -not $NoStart) {
        Write-Warning "Scheduled task '$TaskName' is not installed; start Privonym manually after the upgrade."
    }

    $version = (Get-Content -LiteralPath (Join-Path $packageRoot 'VERSION.txt') -Raw).Trim()
    if (-not $metadata) { $metadata = [pscustomobject]@{} }
    $metadata | Add-Member -NotePropertyName InstallDirectory -NotePropertyValue $InstallDirectory -Force
    $metadata | Add-Member -NotePropertyName DataDirectory -NotePropertyValue $DataDirectory -Force
    $metadata | Add-Member -NotePropertyName JavaPath -NotePropertyValue $javaPath -Force
    $metadata | Add-Member -NotePropertyName Version -NotePropertyValue $version -Force
    $metadata | Add-Member -NotePropertyName UpgradedAt -NotePropertyValue ([DateTimeOffset]::Now.ToString('O')) -Force
    [IO.File]::WriteAllText($metadataPath, ($metadata | ConvertTo-Json), [Text.UTF8Encoding]::new($false))

    Write-Host ''
    Write-Host "Privonym upgrade $version completed." -ForegroundColor Green
    Write-Host "Binary rollback: $backupDirectory"
    Write-Host 'Configuration, repository password and encryption key were not replaced.'
} catch {
    $failure = $_
    Write-Warning "Upgrade failed: $($failure.Exception.Message)"
    if ($runtimeReplacementStarted -and $backupDirectory) {
        Write-Warning 'Restoring the previous application runtime...'
        Restore-InstalledRuntime (Join-Path $backupDirectory 'install') $InstallDirectory
    }
    if ($taskExists -and -not $NoStart) {
        Start-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
    }
    throw "Privonym upgrade failed. Previous binaries were restored. Oracle schema migrations are forward-only. Cause: $($failure.Exception.Message)"
}
