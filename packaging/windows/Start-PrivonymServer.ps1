[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$InstallDirectory,
    [Parameter(Mandatory = $true)][string]$DataDirectory,
    [Parameter(Mandatory = $true)][string]$JavaPath
)

$ErrorActionPreference = 'Stop'
$env:PRIVONYM_CONFIG = Join-Path $DataDirectory 'config\privonym.properties'
$secretKey = Join-Path $DataDirectory 'config\privonym-secret.key'
$log = Join-Path $DataDirectory 'logs\privonym-server.log'
$classPath = "$(Join-Path $InstallDirectory 'app\*');$(Join-Path $InstallDirectory 'lib\*')"

try {
    & $JavaPath "-Dprivonym.secret.key.file=$secretKey" -classpath $classPath `
        com.privonym.server.PrivonymServerLauncher *>> $log
    exit $LASTEXITCODE
} catch {
    "[$([DateTimeOffset]::Now.ToString('O'))] STARTUP ERROR: $($_.Exception.Message)" | Out-File -LiteralPath $log -Append -Encoding utf8
    exit 1
}
