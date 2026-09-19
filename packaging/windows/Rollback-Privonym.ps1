[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$BackupDirectory,
    [string]$InstallDirectory,
    [string]$TaskName = 'Privonym Server',
    [switch]$NoStart
)

$ErrorActionPreference = 'Stop'

function Assert-Administrator {
    $identity = [Security.Principal.WindowsIdentity]::GetCurrent()
    $principal = [Security.Principal.WindowsPrincipal]::new($identity)
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Run rollback from an elevated PowerShell window (Run as administrator).'
    }
}

function Resolve-AbsolutePath([string]$Path) {
    if ([string]::IsNullOrWhiteSpace($Path) -or -not [IO.Path]::IsPathRooted($Path)) {
        throw "An absolute path is required: $Path"
    }
    return [IO.Path]::GetFullPath($Path).TrimEnd('\')
}

function Remove-InstalledDirectory([string]$Root, [string]$Name) {
    $path = Resolve-AbsolutePath (Join-Path $Root $Name)
    if ([IO.Directory]::GetParent($path).FullName.TrimEnd('\') -ne $Root.TrimEnd('\')) {
        throw "Unsafe rollback target: $path"
    }
    if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Recurse -Force }
}

Assert-Administrator
$BackupDirectory = Resolve-AbsolutePath $BackupDirectory
$manifestPath = Join-Path $BackupDirectory 'backup.json'
$backupInstall = Join-Path $BackupDirectory 'install'
if (-not (Test-Path -LiteralPath $manifestPath -PathType Leaf) -or
        -not (Test-Path -LiteralPath (Join-Path $backupInstall 'app\privonym-oracle-adapter.jar') -PathType Leaf)) {
    throw "This is not a valid Privonym binary backup: $BackupDirectory"
}
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
if ([string]::IsNullOrWhiteSpace($InstallDirectory)) { $InstallDirectory = [string]$manifest.InstallDirectory }
$InstallDirectory = Resolve-AbsolutePath $InstallDirectory
if ($InstallDirectory -eq [IO.Path]::GetPathRoot($InstallDirectory).TrimEnd('\')) {
    throw "A drive root cannot be used as InstallDirectory: $InstallDirectory"
}
if ($manifest.TaskName -and -not $PSBoundParameters.ContainsKey('TaskName')) { $TaskName = [string]$manifest.TaskName }

$task = Get-ScheduledTask -TaskName $TaskName -ErrorAction SilentlyContinue
if ($task -and $task.State -eq 'Running') {
    Stop-ScheduledTask -TaskName $TaskName
    Start-Sleep -Seconds 2
}

foreach ($directory in @('app', 'lib', 'database')) {
    Remove-InstalledDirectory $InstallDirectory $directory
    $source = Join-Path $backupInstall $directory
    if (Test-Path -LiteralPath $source -PathType Container) {
        Copy-Item -LiteralPath $source -Destination $InstallDirectory -Recurse -Force
    }
}
foreach ($script in @('Start-PrivonymServer.ps1', 'Uninstall-Privonym.ps1',
        'privonym-job.ps1', 'privonym-job.cmd', 'privonym-job.sh', 'VERSION.txt')) {
    $source = Join-Path $backupInstall $script
    if (Test-Path -LiteralPath $source -PathType Leaf) {
        Copy-Item -LiteralPath $source -Destination $InstallDirectory -Force
    }
}

if ($task -and -not $NoStart) { Start-ScheduledTask -TaskName $TaskName }
Write-Host 'Privonym application binaries restored.' -ForegroundColor Green
Write-Warning 'This rollback does not reverse Oracle repository migrations; they are designed to remain compatible with the previous runtime.'
