@echo off
setlocal
rem PowerShell 7 (pwsh) when it is installed: release-windows.ps1 refuses Windows PowerShell 5.1.
rem No ( ) block: a folder name holding a parenthesis would end it early.
where pwsh >nul 2>nul
if errorlevel 1 goto windowspowershell
pwsh -ExecutionPolicy Bypass -File "%~dp0release-windows.ps1" %*
exit /b %ERRORLEVEL%
:windowspowershell
powershell -ExecutionPolicy Bypass -File "%~dp0release-windows.ps1" %*
exit /b %ERRORLEVEL%
