@echo off
rem Double-click to start PanGaze on Windows. Keep this window open while you watch.
setlocal
cd /d "%~dp0"
title PanGaze

rem Already running in the background? Just open it.
curl -s -m 2 http://localhost:8899/__xp_ping 2>nul | find "xp-proxy" >nul && (
  echo PanGaze is already running. Opening it in your browser...
  start "" http://localhost:8899
  exit /b 0
)

rem Find Python 3 (the py launcher from python.org first, then python)
set "PY="
py -3 -c "import sys" >nul 2>&1 && set "PY=py -3"
if not defined PY python -c "import sys" >nul 2>&1 && set "PY=python"
if not defined PY (
  echo.
  echo   PanGaze needs Python 3, which is free.
  echo   Opening the download page now. Run the installer and tick
  echo   "Add python.exe to PATH", then double-click this file again.
  echo.
  start "" https://www.python.org/downloads/windows/
  pause
  exit /b 1
)

echo.
echo   Starting PanGaze... If Windows asks whether Python may use the network,
echo   allow it on Private networks so your phone and TV can reach PanGaze.
echo.
%PY% server.py --lan %*
pause
