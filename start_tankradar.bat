@echo off
TITLE TankRadar Starter
echo Starting TankRadar...

:: Check if Python is installed
python --version >nul 2>&1
if %errorlevel% neq 0 (
    echo Error: Python is not installed or not in PATH.
    pause
    exit /b
)

:: Run the application
echo Launching dashboard...
python main.py

pause
