@echo off
setlocal
title Kitaab - setup
cd /d "%~dp0"
echo.
echo  Kitaab setup wizard. Keep this window open.
echo.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0setup\Setup-Kitaab.ps1" -Mode menu
echo.
pause
