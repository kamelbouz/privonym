[CmdletBinding()]
param([Parameter(Mandatory=$true)][string]$Archive,[switch]$CheckService)
$ErrorActionPreference='Stop'
$temporaryBase=[IO.Path]::GetFullPath([IO.Path]::GetTempPath())
$testRoot=Join-Path $temporaryBase ('privonym-kit-test-'+[guid]::NewGuid().ToString('N'))
$serviceExecutable=$null
try {
 New-Item -ItemType Directory -Path $testRoot | Out-Null
 Expand-Archive -LiteralPath $Archive -DestinationPath (Join-Path $testRoot 'extracted')
 $payload=@(Get-ChildItem -LiteralPath (Join-Path $testRoot 'extracted') -Directory)
 if($payload.Count -ne 1){throw 'Unexpected archive root'}
 $payload=$payload[0].FullName
 foreach($line in [IO.File]::ReadAllLines((Join-Path $payload 'SHA256SUMS'))) {
  if($line -notmatch '^([a-f0-9]{64})  (.+)$'){throw 'Invalid checksum manifest'}
  $relative=$Matches[2]
  if($relative.Contains('..') -or [IO.Path]::IsPathRooted($relative)){throw 'Unsafe archive manifest path'}
  if((Get-FileHash -LiteralPath (Join-Path $payload $relative)).Hash.ToLowerInvariant() -ne $Matches[1]){throw 'Kit checksum mismatch'}
 }
 if(Get-ChildItem -LiteralPath (Join-Path $payload 'config') -File | Where-Object {$_.Name -notlike '*.example'}){throw 'Kit contains private configuration'}
 if(Get-ChildItem -LiteralPath $payload -Recurse -File | Where-Object {$_.Extension -eq '.key' -or $_.Name -match '(?i)oidc|nimbus'}){throw 'Unexpected key or deferred SSO component in kit'}
 $installed=Join-Path $testRoot 'installed with spaces'
 & (Join-Path $payload 'Install-Privonym.ps1') -Destination $installed -Port 18082
 foreach($file in @('target\privonym-oracle-adapter.jar','privonym-job.cmd','privonym-server.cmd','config\privonym-job.properties','config\privonym-secret.key')) {
  if(-not(Test-Path -LiteralPath (Join-Path $installed $file))){throw "Installed file missing: $file"}
 }
 if(-not (Get-Acl -LiteralPath (Join-Path $installed 'config')).AreAccessRulesProtected){throw 'Private configuration ACL inheritance was not removed'}
 $key=[Convert]::FromBase64String([IO.File]::ReadAllText((Join-Path $installed 'config\privonym-secret.key')))
 if($key.Length -ne 32){throw 'Invalid initial encryption key'}
 $before=(Get-FileHash -LiteralPath (Join-Path $installed 'config\privonym.properties')).Hash
 $refused=$false
 try { & (Join-Path $payload 'Install-Privonym.ps1') -Destination $installed } catch {$refused=$true}
 if(-not $refused -or (Get-FileHash -LiteralPath (Join-Path $installed 'config\privonym.properties')).Hash -ne $before){throw 'Existing installation was replaced'}
 $imported=Join-Path $testRoot 'imported configuration'
 & (Join-Path $payload 'Install-Privonym.ps1') -Destination $imported -RepositoryConfig (Join-Path $installed 'config\privonym.properties') -Port 18083
 if((Get-FileHash -LiteralPath (Join-Path $imported 'config\privonym-secret.key')).Hash -ne (Get-FileHash -LiteralPath (Join-Path $installed 'config\privonym-secret.key')).Hash){throw 'Existing encryption key was not preserved'}
 $start=New-Object Diagnostics.ProcessStartInfo
 $start.FileName=$env:ComSpec
 $start.Arguments='/d /s /c ""'+(Join-Path $installed 'privonym-job.cmd')+'" 0"'
 $start.UseShellExecute=$false; $start.CreateNoWindow=$true
 $start.RedirectStandardOutput=$true; $start.RedirectStandardError=$true
 $process=[Diagnostics.Process]::Start($start)
 $stdout=$process.StandardOutput.ReadToEnd(); $stderr=$process.StandardError.ReadToEnd(); $process.WaitForExit()
 if($process.ExitCode -ne 1 -or $stderr -notmatch 'JOB_ID must be positive'){throw ('Windows Job launcher returned '+$process.ExitCode+': '+$stdout+' '+$stderr)}
 if($CheckService) {
  $serviceName='PrivonymKitFixture'+[guid]::NewGuid().ToString('N').Substring(0,8)
  $servicePath=Join-Path $testRoot 'service installation'
  & (Join-Path $payload 'Install-Privonym.ps1') -Destination $servicePath -RegisterService -ServiceName $serviceName -JavaHome $env:JAVA_HOME
  $serviceExecutable=Join-Path $servicePath "service\$serviceName.exe"
  $service=Get-Service -Name $serviceName
  if($service.Status -ne 'Stopped' -or $service.StartType -ne 'Manual'){throw 'Installer unexpectedly started/enabled the service'}
  & $serviceExecutable uninstall
  if($LASTEXITCODE -ne 0){throw 'Test service cleanup failed'}
  $serviceExecutable=$null
 }
 Write-Output 'Windows kit passed: manifest, installation with spaces, protected configuration, original-key preservation, overwrite refusal and CLI exit code. No database or job was touched.'
} finally {
 if($serviceExecutable){& $serviceExecutable uninstall | Out-Null}
 $resolved=[IO.Path]::GetFullPath($testRoot)
 $prefix=$temporaryBase.TrimEnd('\')+'\privonym-kit-test-'
 if(-not $resolved.StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase)){throw 'Unsafe test cleanup path'}
 if(Test-Path -LiteralPath $resolved){Remove-Item -LiteralPath $resolved -Recurse -Force}
}
