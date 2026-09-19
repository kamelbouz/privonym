param(
    [Parameter(Mandatory = $true, Position = 0)]
    [long]$JobId,
    [Parameter(Position = 1)]
    [string]$ConfigurationFile
)

$root = $PSScriptRoot
$appDirectory = if (Test-Path -LiteralPath (Join-Path $root 'app\privonym-oracle-adapter.jar') -PathType Leaf) {
    Join-Path $root 'app'
} else {
    Join-Path $root 'target'
}
$jar = Join-Path $appDirectory 'privonym-oracle-adapter.jar'
if (-not (Test-Path -LiteralPath $jar)) {
    [Console]::Error.WriteLine('Privonym is not built. Run build.cmd first.')
    exit 2
}
if ([string]::IsNullOrWhiteSpace($ConfigurationFile)) {
    $ConfigurationFile = if ([string]::IsNullOrWhiteSpace($env:PRIVONYM_JOB_CONFIG)) {
        $installedConfiguration = Join-Path $env:ProgramData 'Privonym\config\privonym.properties'
        if ((Split-Path -Leaf $appDirectory) -eq 'app' -and
                (Test-Path -LiteralPath $installedConfiguration -PathType Leaf)) {
            $installedConfiguration
        } else {
            Join-Path $root 'config\privonym-job.properties'
        }
    } else { $env:PRIVONYM_JOB_CONFIG }
}
$classPath = "$appDirectory\*;$root\lib\*"
$secretKeyFile = if (-not [string]::IsNullOrWhiteSpace($env:PRIVONYM_SECRET_KEY_FILE)) {
    $env:PRIVONYM_SECRET_KEY_FILE
} elseif ((Split-Path -Leaf $appDirectory) -eq 'app') {
    Join-Path $env:ProgramData 'Privonym\config\privonym-secret.key'
} else {
    Join-Path $root 'config\privonym-secret.key'
}
if (-not (Test-Path -LiteralPath $secretKeyFile -PathType Leaf)) {
    [Console]::Error.WriteLine("Privonym credential key was not found: $secretKeyFile")
    exit 2
}
& java "-Dprivonym.secret.key.file=$secretKeyFile" -classpath $classPath `
    com.privonym.cli.PrivonymConfiguredJobRunner ([string]$JobId) $ConfigurationFile
exit $LASTEXITCODE
