@echo off
setlocal
if defined JAVA_HOME (set "PRIVONYM_JAVA=%JAVA_HOME%\bin\java.exe") else (set "PRIVONYM_JAVA=java")
if not defined PRIVONYM_CONFIG set "PRIVONYM_CONFIG=%~dp0config\privonym.properties"
"%PRIVONYM_JAVA%" -classpath "%~dp0target\*;%~dp0lib\*" com.privonym.install.PostgresqlRepositoryInstaller "%PRIVONYM_CONFIG%" "%~dp0."
exit /b %ERRORLEVEL%
