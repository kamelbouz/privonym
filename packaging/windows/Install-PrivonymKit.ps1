[CmdletBinding()]
param(
 [string]$Destination=(Join-Path $env:LOCALAPPDATA 'Privonym'),
 [string]$RepositoryConfig,
 [string]$EncryptionKey,
 [ValidateRange(1,65535)][int]$Port=8080,
 [switch]$RegisterService,
 [ValidatePattern('^[A-Za-z][A-Za-z0-9_-]{0,50}$')][string]$ServiceName='Privonym',
 [string]$JavaHome=$env:JAVA_HOME
)
$ErrorActionPreference='Stop'
$source=$PSScriptRoot
$destinationPath=[IO.Path]::GetFullPath($Destination)
if($destinationPath -eq [IO.Path]::GetPathRoot($destinationPath)){throw 'Installation in a drive root is refused'}
if(Test-Path -LiteralPath $destinationPath){
 if((Get-Item -LiteralPath $destinationPath).Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'A linked installation directory is refused'}
 if(@(Get-ChildItem -LiteralPath $destinationPath -Force).Count -gt 0){throw 'Destination must be empty. Existing installations and running jobs are never replaced.'}
}
if($RegisterService){
 $admin=[Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
 if(-not $admin){throw 'Run this installer from an administrator PowerShell to register a Windows service'}
 if(Get-Service -Name $ServiceName -ErrorAction SilentlyContinue){throw 'This service already exists. It will not be stopped or replaced.'}
 if($JavaHome){$java=Join-Path $JavaHome 'bin\java.exe'}else{$java=(Get-Command java.exe -ErrorAction Stop).Source}
 if(-not(Test-Path -LiteralPath $java)){throw 'Java 17 or newer is required'}
}
if($RepositoryConfig){
 $RepositoryConfig=(Resolve-Path -LiteralPath $RepositoryConfig).Path
 if(-not $EncryptionKey){$EncryptionKey=Join-Path (Split-Path -Parent $RepositoryConfig) 'privonym-secret.key'}
 if(-not(Test-Path -LiteralPath $EncryptionKey -PathType Leaf)){throw 'An existing repository requires its original encryption key. Specify -EncryptionKey.'}
}
foreach($name in @('target','lib','database')){if(-not(Test-Path -LiteralPath (Join-Path $source $name))){throw "Incomplete kit: $name"}}
New-Item -ItemType Directory -Path $destinationPath -Force | Out-Null
foreach($directory in @('target','lib','database','config','jobs','reports','logs')){
 New-Item -ItemType Directory -Path (Join-Path $destinationPath $directory) -Force | Out-Null
}
foreach($directory in @('target','lib','database')){Get-ChildItem -LiteralPath (Join-Path $source $directory) -Force | Copy-Item -Destination (Join-Path $destinationPath $directory) -Recurse}
foreach($file in @('privonym-server.cmd','privonym-server.ps1','privonym-job.cmd','privonym-job.ps1','privonym-init-postgresql.cmd','INSTALLATION_KITS.md','REFERENTIEL_POSTGRESQL.md')){
 Copy-Item -LiteralPath (Join-Path $source $file) -Destination $destinationPath
}
Get-ChildItem -LiteralPath (Join-Path $source 'config') -Filter '*.example' | Copy-Item -Destination (Join-Path $destinationPath 'config')
$configPath=Join-Path $destinationPath 'config'
$sid=[Security.Principal.WindowsIdentity]::GetCurrent().User.Value
& icacls.exe $configPath /inheritance:r /grant:r "*$($sid):(OI)(CI)F" '*S-1-5-18:(OI)(CI)F' '*S-1-5-32-544:(OI)(CI)F' | Out-Null
if($LASTEXITCODE -ne 0){throw 'Could not protect the configuration directory'}
if($RegisterService){
 & icacls.exe $destinationPath /grant '*S-1-5-19:(OI)(CI)RX' | Out-Null
 if($LASTEXITCODE -ne 0){throw 'Could not grant runtime read access'}
 & icacls.exe $configPath /grant '*S-1-5-19:(OI)(CI)RX' | Out-Null
 if($LASTEXITCODE -ne 0){throw 'Could not grant service configuration access'}
 foreach($directory in @('reports','logs')) {
  & icacls.exe (Join-Path $destinationPath $directory) /grant '*S-1-5-19:(OI)(CI)M' | Out-Null
  if($LASTEXITCODE -ne 0){throw 'Could not grant runtime output access'}
 }
}
if($RepositoryConfig){
 Copy-Item -LiteralPath $RepositoryConfig -Destination (Join-Path $configPath 'privonym.properties')
 Copy-Item -LiteralPath $RepositoryConfig -Destination (Join-Path $configPath 'privonym-job.properties')
 Copy-Item -LiteralPath $EncryptionKey -Destination (Join-Path $configPath 'privonym-secret.key')
}else{
 Copy-Item -LiteralPath (Join-Path $configPath 'privonym-postgresql.properties.example') -Destination (Join-Path $configPath 'privonym.properties')
 Copy-Item -LiteralPath (Join-Path $configPath 'privonym-job-postgresql.properties.example') -Destination (Join-Path $configPath 'privonym-job.properties')
 if($EncryptionKey){Copy-Item -LiteralPath $EncryptionKey -Destination (Join-Path $configPath 'privonym-secret.key')}
 else{
  $bytes=New-Object byte[] 32
  $rng=[Security.Cryptography.RandomNumberGenerator]::Create()
  try{$rng.GetBytes($bytes)}finally{$rng.Dispose()}
  [IO.File]::WriteAllText((Join-Path $configPath 'privonym-secret.key'),[Convert]::ToBase64String($bytes),[Text.UTF8Encoding]::new($false))
  [Array]::Clear($bytes,0,$bytes.Length)
 }
}
[IO.File]::AppendAllText((Join-Path $configPath 'privonym.properties'),"`r`nserver.port=$Port`r`n",[Text.UTF8Encoding]::new($false))
if($RegisterService){
 $serviceDirectory=Join-Path $destinationPath 'service'
 New-Item -ItemType Directory -Path $serviceDirectory | Out-Null
 $wrapper=Join-Path $source 'service\WinSW-x64-2.12.0.exe'
 if((Get-FileHash -LiteralPath $wrapper -Algorithm SHA256).Hash -ne '05B82D46AD331CC16BDC00DE5C6332C1EF818DF8CEEFCD49C726553209B3A0DA'){throw 'Windows service wrapper checksum mismatch'}
 $executable=Join-Path $serviceDirectory "$ServiceName.exe"
 Copy-Item -LiteralPath $wrapper -Destination $executable
 Copy-Item -LiteralPath (Join-Path $source 'service\LICENSE-WinSW.txt') -Destination $serviceDirectory
 $escapedRoot=[Security.SecurityElement]::Escape($destinationPath)
 $escapedJava=[Security.SecurityElement]::Escape($java)
 $powershell=[Security.SecurityElement]::Escape((Join-Path $env:SystemRoot 'System32\WindowsPowerShell\v1.0\powershell.exe'))
 $xml=@"
<service>
 <id>$ServiceName</id><name>$ServiceName</name><description>Privonym server</description>
 <executable>$powershell</executable>
 <arguments>-NoProfile -ExecutionPolicy Bypass -File &quot;$escapedRoot\privonym-server.ps1&quot; -JavaPath &quot;$escapedJava&quot;</arguments>
 <workingdirectory>$escapedRoot</workingdirectory>
 <env name="PRIVONYM_CONFIG" value="$escapedRoot\config\privonym.properties" />
 <serviceaccount><domain>NT AUTHORITY</domain><user>LocalService</user></serviceaccount>
 <startmode>Manual</startmode><stoptimeout>90 sec</stoptimeout>
 <logpath>$escapedRoot\logs</logpath><log mode="roll-by-size"><sizeThreshold>10240</sizeThreshold><keepFiles>4</keepFiles></log>
</service>
"@
 [IO.File]::WriteAllText((Join-Path $serviceDirectory "$ServiceName.xml"),$xml,[Text.UTF8Encoding]::new($false))
 & $executable install
 if($LASTEXITCODE -ne 0){throw 'Service registration failed'}
 Write-Output "Service $ServiceName registered with manual start. No service was started or stopped."
}
Write-Output "Privonym installed in $destinationPath. Configure PostgreSQL, then run privonym-server.cmd."
