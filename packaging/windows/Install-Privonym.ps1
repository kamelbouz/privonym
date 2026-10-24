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
    [bool]$InitializeRepository = $true,
    [bool]$ResetRepository = $false,
    [bool]$InstallStartupTask = $true
)

$ErrorActionPreference = 'Stop'
$taskName = 'Privonym Server'
$packageRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$payload = Join-Path $packageRoot 'payload'

function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Run this installer from an elevated PowerShell window (Run as administrator).'
    }
}

function Resolve-Java([string]$RequestedPath) {
    $candidate = $RequestedPath
    if ([string]::IsNullOrWhiteSpace($candidate)) {
        $java = Get-Command java.exe -ErrorAction SilentlyContinue
        if ($java) { $candidate = $java.Source }
    }
    if ([string]::IsNullOrWhiteSpace($candidate) -or -not (Test-Path -LiteralPath $candidate -PathType Leaf)) {
        throw 'Java 17 or newer is required. Supply its full path with -JavaPath, for example C:\Tools\jdk-17\bin\java.exe.'
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
    $major = [int]$Matches.major
    if ($major -lt 17) { throw "Java 17 or newer is required; detected Java $major at $candidate." }
    Write-Host "Using Java ${major}: $candidate" -ForegroundColor Cyan
    return $candidate
}

Assert-Administrator
. (Join-Path $packageRoot 'Verify-PrivonymPackage.ps1')
Test-PrivonymPackage $packageRoot
$javaPath = Resolve-Java $JavaPath
if (Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue) {
    Write-Host "Stopping existing scheduled task '$taskName' before installation..." -ForegroundColor Cyan
    Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    $deadline = [DateTime]::UtcNow.AddSeconds(30)
    do {
        $task = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
        if (-not $task -or $task.State -ne 'Running') { break }
        Start-Sleep -Milliseconds 250
    } while ([DateTime]::UtcNow -lt $deadline)
    if ($task -and $task.State -eq 'Running') {
        throw "The existing Privonym scheduled task did not stop within 30 seconds."
    }
    Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
}
if (-not (Test-Path -LiteralPath $payload -PathType Container)) {
    throw "Installer payload is missing: $payload"
}
$requiredRuntimeFiles = @(
    'lib\jetty-server-12.0.38.jar', 'lib\jetty-http-12.0.38.jar',
    'lib\jetty-io-12.0.38.jar', 'lib\jetty-util-12.0.38.jar',
    'lib\ojdbc11-21.1.0.0.jar', 'lib\postgresql-42.7.14.jar',
    'lib\mssql-jdbc-13.4.0.jre11.jar', 'app\privonym-oracle-adapter.jar',
    'app\privonym-http-adapter.jar'
)
foreach ($relativePath in $requiredRuntimeFiles) {
    $runtimeFile = Join-Path $payload $relativePath
    if (-not (Test-Path -LiteralPath $runtimeFile -PathType Leaf)) { throw "Installer runtime file is missing: $runtimeFile" }
}
if ($RepositoryUrl -notmatch '^jdbc:oracle:thin:@') {
    throw 'RepositoryUrl must be an Oracle JDBC URL, for example jdbc:oracle:thin:@//db:1521/SERVICE.'
}
if ([string]::IsNullOrWhiteSpace($RepositoryUser)) { throw 'RepositoryUser is required.' }

New-Item -ItemType Directory -Force -Path $InstallDirectory | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $DataDirectory 'config') | Out-Null
New-Item -ItemType Directory -Force -Path (Join-Path $DataDirectory 'logs') | Out-Null
& (Join-Path $packageRoot 'Set-PrivonymPermissions.ps1') -Directory @($InstallDirectory, $DataDirectory)
Copy-Item -LiteralPath (Join-Path $payload 'app') -Destination $InstallDirectory -Recurse -Force
Copy-Item -LiteralPath (Join-Path $payload 'lib') -Destination $InstallDirectory -Recurse -Force
Copy-Item -LiteralPath (Join-Path $payload 'database') -Destination $InstallDirectory -Recurse -Force
Copy-Item -LiteralPath (Join-Path $packageRoot 'Start-PrivonymServer.ps1') -Destination $InstallDirectory -Force
Copy-Item -LiteralPath (Join-Path $packageRoot 'Uninstall-Privonym.ps1') -Destination $InstallDirectory -Force
Copy-Item -LiteralPath (Join-Path $packageRoot 'privonym-job.ps1') -Destination $InstallDirectory -Force
Copy-Item -LiteralPath (Join-Path $packageRoot 'privonym-job.cmd') -Destination $InstallDirectory -Force
Copy-Item -LiteralPath (Join-Path $packageRoot 'privonym-job.sh') -Destination $InstallDirectory -Force
if (Test-Path -LiteralPath (Join-Path $packageRoot 'VERSION.txt') -PathType Leaf) {
    Copy-Item -LiteralPath (Join-Path $packageRoot 'VERSION.txt') -Destination $InstallDirectory -Force
}

