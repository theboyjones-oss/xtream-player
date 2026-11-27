@echo off
rem Double-click to make PanGaze run quietly in the background on Windows.
rem It starts by itself when you sign in to Windows, with no window, and adds a
rem PanGaze icon to your desktop and Start menu. Run it again after an update.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0windows\install.ps1"
echo.
pause
