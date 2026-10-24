function Test-PrivonymPackage([string]$PackageRoot) {
    $root = [IO.Path]::GetFullPath($PackageRoot).TrimEnd('\')
    $manifest = Join-Path $root 'SHA256SUMS.txt'
    if (-not (Test-Path -LiteralPath $manifest -PathType Leaf)) { throw 'Package checksum manifest is missing.' }
    $listed = @{}
    foreach ($line in [IO.File]::ReadAllLines($manifest)) {
        if ($line -notmatch '^([0-9a-fA-F]{64})  (.+)$') { throw 'Invalid package checksum entry.' }
        $expected = $Matches[1]
        $relative = $Matches[2]
        if ([IO.Path]::IsPathRooted($relative)) { throw 'Absolute package checksum paths are forbidden.' }
        $path = [IO.Path]::GetFullPath((Join-Path $root $relative))
        if (-not $path.StartsWith($root + '\', [StringComparison]::OrdinalIgnoreCase)) {
            throw 'Package checksum path escapes the package root.'
        }
        if ($listed.ContainsKey($path)) { throw 'Duplicate package checksum path.' }
        $listed[$path] = $true
        if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { throw "Package file is missing: $relative" }
        if ((Get-Item -LiteralPath $path).Attributes -band [IO.FileAttributes]::ReparsePoint) {
            throw 'Package links are forbidden.'
        }
        if ((Get-FileHash -LiteralPath $path -Algorithm SHA256).Hash -ne $expected) {
            throw "Package checksum mismatch: $relative"
        }
    }
    foreach ($file in Get-ChildItem -LiteralPath $root -File -Recurse) {
        if ($file.FullName -ne $manifest -and -not $listed.ContainsKey($file.FullName)) {
            throw "Package contains an unlisted file: $($file.Name)"
        }
    }
    if ($listed.Count -eq 0) { throw 'Empty package checksum manifest.' }
}
