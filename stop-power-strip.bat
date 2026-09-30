@echo off
rem power-strip - stop a backend started with run-power-strip.bat (or any power-strip.py).
title stopping-power-strip
taskkill /f /fi "WINDOWTITLE eq power-strip-server*" >nul 2>&1
echo [power-strip] stop signal sent to the server window (if it was open).
echo [power-strip] leftover background servers, if any:
powershell -NoProfile -Command "Get-Process python -ErrorAction SilentlyContinue | Select-Object Id, StartTime"
pause
