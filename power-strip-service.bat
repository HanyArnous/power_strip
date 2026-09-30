@echo off
rem power-strip-service - background workhorse, launched by the "power-strip" scheduled
rem task at boot. Do NOT double-click this (use run-power-strip.bat for a visible
rem window). Logs to power-strip-service.log (ignored by git).
cd /d "%~dp0"
if "%~1"=="" (
  pythonw -u power-strip.py serve >> power-strip-service.log 2>&1
) else (
  pythonw -u power-strip.py serve --ip %~1 >> power-strip-service.log 2>&1
)
