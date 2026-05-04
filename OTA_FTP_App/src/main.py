"""OTA FTP App — entry point."""
from __future__ import annotations
import faulthandler
import logging
import sys
from pathlib import Path

# Dump a C-level stack trace to stderr on SIGSEGV / abort / Windows access violation.
# Survives even when Python's exception machinery can't (e.g. crashes inside Qt).
faulthandler.enable()


def _log_unhandled(exc_type, exc_value, exc_tb):
    """Catch any exception that escapes a Qt slot — PyQt6 sometimes silently
    aborts the process when a Python exception bubbles into the C++ event loop,
    so logging it here is what lets us diagnose that class of crash."""
    import traceback
    msg = "".join(traceback.format_exception(exc_type, exc_value, exc_tb))
    sys.stderr.write("\n=== UNHANDLED EXCEPTION ===\n" + msg + "===========================\n")
    sys.stderr.flush()


sys.excepthook = _log_unhandled

from PyQt6.QtCore import QFile, QTextStream, QLocale, QTranslator, QLibraryInfo
from PyQt6.QtWidgets import QApplication, QMessageBox, QInputDialog

from .secret import SecretLoader, SecretError
from .sftp.client import SftpClient
from .sftp.product_repo import ProductRepo
from .ui.main_window import MainWindow
from .util.image_cache import ImageCache


APP_NAME = "P1900_Production_Manager"
SECRETS_BASENAME = "secrets"  # → secrets.ini / secrets.bin


def _setup_logging() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(asctime)s [%(name)s] %(levelname)s: %(message)s",
    )


def _appdata_dir() -> Path:
    """Where to keep secrets.bin in production. APPDATA on Windows, ~/.config elsewhere."""
    import os
    if sys.platform == "win32":
        base = Path(os.environ.get("APPDATA", Path.home() / "AppData" / "Roaming"))
    else:
        base = Path(os.environ.get("XDG_CONFIG_HOME", Path.home() / ".config"))
    p = base / APP_NAME
    p.mkdir(parents=True, exist_ok=True)
    return p


def _exe_dir() -> Path:
    """Where the EXE / source is located. secrets.ini lives here at first run."""
    if getattr(sys, "frozen", False):
        return Path(sys.executable).parent
    return Path(__file__).resolve().parent.parent


def _load_secrets():
    """Load encrypted secrets, importing from secrets.ini next to the EXE on first run.

    Returns the loaded SecretLoader, or None on failure (caller shows a dialog).
    """
    loader = SecretLoader(
        config_dir=_exe_dir(),       # secrets.ini is dropped here
        bin_dir=_appdata_dir(),       # secrets.bin lives in APPDATA
        filename=SECRETS_BASENAME,
    )
    try:
        loader.load()
    except SecretError as e:
        QMessageBox.critical(None, "Erreur secrets", f"{e}\n\nSupprimez secrets.bin et redéposez secrets.ini.")
        return None
    if not loader.has_secrets:
        QMessageBox.warning(
            None,
            "Secrets manquants",
            f"Aucun secret chargé. Déposez `secrets.ini` à côté de l'application :\n\n{_exe_dir()}",
        )
        return None
    return loader


def _build_repo(loader: SecretLoader) -> ProductRepo:
    host = loader.get("SFTP", "host", "sftp.altyor.solutions")
    port = int(loader.get("SFTP", "port", "22") or 22)
    user = loader.get("SFTP", "username", "")
    key_pem = loader.get("SFTP", "private_key", "")
    passphrase = loader.get("SFTP", "key_passphrase", None)
    root_dir = loader.get("SFTP", "root_dir", "/production") or "/production"
    if not (user and key_pem):
        raise RuntimeError("Section [SFTP] incomplète dans secrets.ini")
    client = SftpClient(host=host, port=port, username=user,
                        private_key_pem=key_pem, passphrase=passphrase)
    client.connect()
    return ProductRepo(client, root=root_dir)


THEMES = ("dark", "win98")


def load_theme(name: str) -> str:
    """Read a theme's QSS by name. Returns '' if the file is missing."""
    qss_path = Path(__file__).parent / "ui" / "themes" / f"{name}.qss"
    if qss_path.exists():
        return qss_path.read_text(encoding="utf-8")
    return ""


def _install_french_translator(app: QApplication) -> None:
    """Translate Qt's built-in dialog buttons (Yes/No/Cancel/Apply…) to French.
    Best-effort — failures are logged and swallowed so they can't crash startup."""
    try:
        QLocale.setDefault(QLocale("fr_FR"))
        qt_path = QLibraryInfo.path(QLibraryInfo.LibraryPath.TranslationsPath)
        loaded = 0
        for base in ("qtbase_fr", "qt_fr"):
            tr = QTranslator(app)
            if tr.load(base, qt_path):
                app.installTranslator(tr)
                loaded += 1
        logging.getLogger("Main").info(f"French translators loaded: {loaded} (from {qt_path})")
    except Exception as e:
        logging.getLogger("Main").warning(f"Failed to install French translator: {e}")


def main() -> int:
    _setup_logging()
    app = QApplication(sys.argv)
    _install_french_translator(app)
    qss = load_theme(THEMES[0])
    if qss:
        app.setStyleSheet(qss)

    loader = _load_secrets()
    if loader is None:
        return 1

    try:
        repo = _build_repo(loader)
    except Exception as e:
        QMessageBox.critical(None, "Connexion impossible", str(e))
        return 1

    image_cache = ImageCache(_appdata_dir() / "image_cache")
    window = MainWindow(repo, image_cache)
    window.show()
    return app.exec()


if __name__ == "__main__":
    sys.exit(main())
