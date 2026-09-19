@echo off
setlocal
set "PRIVONYM_ROOT=%~dp0"
set "PRIVONYM_JAR=%PRIVONYM_ROOT%target\privonym-oracle-adapter.jar"

if not exist "%PRIVONYM_JAR%" (
    echo Privonym is not built. Run build.cmd first. 1>&2
    exit /b 2
)

if not defined PRIVONYM_CONFIG (
    set "PRIVONYM_CONFIG=%PRIVONYM_ROOT%config\privonym.properties"
)

java -Dprivonym.secret.key.file="%PRIVONYM_ROOT%config\privonym-secret.key" -classpath "%PRIVONYM_ROOT%target\privonym-domain.jar;%PRIVONYM_ROOT%target\privonym-application.jar;%PRIVONYM_JAR%;%PRIVONYM_ROOT%lib\*" ^
    com.privonym.cli.PrivonymCli %*
exit /b %ERRORLEVEL%
