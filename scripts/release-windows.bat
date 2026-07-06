@echo off
setlocal
powershell -ExecutionPolicy Bypass -File "%~dp0release-windows.ps1" %*
