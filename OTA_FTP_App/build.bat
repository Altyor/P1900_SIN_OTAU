@echo off
setlocal

REM Build OTA FTP App as a single-file Windows EXE.
REM Mirrors the Relay_Toggler build pattern.

cd /d "%~dp0"

if not exist .venv (
    echo Creating virtual env...
    python -m venv .venv
)

call .venv\Scripts\activate.bat

echo Installing requirements...
pip install -r requirements.txt
pip install pyinstaller

echo Building...
pyinstaller ^
    --onefile ^
    --windowed ^
    --name "OTA_FTP_App" ^
    --add-data "src/ui/styles.qss;ui" ^
    --hidden-import "win32crypt" ^
    src\main.py

echo.
echo Build complete. EXE at: dist\OTA_FTP_App.exe
endlocal
