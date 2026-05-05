"""About dialog — version, paths, SFTP target, keyboard shortcuts."""
from __future__ import annotations
from pathlib import Path

from PyQt6.QtCore import Qt
from PyQt6.QtWidgets import QDialog, QVBoxLayout, QLabel, QPushButton, QFormLayout, QFrame


APP_NAME = "OTA FTP App"
APP_VERSION = "0.1.0"


SHORTCUTS = (
    ("Ctrl+T", "Changer de thème"),
    ("Échap",  "Effacer le filtre / fermer une boîte de dialogue"),
)


class AboutDialog(QDialog):
    def __init__(self, secrets_path: Path, image_cache_path: Path,
                  settings_path: Path, sftp_summary: str, parent=None):
        super().__init__(parent)
        self.setWindowTitle("À propos")
        self.setMinimumWidth(560)

        outer = QVBoxLayout(self)

        title = QLabel(f"<h2>{APP_NAME}</h2><p>Version {APP_VERSION}</p>")
        outer.addWidget(title)

        SELECTABLE = Qt.TextInteractionFlag.TextSelectableByMouse

        # Connection
        conn_label = QLabel("<b>Serveur SFTP</b>")
        outer.addWidget(conn_label)
        conn = QLabel(sftp_summary)
        conn.setStyleSheet("font-family: 'Consolas', monospace;")
        conn.setTextInteractionFlags(SELECTABLE)
        outer.addWidget(conn)

        outer.addWidget(_separator())

        # Paths
        paths_label = QLabel("<b>Emplacements locaux</b>")
        outer.addWidget(paths_label)
        paths_form = QFormLayout()
        paths_form.setLabelAlignment(Qt.AlignmentFlag.AlignLeft)
        for label, p in (
            ("Secrets chiffrés :", secrets_path),
            ("Cache des images :", image_cache_path),
            ("Préférences :",      settings_path),
        ):
            v = QLabel(str(p))
            v.setStyleSheet("font-family: 'Consolas', monospace;")
            v.setTextInteractionFlags(SELECTABLE)
            v.setWordWrap(True)
            paths_form.addRow(label, v)
        outer.addLayout(paths_form)

        outer.addWidget(_separator())

        # Shortcuts
        sc_label = QLabel("<b>Raccourcis clavier</b>")
        outer.addWidget(sc_label)
        sc_form = QFormLayout()
        for keys, desc in SHORTCUTS:
            sc_form.addRow(QLabel(f"<code>{keys}</code>"), QLabel(desc))
        outer.addLayout(sc_form)

        outer.addStretch(1)

        close = QPushButton("Fermer")
        close.clicked.connect(self.accept)
        outer.addWidget(close)


def _separator() -> QFrame:
    line = QFrame()
    line.setFrameShape(QFrame.Shape.HLine)
    line.setFrameShadow(QFrame.Shadow.Sunken)
    return line
