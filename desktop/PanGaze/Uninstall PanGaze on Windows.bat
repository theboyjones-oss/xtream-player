@echo off
rem Double-click to stop PanGaze running in the background and remove its icons.
rem Your data stays in %LOCALAPPDATA%\PanGaze in case you reinstall.
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0windows\uninstall.ps1"
echo.
pause
