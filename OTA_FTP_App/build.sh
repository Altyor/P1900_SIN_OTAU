#!/usr/bin/env bash
# Build OTA FTP App on macOS / Linux. Sibling of build.bat.
# - macOS: --windowed produces a .app bundle in dist/
# - Linux: --windowed produces a regular ELF binary in dist/

set -euo pipefail
cd "$(dirname "$0")"

if [ ! -d .venv ]; then
    echo "Creating virtual env..."
    python3 -m venv .venv
fi

# shellcheck disable=SC1091
source .venv/bin/activate

echo "Installing requirements..."
pip install --upgrade pip > /dev/null
pip install -r requirements.txt
pip install pyinstaller

echo "Building..."
# --add-data uses ':' as the separator on macOS / Linux (':' on Unix, ';' on Windows)
pyinstaller \
    --onefile \
    --windowed \
    --name "OTA_FTP_App" \
    --add-data "src/ui/themes:ui/themes" \
    src/main.py

echo
case "$(uname -s)" in
    Darwin*) echo "Build complete. .app bundle at: dist/OTA_FTP_App.app" ;;
    Linux*)  echo "Build complete. Binary at: dist/OTA_FTP_App" ;;
    *)       echo "Build complete (output in dist/)" ;;
esac
