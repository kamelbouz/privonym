[CmdletBinding()]
param([string]$Version='3.0.0-preview.1')
$ErrorActionPreference='Stop'
if($Version -notmatch '^[0-9][A-Za-z0-9.-]*$'){throw 'Invalid package version'}
$root=Split-Path -Parent $PSScriptRoot
$output=Join-Path $root 'target'
$stage=Join-Path ([IO.Path]::GetTempPath()) ('privonym-package-'+[guid]::NewGuid().ToString('N'))
try {
 foreach($platform in @('Windows','Linux')) {
  $platformRoot=Join-Path $stage $platform
  $payload=Join-Path $platformRoot "Privonym-$Version"
  foreach($directory in @('target','lib','config','database/postgresql','database/generated')) {
   New-Item -ItemType Directory -Path (Join-Path $payload $directory) -Force | Out-Null
  }
  foreach($jar in @('privonym-domain','privonym-application','privonym-http-adapter','privonym-oracle-adapter')) {
   Copy-Item -LiteralPath (Join-Path $root "target/$jar.jar") -Destination (Join-Path $payload 'target')
  }
  Get-ChildItem (Join-Path $root 'lib') -Filter '*.jar' | Copy-Item -Destination (Join-Path $payload 'lib')
  Get-ChildItem (Join-Path $root 'database/postgresql') -Filter '*.sql' | Copy-Item -Destination (Join-Path $payload 'database/postgresql')
  Copy-Item -LiteralPath (Join-Path $root 'database/target-functions') -Destination (Join-Path $payload 'database') -Recurse
  Copy-Item -LiteralPath (Join-Path $root 'database/generated/V2_001_1_privonym_system_metadata_oracle.sql') -Destination (Join-Path $payload 'database/generated')
  foreach($file in @('privonym-postgresql.properties.example','privonym-job-postgresql.properties.example')) {
   Copy-Item -LiteralPath (Join-Path $root "config/$file") -Destination (Join-Path $payload 'config')
  }
  foreach($file in @('REFERENTIEL_POSTGRESQL.md','INSTALLATION_KITS.md')) {
   Copy-Item -LiteralPath (Join-Path $root $file) -Destination $payload
  }
  if($platform -eq 'Windows') {
   foreach($file in @('privonym-server.cmd','privonym-server.ps1','privonym-job.cmd','privonym-job.ps1','privonym-init-postgresql.cmd')) {
    Copy-Item -LiteralPath (Join-Path $root $file) -Destination $payload
   }
   Copy-Item -LiteralPath (Join-Path $root 'packaging/windows/Install-PrivonymKit.ps1') -Destination (Join-Path $payload 'Install-Privonym.ps1')
   Copy-Item -LiteralPath (Join-Path $root 'packaging/windows/install.cmd') -Destination $payload
   New-Item -ItemType Directory -Path (Join-Path $payload 'service') | Out-Null
   $wrapper=Join-Path $root 'packaging/windows/vendor/WinSW-x64-2.12.0.exe'
   if(-not(Test-Path -LiteralPath $wrapper)) {
    Invoke-WebRequest 'https://github.com/winsw/winsw/releases/download/v2.12.0/WinSW-x64.exe' -OutFile $wrapper
   }
   if((Get-FileHash -LiteralPath $wrapper).Hash -ne '05B82D46AD331CC16BDC00DE5C6332C1EF818DF8CEEFCD49C726553209B3A0DA'){throw 'WinSW checksum mismatch'}
   Copy-Item -LiteralPath $wrapper,(Join-Path $root 'packaging/windows/vendor/LICENSE-WinSW.txt') -Destination (Join-Path $payload 'service')
  } else {
   foreach($file in @('privonym-server.sh','privonym-job.sh','privonym-init-postgresql.sh')) {
    Copy-Item -LiteralPath (Join-Path $root $file) -Destination $payload
   }
   Copy-Item -LiteralPath (Join-Path $root 'packaging/linux/install.sh') -Destination $payload
   Get-ChildItem $payload -Filter '*.sh' | ForEach-Object {
    [IO.File]::WriteAllText($_.FullName,[IO.File]::ReadAllText($_.FullName).Replace("`r`n","`n"),[Text.UTF8Encoding]::new($false))
   }
  }
  $manifest=Get-ChildItem $payload -File -Recurse | Sort-Object FullName | ForEach-Object {
   (Get-FileHash -LiteralPath $_.FullName).Hash.ToLowerInvariant()+'  '+$_.FullName.Substring($payload.Length+1).Replace('\','/')
  }
  [IO.File]::WriteAllLines((Join-Path $payload 'SHA256SUMS'),[string[]]$manifest,[Text.UTF8Encoding]::new($false))
  if($platform -eq 'Windows') {
   $archive=Join-Path $output "Privonym-Windows-$Version.zip"
   Compress-Archive -Path $payload -DestinationPath $archive -Force
  } else {
   $archive=Join-Path $output "Privonym-Linux-$Version.tar.gz"
   & tar.exe -czf $archive -C $platformRoot "Privonym-$Version"
   if($LASTEXITCODE -ne 0){throw 'Linux archive creation failed'}
  }
  $hash=(Get-FileHash -LiteralPath $archive).Hash.ToLowerInvariant()
  [IO.File]::WriteAllText($archive+'.sha256',$hash+'  '+[IO.Path]::GetFileName($archive)+"`n",[Text.UTF8Encoding]::new($false))
  Write-Output ($platform+' kit: '+$archive)
 }
} finally {
 $resolved=[IO.Path]::GetFullPath($stage)
 $prefix=[IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\')+'\privonym-package-'
 if(-not $resolved.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe packaging cleanup path'}
 if(Test-Path -LiteralPath $resolved){Remove-Item -LiteralPath $resolved -Recurse -Force}
}
