param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9._-]+$')]
    [string]$Server,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z0-9._-]+$')]
    [string]$User,

    [Parameter(Mandatory = $true)]
    [ValidatePattern('^[A-Za-z][A-Za-z0-9_-]{2,99}$')]
    [string]$Job,

    [ValidateRange(1, 65535)]
    [int]$SshPort = 22
)

$normalizedJob = $Job.ToUpperInvariant()
$target = "$User@$Server"
$remoteCommand = "curl.exe --silent --show-error --fail-with-body --request POST --header `"Accept: application/json`" http://127.0.0.1:8080/api/v1/jobs/$normalizedJob/execute"

& ssh -p $SshPort -- $target $remoteCommand
if ($LASTEXITCODE -ne 0) {
    throw "Remote Privonym Job execution failed with exit code $LASTEXITCODE"
}
