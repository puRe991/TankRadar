@echo off
setlocal EnableExtensions
TITLE TankRadar Installation
cd /d "%~dp0"

set "PYTHON_INSTALLER=%TEMP%\python-3.12.10-amd64.exe"
set "PYTHON_DOWNLOAD_URL=https://www.python.org/ftp/python/3.12.10/python-3.12.10-amd64.exe"

echo ============================================================
echo TankRadar - automatische Installation
echo ============================================================
echo.

call :find_python
if defined PYTHON_EXE goto :python_ready

echo Keine kompatible 64-Bit-Python-Installation gefunden.
echo Python 3.12 64-Bit wird jetzt installiert...
where winget >nul 2>&1
if errorlevel 1 goto :install_python_fallback

winget install --exact --id Python.Python.3.12 --architecture x64 --scope user --accept-package-agreements --accept-source-agreements
if errorlevel 1 (
    echo FEHLER: Python konnte mit winget nicht installiert werden.
    goto :failed
)
goto :find_python_after_install

:install_python_fallback
echo winget wurde nicht gefunden. Der offizielle Python-Installer wird heruntergeladen...
powershell.exe -NoProfile -ExecutionPolicy Bypass -Command "$ErrorActionPreference='Stop'; Invoke-WebRequest -Uri $env:PYTHON_DOWNLOAD_URL -OutFile $env:PYTHON_INSTALLER"
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
    echo FEHLER: Python 64-Bit wurde installiert, konnte aber nicht gestartet werden.
    echo Bitte Windows neu starten und diese Datei erneut ausfuehren.
    goto :failed
)

:python_ready
echo Gefundene Python-Installation:
"%PYTHON_EXE%" %PYTHON_ARGS% --version
if errorlevel 1 goto :failed
"%PYTHON_EXE%" %PYTHON_ARGS% -c "import platform; print('Architektur: ' + platform.architecture()[0])"
if errorlevel 1 goto :failed
echo.

if exist ".venv\Scripts\python.exe" (
    ".venv\Scripts\python.exe" -c "import struct, sys; raise SystemExit(0 if struct.calcsize('P') * 8 == 64 and sys.version_info >= (3, 10) else 1)" >nul 2>&1
    if errorlevel 1 (
        echo Vorhandene virtuelle Umgebung ist nicht kompatibel und wird neu erstellt...
        rmdir /s /q ".venv"
        if exist ".venv\Scripts\python.exe" (
            echo FEHLER: Die alte virtuelle Umgebung konnte nicht entfernt werden.
            echo Bitte schliessen Sie laufende TankRadar- oder Python-Prozesse und starten Sie die Installation erneut.
            goto :failed
        )
    ) else (
        echo Vorhandene kompatible virtuelle Umgebung wird verwendet.
    )
)

echo Virtuelle Python-Umgebung wird eingerichtet...
if not exist ".venv\Scripts\python.exe" (
    "%PYTHON_EXE%" %PYTHON_ARGS% -m venv .venv
    if errorlevel 1 (
        echo FEHLER: Die virtuelle Umgebung konnte nicht erstellt werden.
        goto :failed
    )
)

echo pip wird aktualisiert...
".venv\Scripts\python.exe" -m pip install --upgrade pip
if errorlevel 1 goto :dependency_error

echo Erforderliche Python-Pakete werden heruntergeladen und installiert...
".venv\Scripts\python.exe" -m pip install --only-binary=:all: --prefer-binary -r requirements.txt
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
echo Diese Installation nutzt absichtlich nur fertige 64-Bit-Pakete, damit pandas, numpy und prophet nicht lokal kompiliert werden muessen.
echo Pruefen Sie Ihre Internetverbindung. Falls der Fehler weiterhin auftritt, loeschen Sie den Ordner .venv und starten Sie diese Datei erneut.
goto :failed

:failed
echo.
echo Installation abgebrochen.
pause
exit /b 1

:find_python
set "PYTHON_EXE="
set "PYTHON_ARGS="
py -3.12-64 -c "import struct, sys; raise SystemExit(0 if sys.version_info >= (3, 10) and struct.calcsize('P') * 8 == 64 else 1)" >nul 2>&1
if not errorlevel 1 (
    set "PYTHON_EXE=py"
    set "PYTHON_ARGS=-3.12-64"
    exit /b 0
)
py -3.12 -c "import struct, sys; raise SystemExit(0 if sys.version_info >= (3, 10) and struct.calcsize('P') * 8 == 64 else 1)" >nul 2>&1
if not errorlevel 1 (
    set "PYTHON_EXE=py"
    set "PYTHON_ARGS=-3.12"
    exit /b 0
)
python -c "import struct, sys; raise SystemExit(0 if sys.version_info >= (3, 10) and struct.calcsize('P') * 8 == 64 else 1)" >nul 2>&1
if not errorlevel 1 (
    set "PYTHON_EXE=python"
    exit /b 0
)
if exist "%LocalAppData%\Programs\Python\Python312\python.exe" (
    "%LocalAppData%\Programs\Python\Python312\python.exe" -c "import struct, sys; raise SystemExit(0 if sys.version_info >= (3, 10) and struct.calcsize('P') * 8 == 64 else 1)" >nul 2>&1
    if not errorlevel 1 (
        set "PYTHON_EXE=%LocalAppData%\Programs\Python\Python312\python.exe"
        exit /b 0
    )
)
exit /b 0
