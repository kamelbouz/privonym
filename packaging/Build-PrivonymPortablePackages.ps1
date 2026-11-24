[CmdletBinding()]
param([string]$Version = '3.0.0-preview')
$ErrorActionPreference='Stop'
if($Version -notmatch '^[0-9][A-Za-z0-9.-]*$'){throw 'Invalid package version'}
$root=Split-Path -Parent $PSScriptRoot
$output=Join-Path $root 'target'
$stage=Join-Path ([IO.Path]::GetTempPath()) ('privonym-package-'+[guid]::NewGuid().ToString('N'))
$payload=Join-Path $stage "Privonym-$Version"
try {
 foreach($directory in @('target','lib','config','database/postgresql','database/generated')){
  New-Item -ItemType Directory -Path (Join-Path $payload $directory) -Force | Out-Null
 }
 foreach($jar in @('privonym-domain','privonym-application','privonym-http-adapter','privonym-oracle-adapter')){
  Copy-Item -LiteralPath (Join-Path $root "target/$jar.jar") -Destination (Join-Path $payload 'target')
 }
 Get-ChildItem (Join-Path $root 'lib') -Filter '*.jar' | Copy-Item -Destination (Join-Path $payload 'lib')
 Get-ChildItem (Join-Path $root 'database/postgresql') -Filter '*.sql' | Copy-Item -Destination (Join-Path $payload 'database/postgresql')
 Copy-Item -LiteralPath (Join-Path $root 'database/generated/V2_001_1_privonym_system_metadata_oracle.sql') -Destination (Join-Path $payload 'database/generated')
 Copy-Item -LiteralPath (Join-Path $root 'config/privonym-postgresql.properties.example') -Destination (Join-Path $payload 'config')
 foreach($file in @('privonym-server.cmd','privonym-server.ps1','privonym-server.sh','privonym-init-postgresql.cmd','privonym-init-postgresql.sh','privonym-job.cmd','privonym-job.ps1','privonym-job.sh','REFERENTIEL_POSTGRESQL.md')){
  Copy-Item -LiteralPath (Join-Path $root $file) -Destination $payload
 }
 # Normalize POSIX launchers; no local config, keys, credentials or user metadata is packaged.
 Get-ChildItem $payload -Filter '*.sh' | ForEach-Object {
  $text=[IO.File]::ReadAllText($_.FullName).Replace("`r`n","`n")
  [IO.File]::WriteAllText($_.FullName,$text,[Text.UTF8Encoding]::new($false))
 }
 $manifest=Get-ChildItem $payload -File -Recurse | Sort-Object FullName | ForEach-Object {
  $relative=$_.FullName.Substring($payload.Length+1).Replace('\','/')
  (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()+"  "+$relative
 }
 [IO.File]::WriteAllLines((Join-Path $payload 'SHA256SUMS'),[string[]]$manifest,[Text.UTF8Encoding]::new($false))
 $windows=Join-Path $output "Privonym-Windows-$Version.zip"
 $linux=Join-Path $output "Privonym-Linux-$Version.tar.gz"
 Compress-Archive -Path $payload -DestinationPath $windows -Force
 & tar.exe -czf $linux -C $stage "Privonym-$Version"
 if($LASTEXITCODE -ne 0){throw 'Linux archive creation failed'}
 Get-FileHash -LiteralPath $windows,$linux -Algorithm SHA256 | Select-Object Path,Hash
} finally {
 $resolved=[IO.Path]::GetFullPath($stage)
 $prefix=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')+'\privonym-package-'
 if(-not $resolved.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe packaging cleanup path'}
 if(Test-Path -LiteralPath $resolved){Remove-Item -LiteralPath $resolved -Recurse -Force}
}