$configuration = @(
    "repository.url=$RepositoryUrl"
    "repository.user=$RepositoryUser"
    "server.host=$BindAddress"
    "server.port=$HttpPort"
) -join [Environment]::NewLine
$configurationPath = Join-Path $DataDirectory 'config\privonym.properties'
[IO.File]::WriteAllText($configurationPath, $configuration + [Environment]::NewLine,
        [Text.UTF8Encoding]::new($false))

if ($InitializeRepository) {
    $baseline = Join-Path $InstallDirectory 'database\generated\V2_001_privonym_repository_oracle.sql'
    $metadata = Join-Path $InstallDirectory 'database\generated\V2_001_1_privonym_system_metadata_oracle.sql'
    $migrations = Join-Path $InstallDirectory 'database\migrations'
    foreach ($requiredFile in @($baseline, $metadata)) {
        if (-not (Test-Path -LiteralPath $requiredFile -PathType Leaf)) { throw "Repository installation file is missing: $requiredFile" }
    }
    if (-not (Test-Path -LiteralPath $migrations -PathType Container)) { throw "Repository migration directory is missing: $migrations" }
    Write-Host ''
    Write-Host 'Initializing the empty Oracle repository schema...' -ForegroundColor Cyan
    $classPath = "$(Join-Path $InstallDirectory 'app\*');$(Join-Path $InstallDirectory 'lib\*')"
    $repositoryInstallerArguments = @(
        '-classpath', $classPath, 'com.privonym.install.OracleRepositoryInstaller',
        $configurationPath, $baseline, $metadata, $migrations
    )
    if ($ResetRepository) { $repositoryInstallerArguments += '--reset' }
    & $javaPath @repositoryInstallerArguments
    if ($LASTEXITCODE -ne 0) { throw "Repository initialization failed with exit code $LASTEXITCODE." }
}

$installMetadata = @{
    InstallDirectory = $InstallDirectory
    DataDirectory = $DataDirectory
    JavaPath = $javaPath
    HttpUrl = "http://$($BindAddress -replace '^0\.0\.0\.0$','localhost'):$HttpPort/"
    InstalledAt = [DateTimeOffset]::Now.ToString('O')
} | ConvertTo-Json
[IO.File]::WriteAllText((Join-Path $DataDirectory 'installation.json'), $installMetadata,
        [Text.UTF8Encoding]::new($false))

if ($InstallStartupTask) {
    $existing = Get-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
    if ($existing) {
        Stop-ScheduledTask -TaskName $taskName -ErrorAction SilentlyContinue
        Unregister-ScheduledTask -TaskName $taskName -Confirm:$false
    }
    $launcher = Join-Path $InstallDirectory 'Start-PrivonymServer.ps1'
    $arguments = "-NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File `"$launcher`" -InstallDirectory `"$InstallDirectory`" -DataDirectory `"$DataDirectory`" -JavaPath `"$javaPath`""
    $action = New-ScheduledTaskAction -Execute 'powershell.exe' -Argument $arguments
    $trigger = New-ScheduledTaskTrigger -AtStartup
    $principal = New-ScheduledTaskPrincipal -UserId 'SYSTEM' -LogonType ServiceAccount -RunLevel Highest
    $settings = New-ScheduledTaskSettingsSet -RestartCount 5 -RestartInterval (New-TimeSpan -Minutes 1) -StartWhenAvailable -ExecutionTimeLimit ([TimeSpan]::Zero)
    Register-ScheduledTask -TaskName $taskName -Action $action -Trigger $trigger -Principal $principal -Settings $settings -Description 'Privonym HTTP anonymization server' | Out-Null
    Start-ScheduledTask -TaskName $taskName
}

Write-Host ''
Write-Host 'Privonym installation completed.' -ForegroundColor Green
Write-Host "Application: $InstallDirectory"
Write-Host "Configuration: $configurationPath"
Write-Host "Logs: $(Join-Path $DataDirectory 'logs')"
Write-Host "Console: http://localhost:$HttpPort/login"
Write-Host 'The Oracle repository password is entered from the login page and is not stored on disk.'
