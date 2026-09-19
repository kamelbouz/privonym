@echo off
setlocal
if "%~1"=="" (
    echo Usage: privonym-job.cmd JOB_ID 1>&2
    exit /b 2
)
powershell.exe -NoLogo -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "%~dp0privonym-job.ps1" "%~1" "%~2"
exit /b %ERRORLEVEL%
