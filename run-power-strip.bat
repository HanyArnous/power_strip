@echo off
rem power-strip - one double-click to run the backend (device server + web UI).
rem Usage:  run-power-strip.bat              (auto-detect this PC's IP)
rem         run-power-strip.bat 192.168.1.112  (pin a fixed IP - recommended,
rem                            the strip remembers it, use a DHCP reservation)
title power-strip-server
cd /d "%~dp0"
where python >nul 2>&1
if errorlevel 1 (
  echo [power-strip] ERROR: Python 3.8+ is required - install it from python.org
  pause
  exit /b 1
)
powershell -NoProfile -Command "$c=New-Object Net.Sockets.TcpClient; try { $c.Connect('127.0.0.1', 10086); exit 1 } catch { exit 0 } finally { $c.Close() }" >nul 2>&1
if errorlevel 1 (
  echo [power-strip] Port 10086 is already in use - another server is running.
  echo   Close the other power-strip window, or run stop-power-strip.bat, then try again.
  pause
  exit /b 1
)
echo [power-strip] starting... keep this window open. Close it (or Ctrl+C) to stop.
echo.
if "%~1"=="" (
  python -u power-strip.py serve
) else (
  python -u power-strip.py serve --ip %~1
)
echo.
echo [power-strip] stopped.
pause
