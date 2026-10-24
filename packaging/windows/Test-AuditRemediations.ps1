$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Verify-PrivonymPackage.ps1')
foreach ($script in Get-ChildItem -LiteralPath $PSScriptRoot -Filter '*.ps1') {
    $parseErrors = $null
    [Management.Automation.Language.Parser]::ParseFile($script.FullName, [ref]$null, [ref]$parseErrors) | Out-Null
    if ($parseErrors.Count) { throw "PowerShell syntax errors in $($script.Name): $parseErrors" }
}
$temporaryRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')
$fixture = Join-Path $temporaryRoot ('privonym-audit-test-' + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixture | Out-Null
try {
    $file = Join-Path $fixture 'payload.txt'
    $manifest = Join-Path $fixture 'SHA256SUMS.txt'
    [IO.File]::WriteAllText($file, 'synthetic package')
    $hash = (Get-FileHash -LiteralPath $file -Algorithm SHA256).Hash
    [IO.File]::WriteAllText($manifest, "$hash  payload.txt")
    Test-PrivonymPackage $fixture
    [IO.File]::WriteAllText($file, 'tampered package')
    $rejected = $false
    try { Test-PrivonymPackage $fixture } catch { $rejected = $true }
    if (-not $rejected) { throw 'Tampered packages must be rejected.' }
    [IO.File]::WriteAllText($manifest, "$hash  ../outside.txt")
    $rejected = $false
    try { Test-PrivonymPackage $fixture } catch { $rejected = $true }
    if (-not $rejected) { throw 'Checksum path traversal must be rejected.' }
    & (Join-Path $PSScriptRoot 'Set-PrivonymPermissions.ps1') -Directory $fixture
    foreach ($path in @($fixture, $file)) {
        $acl = Get-Acl -LiteralPath $path
        if (-not $acl.AreAccessRulesProtected) { throw 'Sensitive paths must not inherit broad permissions.' }
        foreach ($rule in $acl.Access) {
            $sid = $rule.IdentityReference.Translate([Security.Principal.SecurityIdentifier]).Value
            if ($sid -in @('S-1-1-0','S-1-5-11','S-1-5-32-545')) {
                throw 'Generic Windows users must not retain access.'
            }
        }
    }
    $package = Join-Path $fixture 'package'
    $installation = Join-Path $fixture 'installation'
    New-Item -ItemType Directory -Force -Path (Join-Path $package 'payload\target'),
        (Join-Path $package 'payload\lib'),(Join-Path $installation 'target'),
        (Join-Path $installation 'lib'),(Join-Path $installation 'config') | Out-Null
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'Install-PrivonymAuditFixes.ps1'),
        (Join-Path $PSScriptRoot 'Verify-PrivonymPackage.ps1') -Destination $package
    foreach ($name in @('privonym-domain.jar','privonym-application.jar','privonym-http-adapter.jar','privonym-oracle-adapter.jar')) {
        [IO.File]::WriteAllText((Join-Path $package "payload\target\$name"), "new synthetic $name")
        [IO.File]::WriteAllText((Join-Path $installation "target\$name"), "old synthetic $name")
    }
    [IO.File]::WriteAllText((Join-Path $package 'payload\lib\postgresql-42.7.14.jar'), 'new synthetic driver')
    [IO.File]::WriteAllText((Join-Path $installation 'lib\postgresql-42.7.13.jar'), 'old synthetic driver')
    [IO.File]::WriteAllText((Join-Path $installation 'config\privonym-secret.key'), 'synthetic key placeholder')
    $checksums = Get-ChildItem -LiteralPath $package -Recurse -File | ForEach-Object {
        $relative = $_.FullName.Substring($package.Length + 1).Replace('\', '/')
        "$(Get-FileHash -LiteralPath $_.FullName | Select-Object -ExpandProperty Hash)  $relative"
    }
    [IO.File]::WriteAllLines((Join-Path $package 'SHA256SUMS.txt'), $checksums)
    $held = [IO.File]::Open((Join-Path $installation 'lib\postgresql-42.7.13.jar'), 'Open', 'ReadWrite', 'None')
    try {
        $rejected = $false
        try { & (Join-Path $package 'Install-PrivonymAuditFixes.ps1') -InstallDirectory $installation }
        catch { $rejected = $true }
        if (-not $rejected) { throw 'Installation must refuse a loaded driver.' }
    } finally { $held.Dispose() }
    & (Join-Path $package 'Install-PrivonymAuditFixes.ps1') -InstallDirectory $installation
    if (Test-Path -LiteralPath (Join-Path $installation 'lib\postgresql-42.7.13.jar')) {
        throw 'Old driver must leave the runtime classpath.'
    }
    if (([IO.File]::ReadAllText((Join-Path $installation 'lib\postgresql-42.7.14.jar'))) -ne 'new synthetic driver') {
        throw 'New driver was not installed.'
    }
    if ((Get-ChildItem -LiteralPath (Join-Path $installation 'backups') -Recurse -Filter 'postgresql-42.7.13.jar').Count -ne 1) {
        throw 'Old driver backup is required.'
    }
    Write-Output 'Windows audit remediation checks passed.'
} finally {
    $resolvedFixture = [IO.Path]::GetFullPath($fixture)
    if ([IO.Directory]::GetParent($resolvedFixture).FullName.TrimEnd('\') -ne $temporaryRoot -or
            [IO.Path]::GetFileName($resolvedFixture) -notmatch '^privonym-audit-test-[0-9a-f]{32}$') {
        throw 'Unsafe test cleanup path.'
    }
    Remove-Item -LiteralPath $resolvedFixture -Recurse -Force
}
