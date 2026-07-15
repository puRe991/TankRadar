@echo off
setlocal EnableExtensions
TITLE TankRadar Installation
cd /d "%~dp0"

set "PYTHON_EXE="
set "PYTHON_ARGS="
set "REQUIREMENTS_FILE=requirements.txt"
set "PYTHON_INSTALLER="
set "PYTHON_DOWNLOAD_URL="
set "WINGET_ARCH="
set "PYTHON_INSTALL_LABEL="

call :detect_system_architecture

echo ============================================================
echo TankRadar - automatische Installation
echo ============================================================
echo.

call :find_python
if defined PYTHON_EXE goto :python_ready

echo Keine kompatible Python-Installation gefunden.
echo %PYTHON_INSTALL_LABEL% wird jetzt installiert...
where winget >nul 2>&1
if errorlevel 1 goto :install_python_fallback

winget install --exact --id Python.Python.%PYTHON_WINGET_VERSION% --architecture %WINGET_ARCH% --scope user --accept-package-agreements --accept-source-agreements
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
    echo FEHLER: %PYTHON_INSTALL_LABEL% wurde installiert, konnte aber nicht gestartet werden.
    echo Bitte Windows neu starten und diese Datei erneut ausfuehren.
    goto :failed
)

:python_ready
echo Gefundene Python-Installation:
"%PYTHON_EXE%" %PYTHON_ARGS% --version
if errorlevel 1 goto :failed
"%PYTHON_EXE%" %PYTHON_ARGS% -c "import platform; print('Architektur: ' + platform.architecture()[0])"
if errorlevel 1 goto :failed
echo Verwendete Abhaengigkeiten: %REQUIREMENTS_FILE%
echo.

if exist ".venv\Scripts\python.exe" (
    call :check_venv_compatibility
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
".venv\Scripts\python.exe" -m pip install --only-binary=:all: --no-binary=proxy_tools --prefer-binary -r "%REQUIREMENTS_FILE%"
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
echo Diese Installation nutzt absichtlich nur fertige Pakete, damit pandas, numpy und aehnliche Pakete nicht lokal kompiliert werden muessen.
echo Pruefen Sie Ihre Internetverbindung. Falls der Fehler weiterhin auftritt, loeschen Sie den Ordner .venv und starten Sie diese Datei erneut.
goto :failed

:failed
echo.
echo Installation abgebrochen.
pause
exit /b 1

:detect_system_architecture
set "SYSTEM_BITS=32"
if /i "%PROCESSOR_ARCHITECTURE%"=="AMD64" set "SYSTEM_BITS=64"
if /i "%PROCESSOR_ARCHITECTURE%"=="ARM64" set "SYSTEM_BITS=64"
if defined PROCESSOR_ARCHITEW6432 set "SYSTEM_BITS=64"
if "%SYSTEM_BITS%"=="64" (
    set "REQUIREMENTS_FILE=requirements.txt"
    set "PYTHON_WINGET_VERSION=3.12"
    set "PYTHON_INSTALLER=%TEMP%\python-3.12.10-amd64.exe"
    set "PYTHON_DOWNLOAD_URL=https://www.python.org/ftp/python/3.12.10/python-3.12.10-amd64.exe"
    set "WINGET_ARCH=x64"
    set "PYTHON_INSTALL_LABEL=Python 3.12 64-Bit"
) else (
    set "REQUIREMENTS_FILE=requirements-32bit.txt"
    set "PYTHON_WINGET_VERSION=3.11"
    set "PYTHON_INSTALLER=%TEMP%\python-3.11.9.exe"
    set "PYTHON_DOWNLOAD_URL=https://www.python.org/ftp/python/3.11.9/python-3.11.9.exe"
    set "WINGET_ARCH=x86"
    set "PYTHON_INSTALL_LABEL=Python 3.11 32-Bit"
)
exit /b 0

:find_python
set "PYTHON_EXE="
set "PYTHON_ARGS="
if "%SYSTEM_BITS%"=="64" goto :find_python_64

echo 32-Bit-Windows erkannt: verwende das 32-Bit-kompatible Abhaengigkeitsprofil.
py -3.11-32 -c "import struct, sys; raise SystemExit(0 if (3, 10) <= sys.version_info[:2] < (3, 12) and struct.calcsize('P') * 8 == 32 else 1)" >nul 2>&1
if not errorlevel 1 (
    set "PYTHON_EXE=py"
    set "PYTHON_ARGS=-3.11-32"
    exit /b 0
)
py -3.10-32 -c "import struct, sys; raise SystemExit(0 if (3, 10) <= sys.version_info[:2] < (3, 12) and struct.calcsize('P') * 8 == 32 else 1)" >nul 2>&1
if not errorlevel 1 (
    set "PYTHON_EXE=py"
    set "PYTHON_ARGS=-3.10-32"
    exit /b 0
)
python -c "import struct, sys; raise SystemExit(0 if (3, 10) <= sys.version_info[:2] < (3, 12) and struct.calcsize('P') * 8 == 32 else 1)" >nul 2>&1
if not errorlevel 1 (
    set "PYTHON_EXE=python"
    exit /b 0
)
if exist "%LocalAppData%\Programs\Python\Python311-32\python.exe" (
    "%LocalAppData%\Programs\Python\Python311-32\python.exe" -c "import struct, sys; raise SystemExit(0 if (3, 10) <= sys.version_info[:2] < (3, 12) and struct.calcsize('P') * 8 == 32 else 1)" >nul 2>&1
    if not errorlevel 1 (
        set "PYTHON_EXE=%LocalAppData%\Programs\Python\Python311-32\python.exe"
        exit /b 0
    )
)
if exist "%LocalAppData%\Programs\Python\Python310-32\python.exe" (
    "%LocalAppData%\Programs\Python\Python310-32\python.exe" -c "import struct, sys; raise SystemExit(0 if (3, 10) <= sys.version_info[:2] < (3, 12) and struct.calcsize('P') * 8 == 32 else 1)" >nul 2>&1
    if not errorlevel 1 (
        set "PYTHON_EXE=%LocalAppData%\Programs\Python\Python310-32\python.exe"
        exit /b 0
    )
)
exit /b 0

:find_python_64
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

:check_venv_compatibility
if "%SYSTEM_BITS%"=="64" (
    ".venv\Scripts\python.exe" -c "import struct, sys; raise SystemExit(0 if sys.version_info >= (3, 10) and struct.calcsize('P') * 8 == 64 else 1)" >nul 2>&1
    exit /b %errorlevel%
)
".venv\Scripts\python.exe" -c "import struct, sys; raise SystemExit(0 if (3, 10) <= sys.version_info[:2] < (3, 12) and struct.calcsize('P') * 8 == 32 else 1)" >nul 2>&1
exit /b %errorlevel%
