@echo off
rem install-service - register power-strip as an auto-start background task
rem (a Windows-service equivalent, no extra software needed).
rem Double-click, approve the admin prompt, done.
rem Optional fixed IP:  install-service.bat 192.168.1.112
net session >nul 2>&1
if errorlevel 1 (
  echo [power-strip] requesting admin rights...
  powershell -NoProfile -Command "Start-Process '%~f0' -ArgumentList '%*' -Verb RunAs"
  exit /b
)
set "IP=%~1"
if "%IP%"=="" set "IP=192.168.1.112"
set "XML=%TEMP%\power-strip-task.xml"
powershell -NoProfile -Command "(Get-Content '%~dp0power-strip-task.xml' -Raw).Replace('__DIR__','%~dp0').Replace('__IP__','%IP%') | Set-Content '%XML%' -Encoding Unicode"
if errorlevel 1 (
  echo [power-strip] ERROR: could not prepare the task file.
  pause
  exit /b 1
)
schtasks /create /tn "power-strip" /f /xml "%XML%"
del "%XML%" >nul 2>&1
if errorlevel 1 (
  echo [power-strip] ERROR: task registration failed.
  pause
  exit /b 1
)
schtasks /run /tn "power-strip" >nul 2>&1
echo.
echo [power-strip] installed: starts at every boot, restarts if it crashes,
echo          log: power-strip-service.log, web UI: http://%IP%:8080
echo [power-strip] to remove later: uninstall-service.bat
pause
