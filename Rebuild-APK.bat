@echo off
setlocal
title Kitaab - rebuild APK
cd /d "%~dp0"
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup\Setup-Kitaab.ps1" -Mode rebuild
echo.
pause
