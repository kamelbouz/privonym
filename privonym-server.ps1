[CmdletBinding()]
param([string]$JavaPath = 'java')

$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$temporaryBase = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$runRoot = Join-Path $temporaryBase ('privonym-server-' + [guid]::NewGuid().ToString('N'))
$serverExitCode = 1
$jarNames = @(
    'privonym-domain.jar',
    'privonym-application.jar',
    'privonym-oracle-adapter.jar',
    'privonym-http-adapter.jar'
)

try {
    New-Item -ItemType Directory -Path $runRoot | Out-Null
    foreach ($jarName in $jarNames) {
        $source = Join-Path $projectRoot (Join-Path 'target' $jarName)
        if (-not (Test-Path -LiteralPath $source -PathType Leaf)) {
            throw "Missing $source. Run build.ps1 first."
        }
        Copy-Item -LiteralPath $source -Destination (Join-Path $runRoot $jarName)
    }

    if (-not $env:PRIVONYM_CONFIG) {
        $env:PRIVONYM_CONFIG = Join-Path $projectRoot 'config\privonym.properties'
    }

    $secretKeyFile = Join-Path $projectRoot 'config\privonym-secret.key'
    & $JavaPath "-Dprivonym.secret.key.file=$secretKeyFile" -classpath "$runRoot\*;$projectRoot\lib\*" `
        com.privonym.server.PrivonymServerLauncher
    $serverExitCode = $LASTEXITCODE
} finally {
    $resolvedRunRoot = [IO.Path]::GetFullPath($runRoot)
    $safePrefix = $temporaryBase.TrimEnd([IO.Path]::DirectorySeparatorChar) `
            + [IO.Path]::DirectorySeparatorChar + 'privonym-server-'
    if ($resolvedRunRoot.StartsWith($safePrefix, [StringComparison]::OrdinalIgnoreCase) -and
            (Test-Path -LiteralPath $resolvedRunRoot)) {
        $cleanupError = $null
        for ($attempt = 1; $attempt -le 20; $attempt++) {
            try {
                Remove-Item -LiteralPath $resolvedRunRoot -Recurse -Force
                $cleanupError = $null
                break
            } catch {
                $cleanupError = $_.Exception
                if ($attempt -lt 20) {
                    Start-Sleep -Milliseconds 250
                }
            }
        }
        if (Test-Path -LiteralPath $resolvedRunRoot) {
            Write-Warning "Unable to remove temporary server files after waiting for Java to release them: $($cleanupError.Message)"
        }
    }
}

exit $serverExitCode
