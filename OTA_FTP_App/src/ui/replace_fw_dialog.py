"""Replace firmware (Antenna or Power) dialog."""
from __future__ import annotations
import os
from typing import List, Optional

from PyQt6.QtWidgets import (
    QDialog, QVBoxLayout, QHBoxLayout, QLabel, QLineEdit, QPushButton,
    QFileDialog, QMessageBox
)

from ..sftp.product_repo import ProductRepo, FirmwareFile
from typing import Optional  # noqa: F811 — already imported above


class ReplaceFirmwareDialog(QDialog):
    def __init__(self, repo: ProductRepo, name: str, slot: str, current: List[FirmwareFile],
                 pn_name: Optional[str] = None, parent=None):
        super().__init__(parent)
        self.repo = repo
        self.name = name
        self.pn_name = pn_name
        self.slot = slot  # 'Antenna' | 'Power'
        self.setWindowTitle(f"Remplacer firmware — {slot}")
        self.setMinimumWidth(560)

        outer = QVBoxLayout(self)
        outer.addWidget(QLabel(f"Produit : <b>{name}</b>"))
        outer.addWidget(QLabel(f"Emplacement : <b>FW/{slot}/</b>"))

        outer.addWidget(QLabel("Firmware actuel :"))
        if current:
            for f in current:
                outer.addWidget(QLabel(f"  • {f.name}  ({f.size:,} octets)"))
        else:
            outer.addWidget(QLabel("  (aucun)"))

        outer.addSpacing(8)
        outer.addWidget(QLabel("Nouveau firmware :"))
        row = QHBoxLayout()
        self.path_edit = QLineEdit()
        self.path_edit.setReadOnly(True)
        row.addWidget(self.path_edit, stretch=1)
        browse = QPushButton("Parcourir…")
        browse.clicked.connect(self._on_browse)
        row.addWidget(browse)
        outer.addLayout(row)

        outer.addSpacing(12)
        btn_row = QHBoxLayout()
        btn_row.addStretch(1)
        cancel = QPushButton("Annuler")
        cancel.setProperty("role", "ghost")
        cancel.clicked.connect(self.reject)
        btn_row.addWidget(cancel)
        self.apply_btn = QPushButton("Remplacer")
        self.apply_btn.setEnabled(False)
        self.apply_btn.clicked.connect(self._on_apply)
        btn_row.addWidget(self.apply_btn)
        outer.addLayout(btn_row)

    def _on_browse(self) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "Choisir le firmware", "", "Firmware (*.gbl *.zigbee)")
        if path:
            self.path_edit.setText(path)
            self.apply_btn.setEnabled(True)

    def _on_apply(self) -> None:
        local = self.path_edit.text().strip()
        if not local or not os.path.exists(local):
            QMessageBox.warning(self, "Erreur", "Fichier introuvable.")
            return
        try:
            self.repo.replace_firmware(self.name, self.slot, local, pn_name=self.pn_name)
            QMessageBox.information(self, "Remplacé", f"{os.path.basename(local)} envoyé.")
            self.accept()
        except Exception as e:
            QMessageBox.critical(self, "Erreur", f"Échec : {e}")
