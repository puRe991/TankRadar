@echo off
setlocal EnableExtensions
TITLE TankRadar Installation
cd /d "%~dp0"

echo ============================================================
echo TankRadar - automatische Installation
echo ============================================================
echo.

call :find_python
if defined PYTHON_EXE goto :python_ready

echo Python wurde nicht gefunden. Python 3.12 wird jetzt installiert...
where winget >nul 2>&1
if errorlevel 1 goto :install_python_fallback

winget install --exact --id Python.Python.3.12 --scope user --accept-package-agreements --accept-source-agreements
if errorlevel 1 (
    echo FEHLER: Python konnte mit winget nicht installiert werden.
    goto :failed
)
goto :find_python_after_install

:install_python_fallback
echo winget wurde nicht gefunden. Der offizielle Python-Installer wird heruntergeladen...
set "PYTHON_INSTALLER=%TEMP%\python-3.12.10-amd64.exe"
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Invoke-WebRequest -Uri 'https://www.python.org/ftp/python/3.12.10/python-3.12.10-amd64.exe' -OutFile $env:PYTHON_INSTALLER"
if errorlevel 1 (
    echo FEHLER: Der Python-Installer konnte nicht heruntergeladen werden.
    goto :failed
)

"%PYTHON_INSTALLER%" /quiet InstallAllUsers=0 PrependPath=1 Include_launcher=1 Include_test=0
if errorlevel 1 (
    echo FEHLER: Python konnte nicht installiert werden.
    goto :failed
)
del /q "%PYTHON_INSTALLER%" >nul 2>&1

:find_python_after_install
call :find_python
if not defined PYTHON_EXE (
    echo FEHLER: Python wurde installiert, konnte aber nicht gestartet werden.
    echo Bitte Windows neu starten und diese Datei erneut ausfuehren.
    goto :failed
)

:python_ready
echo Gefundene Python-Installation:
"%PYTHON_EXE%" %PYTHON_ARGS% --version
if errorlevel 1 goto :failed
echo.

echo Virtuelle Python-Umgebung wird eingerichtet...
if not exist ".venv\Scripts\python.exe" (
    "%PYTHON_EXE%" %PYTHON_ARGS% -m venv .venv
    if errorlevel 1 (
        echo FEHLER: Die virtuelle Umgebung konnte nicht erstellt werden.
        goto :failed
    )
) else (
    echo Vorhandene virtuelle Umgebung wird verwendet.
)

echo pip wird aktualisiert...
".venv\Scripts\python.exe" -m pip install --upgrade pip
if errorlevel 1 goto :dependency_error

echo Erforderliche Python-Pakete werden heruntergeladen und installiert...
".venv\Scripts\python.exe" -m pip install -r requirements.txt
if errorlevel 1 goto :dependency_error

echo.
echo ============================================================
echo Installation erfolgreich abgeschlossen.
echo TankRadar kann jetzt mit start_tankradar.bat gestartet werden.
echo ============================================================
pause
exit /b 0

:dependency_error
echo.
echo FEHLER: Nicht alle Python-Pakete konnten installiert werden.
echo Pruefen Sie Ihre Internetverbindung und starten Sie die Installation erneut.
goto :failed

:failed
echo.
echo Installation abgebrochen.
pause
exit /b 1

:find_python
set "PYTHON_EXE="
set "PYTHON_ARGS="
py -3.12 -c "import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)" >nul 2>&1
if not errorlevel 1 (
    set "PYTHON_EXE=py"
    set "PYTHON_ARGS=-3.12"
    exit /b 0
)
python -c "import sys; raise SystemExit(0 if sys.version_info >= (3, 10) else 1)" >nul 2>&1
if not errorlevel 1 (
    set "PYTHON_EXE=python"
    exit /b 0
)
if exist "%LocalAppData%\Programs\Python\Python312\python.exe" (
    set "PYTHON_EXE=%LocalAppData%\Programs\Python\Python312\python.exe"
    exit /b 0
)
exit /b 0
