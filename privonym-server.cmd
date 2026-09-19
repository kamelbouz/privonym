@echo off
setlocal
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0privonym-server.ps1"
exit /b %ERRORLEVEL%
