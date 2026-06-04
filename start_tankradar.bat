@echo off
setlocal EnableExtensions
TITLE TankRadar Starter
cd /d "%~dp0"
echo Starting TankRadar...

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
