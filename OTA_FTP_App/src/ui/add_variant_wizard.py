"""Wizard for adding a new PN variant to an existing PN-layout product.

Reuses _ValidationPage / _FirmwarePage / _ScanFilterPage from new_product_wizard.
The first page collects the PN name; the parent product (and its image) come
from the detail-page context, so we don't ask for them again.
"""
from __future__ import annotations
from typing import List, Optional

from PyQt6.QtWidgets import (
    QWizard, QWizardPage, QFormLayout, QVBoxLayout, QLineEdit, QLabel, QMessageBox
)

from ..sftp.product_repo import ProductRepo
from ..domain.firmware_validation import FirmwareValidation
from ..domain.scan_filter import ScanFilterConfig
from .new_product_wizard import _ValidationPage, _FirmwarePage, _ScanFilterPage


class _PnNamePage(QWizardPage):
    def __init__(self, parent_name: str, existing_pns: List[str]):
        super().__init__()
        self.setTitle("Nom de la variante (PN)")
        self.setSubTitle(f"Ajout d'une variante au produit « {parent_name} ».")
        self.existing_pns = set(existing_pns)
        layout = QFormLayout(self)
        self.pn = QLineEdit()
        self.pn.setPlaceholderText("ex. 100005")
        layout.addRow("PN :", self.pn)
        self.error = QLabel("")
        self.error.setStyleSheet("color: #cc6666;")
        layout.addRow("", self.error)
        self.pn.textChanged.connect(self._validate)
        self.registerField("pn_name*", self.pn)

    def _validate(self) -> None:
        v = self.pn.text().strip()
        if v in self.existing_pns:
            self.error.setText(f"Le PN « {v} » existe déjà pour ce produit.")
        elif "/" in v or "\\" in v:
            self.error.setText("Le PN ne peut pas contenir « / » ni « \\ ».")
        else:
            self.error.setText("")
        self.completeChanged.emit()

    def isComplete(self) -> bool:
        v = self.pn.text().strip()
        if not v:
            return False
        if v in self.existing_pns:
            return False
        if "/" in v or "\\" in v:
            return False
        return True


class _VariantReviewPage(QWizardPage):
    def __init__(self, parent_name: str):
        super().__init__()
        self.parent_name = parent_name
        self.setTitle("Revue + envoi")
        self.setSubTitle("Vérifiez puis cliquez Terminer pour pousser sur le serveur.")
        self.summary = QLabel()
        self.summary.setWordWrap(True)
        self.summary.setStyleSheet("font-family: 'Consolas', monospace;")
        layout = QVBoxLayout(self)
        layout.addWidget(self.summary)

    def initializePage(self) -> None:
        import os
        f = self.wizard().field
        sf = ""
        if f("sf_enabled"):
            sf = (
                f"\n[scan_filter]\nname={f('sf_name')}\n"
                f"rssi_min={int(f('sf_rssi_min'))}\nrssi_max={int(f('sf_rssi_max'))}\n"
            )
        def short(path: str) -> str:
            return os.path.basename(path) if path else "(aucune)"
        full_paths = (
            f"Antenne : {f('antenna_path') or ''}\n"
            f"Power   : {f('power_path') or ''}"
        )
        self.summary.setText(
            f"Produit parent : {self.parent_name}\n"
            f"PN             : {f('pn_name')}\n"
            f"Antenne        : {short(f('antenna_path'))}\n"
            f"Power          : {short(f('power_path'))}\n\n"
            f"[validation]\n"
            f"pre_model={f('pre_model')}\n"
            f"post_model={f('post_model')}\n"
            f"antenna_version={f('antenna_version')}\n"
            f"power_version={f('power_version')}\n"
            f"\n[after_antenna]\ncheck=antenna_version\n{sf}"
        )
        self.summary.setToolTip(full_paths)


class AddVariantWizard(QWizard):
    def __init__(self, repo: ProductRepo, parent_name: str, existing_pns: List[str], parent=None):
        super().__init__(parent)
        self.repo = repo
        self.parent_name = parent_name
        self.setWindowTitle(f"Ajouter une variante — {parent_name}")
        self.setMinimumSize(640, 540)
        self.setWizardStyle(QWizard.WizardStyle.ModernStyle)
        self.addPage(_PnNamePage(parent_name, existing_pns))
        self.addPage(_ValidationPage())
        self.addPage(_FirmwarePage())
        self.addPage(_ScanFilterPage())
        self.addPage(_VariantReviewPage(parent_name))

    def accept(self) -> None:
        try:
            f = self.field
            pn_name = f("pn_name").strip()
            sf: Optional[ScanFilterConfig] = None
            if f("sf_enabled"):
                sf = ScanFilterConfig(
                    name=f("sf_name").strip() or None,
                    rssi_min=float(f("sf_rssi_min")),
                    rssi_max=float(f("sf_rssi_max")),
                )
            validation = FirmwareValidation(
                pre_model=f("pre_model").strip(),
                post_model=f("post_model").strip(),
                antenna_version=f("antenna_version").strip(),
                power_version=f("power_version").strip(),
                scan_filter=sf,
            )
            try:
                self.repo.add_variant(
                    name=self.parent_name,
                    pn_name=pn_name,
                    validation=validation,
                    local_antenna_path=f("antenna_path") or None,
                    local_power_path=f("power_path") or None,
                )
                QMessageBox.information(
                    self, "Variante ajoutée",
                    f"PN {pn_name} ajouté à {self.parent_name}."
                )
                super().accept()
            except FileExistsError as e:
                QMessageBox.warning(self, "Conflit", str(e))
            except Exception as e:
                QMessageBox.critical(self, "Erreur", f"Échec : {e}")
        except Exception:
            import logging
            logging.getLogger("Wizard").exception("AddVariantWizard accept crashed")
            raise
