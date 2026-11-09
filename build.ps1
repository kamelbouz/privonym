[CmdletBinding()]
param(
    [string]$JdkHome = $env:JAVA_HOME
)

$ErrorActionPreference = 'Stop'

$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
$sourceRoot = Join-Path $projectRoot 'src\main\java'
$testRoot = Join-Path $projectRoot 'src\test\java'
$domainRoot = Join-Path $projectRoot 'modules\privonym-domain'
$domainSourceRoot = Join-Path $domainRoot 'src\main\java'
$domainTestRoot = Join-Path $domainRoot 'src\test\java'
$applicationRoot = Join-Path $projectRoot 'modules\privonym-application'
$applicationSourceRoot = Join-Path $applicationRoot 'src\main\java'
$applicationTestRoot = Join-Path $applicationRoot 'src\test\java'
$httpRoot = Join-Path $projectRoot 'modules\privonym-http-adapter'
$httpSourceRoot = Join-Path $httpRoot 'src\main\java'
$httpResourceRoot = Join-Path $httpRoot 'src\main\resources'
$httpTestRoot = Join-Path $httpRoot 'src\test\java'
$targetRoot = Join-Path $projectRoot 'target'
$buildRoot = Join-Path $targetRoot ('.build-' + [guid]::NewGuid().ToString('N'))
$domainClassesRoot = Join-Path $buildRoot 'domain-classes'
$domainTestClassesRoot = Join-Path $buildRoot 'domain-test-classes'
$applicationClassesRoot = Join-Path $buildRoot 'application-classes'
$applicationTestClassesRoot = Join-Path $buildRoot 'application-test-classes'
$httpClassesRoot = Join-Path $buildRoot 'http-classes'
$httpTestClassesRoot = Join-Path $buildRoot 'http-test-classes'
$classesRoot = Join-Path $buildRoot 'classes'
$testClassesRoot = Join-Path $buildRoot 'test-classes'
$jarPath = Join-Path $targetRoot 'privonym-oracle-adapter.jar'
$domainJarPath = Join-Path $targetRoot 'privonym-domain.jar'
$applicationJarPath = Join-Path $targetRoot 'privonym-application.jar'
$httpJarPath = Join-Path $targetRoot 'privonym-http-adapter.jar'
$httpLibraryNames = @(
    'jetty-server-12.0.38.jar',
    'jetty-http-12.0.38.jar',
    'jetty-io-12.0.38.jar',
    'jetty-util-12.0.38.jar',
    'slf4j-api-2.0.7.jar',
    'slf4j-simple-2.0.7.jar'
)
$httpLibraryPaths = $httpLibraryNames | ForEach-Object { Join-Path $projectRoot ("lib\" + $_) }
$missingHttpLibraries = $httpLibraryPaths | Where-Object { -not (Test-Path -LiteralPath $_ -PathType Leaf) }
if ($missingHttpLibraries) {
    throw "Missing HTTP runtime libraries: $($missingHttpLibraries -join ', ')"
}
$libraryClasspath = $httpLibraryPaths -join ';'

$javacCommand = if (-not [string]::IsNullOrWhiteSpace($JdkHome)) {
    $configuredCompiler = Join-Path $JdkHome 'bin\javac.exe'
    if (-not (Test-Path -LiteralPath $configuredCompiler -PathType Leaf)) {
        throw "JdkHome/JAVA_HOME must point to a complete JDK: $configuredCompiler is missing."
    }
    Get-Command -Name $configuredCompiler -ErrorAction Stop
} else {
    Get-Command 'javac' -ErrorAction SilentlyContinue
}
if (-not $javacCommand) {
    throw 'A JDK 17 or newer is required; javac is unavailable.'
}

$javaSettingsProcessInfo = New-Object System.Diagnostics.ProcessStartInfo
$javaSettingsProcessInfo.FileName = $javacCommand.Source
$javaSettingsProcessInfo.Arguments = '-J-XshowSettings:properties -version'
$javaSettingsProcessInfo.UseShellExecute = $false
$javaSettingsProcessInfo.RedirectStandardOutput = $false
$javaSettingsProcessInfo.RedirectStandardError = $true
$javaSettingsProcessInfo.CreateNoWindow = $true
$javaSettingsProcess = New-Object System.Diagnostics.Process
$javaSettingsProcess.StartInfo = $javaSettingsProcessInfo
if (-not $javaSettingsProcess.Start()) {
    throw 'Unable to start javac to determine the JDK home directory.'
}
$javaSettingsError = $javaSettingsProcess.StandardError.ReadToEnd()
$javaSettingsProcess.WaitForExit()
if ($javaSettingsProcess.ExitCode -ne 0) {
    throw "Unable to query JDK settings; javac exited with code $($javaSettingsProcess.ExitCode)."
}
$javaSettings = $javaSettingsError
$javaHomeMatch = [regex]::Match($javaSettings, '(?m)^\s*java\.home\s*=\s*(.+?)\s*$')
if (-not $javaHomeMatch.Success) {
    throw 'Unable to determine the JDK home directory from javac.'
}

$jdkBin = Join-Path $javaHomeMatch.Groups[1].Value 'bin'
$javacCommandPath = Join-Path $jdkBin 'javac.exe'
$javaCommandPath = Join-Path $jdkBin 'java.exe'
$jarCommandPath = Join-Path $jdkBin 'jar.exe'

foreach ($commandPath in @($javacCommandPath, $javaCommandPath, $jarCommandPath)) {
    if (-not (Test-Path -LiteralPath $commandPath -PathType Leaf)) {
        throw "Incomplete JDK installation; required command is unavailable: $commandPath"
    }
}

New-Item -ItemType Directory -Force -Path $domainClassesRoot | Out-Null
New-Item -ItemType Directory -Force -Path $domainTestClassesRoot | Out-Null
New-Item -ItemType Directory -Force -Path $applicationClassesRoot | Out-Null
New-Item -ItemType Directory -Force -Path $applicationTestClassesRoot | Out-Null
New-Item -ItemType Directory -Force -Path $httpClassesRoot | Out-Null
New-Item -ItemType Directory -Force -Path $httpTestClassesRoot | Out-Null
New-Item -ItemType Directory -Force -Path $classesRoot | Out-Null
New-Item -ItemType Directory -Force -Path $testClassesRoot | Out-Null

$domainSources = Get-ChildItem -LiteralPath $domainSourceRoot -Recurse -Filter '*.java' |
    Select-Object -ExpandProperty FullName

if (-not $domainSources) {
    throw 'No Privonym domain sources were found.'
}

& $javacCommandPath --release 17 `
    -classpath $domainClassesRoot `
    -Xlint:all `
    -Werror `
    -encoding UTF-8 `
    -d $domainClassesRoot `
    $domainSources

if ($LASTEXITCODE -ne 0) {
    throw "Domain compilation failed with exit code $LASTEXITCODE"
}

$domainTestSources = Get-ChildItem -LiteralPath $domainTestRoot -Recurse -Filter '*.java' |
    Select-Object -ExpandProperty FullName

& $javacCommandPath --release 17 `
    -classpath $domainClassesRoot `
    -Xlint:all `
    -Werror `
    -encoding UTF-8 `
    -d $domainTestClassesRoot `
    $domainTestSources

if ($LASTEXITCODE -ne 0) {
    throw "Domain test compilation failed with exit code $LASTEXITCODE"
}

& $javaCommandPath -classpath "$domainClassesRoot;$domainTestClassesRoot" `
    com.privonym.domain.DomainSmokeCheck

if ($LASTEXITCODE -ne 0) {
    throw "Domain checks failed with exit code $LASTEXITCODE"
}

$applicationSources = Get-ChildItem -LiteralPath $applicationSourceRoot -Recurse -Filter '*.java' |
    Select-Object -ExpandProperty FullName

& $javacCommandPath --release 17 `
    -classpath $domainClassesRoot `
    -Xlint:all `
    -Werror `
    -encoding UTF-8 `
    -d $applicationClassesRoot `
    $applicationSources

if ($LASTEXITCODE -ne 0) {
    throw "Application compilation failed with exit code $LASTEXITCODE"
}

$applicationTestSources = Get-ChildItem -LiteralPath $applicationTestRoot -Recurse -Filter '*.java' |
    Select-Object -ExpandProperty FullName

& $javacCommandPath --release 17 `
    -classpath "$domainClassesRoot;$applicationClassesRoot" `
    -Xlint:all `
    -Werror `
    -encoding UTF-8 `
    -d $applicationTestClassesRoot `
    $applicationTestSources

if ($LASTEXITCODE -ne 0) {
    throw "Application test compilation failed with exit code $LASTEXITCODE"
}

& $javaCommandPath -classpath "$domainClassesRoot;$applicationClassesRoot;$applicationTestClassesRoot" `
    com.privonym.application.ApplicationSmokeCheck

if ($LASTEXITCODE -ne 0) {
    throw "Application checks failed with exit code $LASTEXITCODE"
}

$httpSources = Get-ChildItem -LiteralPath $httpSourceRoot -Recurse -Filter '*.java' |
    Select-Object -ExpandProperty FullName

& $javacCommandPath --release 17 `
    -classpath "$domainClassesRoot;$applicationClassesRoot;$libraryClasspath" `
    -Xlint:all `
    -Werror `
    -encoding UTF-8 `
    -d $httpClassesRoot `
    $httpSources

if ($LASTEXITCODE -ne 0) {
    throw "HTTP adapter compilation failed with exit code $LASTEXITCODE"
}

if (Test-Path -LiteralPath $httpResourceRoot -PathType Container) {
    Copy-Item -Path (Join-Path $httpResourceRoot '*') -Destination $httpClassesRoot -Recurse -Force
}

$httpTestSources = Get-ChildItem -LiteralPath $httpTestRoot -Recurse -Filter '*.java' |
    Select-Object -ExpandProperty FullName

& $javacCommandPath --release 17 `
    -classpath "$domainClassesRoot;$applicationClassesRoot;$httpClassesRoot;$libraryClasspath" `
    -Xlint:all `
    -Werror `
    -encoding UTF-8 `
    -d $httpTestClassesRoot `
    $httpTestSources

if ($LASTEXITCODE -ne 0) {
    throw "HTTP adapter test compilation failed with exit code $LASTEXITCODE"
}

& $javaCommandPath -classpath "$domainClassesRoot;$applicationClassesRoot;$httpClassesRoot;$httpTestClassesRoot;$libraryClasspath" `
    com.privonym.http.HttpAdapterSmokeCheck

if ($LASTEXITCODE -ne 0) {
    throw "HTTP adapter checks failed with exit code $LASTEXITCODE"
}

$mainSources = Get-ChildItem -LiteralPath $sourceRoot -Recurse -Filter '*.java' |
    Select-Object -ExpandProperty FullName

if (-not $mainSources) {
    throw 'No Java production sources were found.'
}

& $javacCommandPath --release 17 `
    -classpath "$domainClassesRoot;$applicationClassesRoot;$httpClassesRoot;$classesRoot;$libraryClasspath" `
    -Xlint:all `
    -Werror `
    -encoding UTF-8 `
    -d $classesRoot `
    $mainSources

if ($LASTEXITCODE -ne 0) {
    throw "Production compilation failed with exit code $LASTEXITCODE"
}

$testSources = Get-ChildItem -LiteralPath $testRoot -Recurse -Filter '*.java' |
    Select-Object -ExpandProperty FullName

if ($testSources) {
    & $javacCommandPath --release 17 `
        -classpath "$domainClassesRoot;$applicationClassesRoot;$classesRoot" `
        -Xlint:all `
        -Werror `
        -encoding UTF-8 `
        -d $testClassesRoot `
        $testSources

    if ($LASTEXITCODE -ne 0) {
        throw "Test compilation failed with exit code $LASTEXITCODE"
    }

    & $javaCommandPath -classpath "$domainClassesRoot;$applicationClassesRoot;$classesRoot;$testClassesRoot" `
        com.privonym.adapter.oracle.OracleAdapterSmokeCheck

    if ($LASTEXITCODE -ne 0) {
        throw "Smoke checks failed with exit code $LASTEXITCODE"
    }

    & $javaCommandPath -classpath "$domainClassesRoot;$applicationClassesRoot;$classesRoot;$testClassesRoot" `
        com.privonym.repository.oracle.OracleDiscoveryClassifierSmokeCheck

    if ($LASTEXITCODE -ne 0) {
        throw "Discovery classifier checks failed with exit code $LASTEXITCODE"
    }

    & $javaCommandPath -classpath "$domainClassesRoot;$applicationClassesRoot;$classesRoot;$testClassesRoot" `
        com.privonym.repository.oracle.OracleSqlServerPlanSmokeCheck

    if ($LASTEXITCODE -ne 0) {
        throw "SQL Server plan checks failed with exit code $LASTEXITCODE"
    }

    & $javaCommandPath -classpath "$domainClassesRoot;$applicationClassesRoot;$classesRoot;$testClassesRoot" `
        com.privonym.install.OracleMigrationScriptSmokeCheck `
        (Join-Path $projectRoot 'database\migrations') `
        (Join-Path $projectRoot 'database\generated\V2_001_privonym_repository_oracle.sql') `
        (Join-Path $projectRoot 'database\generated\V2_001_1_privonym_system_metadata_oracle.sql')

    if ($LASTEXITCODE -ne 0) {
        throw "Oracle migration script checks failed with exit code $LASTEXITCODE"
    }

}

function New-VerifiedJar {
    param([string]$ClassesDirectory, [string]$OutputFile, [string]$ArgumentsName)
    # Enumerate files once. Explorer/OneDrive may create/delete desktop.ini while
    # jar recursively walks a synchronized directory, causing a packaging race.
    $entries = Get-ChildItem -LiteralPath $ClassesDirectory -Recurse -File |
        Where-Object { $_.Name -ne 'desktop.ini' }
    $arguments = New-Object 'System.Collections.Generic.List[string]'
    $portableRoot = $ClassesDirectory.Replace('\', '/')
    foreach ($entry in $entries) {
        $relative = $entry.FullName.Substring($ClassesDirectory.Length + 1).Replace('\', '/')
        $arguments.Add('-C')
        $arguments.Add('"' + $portableRoot + '"')
        $arguments.Add('"' + $relative + '"')
    }
    $argumentPath = Join-Path $buildRoot $ArgumentsName
    [IO.File]::WriteAllLines($argumentPath, $arguments, (New-Object Text.UTF8Encoding($false)))
    & $jarCommandPath --create --file $OutputFile "@$argumentPath"
    if ($LASTEXITCODE -ne 0) { throw "JAR packaging failed: $OutputFile (exit $LASTEXITCODE)" }
}
New-VerifiedJar $domainClassesRoot $domainJarPath 'domain-jar.args'
New-VerifiedJar $applicationClassesRoot $applicationJarPath 'application-jar.args'
New-VerifiedJar $classesRoot $jarPath 'oracle-jar.args'
New-VerifiedJar $httpClassesRoot $httpJarPath 'http-jar.args'

Write-Host "Offline build completed:"
Write-Host "  $domainJarPath"
Write-Host "  $applicationJarPath"
Write-Host "  $jarPath"
Write-Host "  $httpJarPath"
