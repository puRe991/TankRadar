@echo off
setlocal EnableExtensions
TITLE TankRadar Starter
cd /d "%~dp0"
echo Starting TankRadar...

:: Update the local checkout before starting the application.
:: A fast-forward-only pull avoids unexpected merge commits or overwriting local work.
call :update_from_git

:: Prefer the environment created by install_tankradar.bat
if exist ".venv\Scripts\python.exe" (
    set "PYTHON_EXE=.venv\Scripts\python.exe"
) else (
    set "PYTHON_EXE=python"
)

:: Check if Python is installed
"%PYTHON_EXE%" --version >nul 2>&1
if errorlevel 1 (
    echo Error: Python and the TankRadar dependencies are not installed.
    echo Please run install_tankradar.bat first.
    pause
    exit /b 1
)

:: Run the application
echo Launching dashboard...
"%PYTHON_EXE%" main.py

pause
exit /b 0

:update_from_git
where git >nul 2>&1
if errorlevel 1 (
    echo [WARN] Git wurde nicht gefunden. Starte ohne automatisches Update.
    exit /b 0
)

git rev-parse --is-inside-work-tree >nul 2>&1
if errorlevel 1 (
    echo [WARN] Dieses Verzeichnis ist kein Git-Checkout. Starte ohne automatisches Update.
    exit /b 0
)

echo Aktualisiere TankRadar aus Git...
git pull --ff-only
if errorlevel 1 (
    echo [WARN] Git-Update fehlgeschlagen. Lokale Version wird gestartet.
    echo [WARN] Bitte pruefen Sie Netzwerk, lokale Aenderungen und Branch-Konfiguration.
    exit /b 0
)

echo [OK] TankRadar ist auf dem neuesten Git-Stand.
exit /b 0
