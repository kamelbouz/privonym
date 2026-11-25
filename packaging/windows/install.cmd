@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0Install-Privonym.ps1" %*
exit /b %ERRORLEVEL%
