@echo off
rem uninstall-service - remove the power-strip auto-start task.
net session >nul 2>&1
if errorlevel 1 (
  echo [power-strip] requesting admin rights...
  powershell -NoProfile -Command "Start-Process '%~f0' -Verb RunAs"
  exit /b
)
schtasks /end /tn "power-strip" >nul 2>&1
schtasks /delete /tn "power-strip" /f
taskkill /f /fi "WINDOWTITLE eq power-strip-server*" >nul 2>&1
echo [power-strip] auto-start task removed. Any visible server window was closed too.
pause
