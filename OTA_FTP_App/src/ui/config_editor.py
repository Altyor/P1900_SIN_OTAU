"""Form-based editor for [validation] + [scan_filter]. Pushes minimal-edit changes."""
from __future__ import annotations
from typing import Optional

from typing import Dict, Optional, Set

from PyQt6.QtCore import Qt
from PyQt6.QtWidgets import (
    QDialog, QVBoxLayout, QHBoxLayout, QFormLayout, QLineEdit, QPushButton,
    QGroupBox, QCheckBox, QSpinBox, QLabel, QPlainTextEdit, QMessageBox,
    QGridLayout
)

from ..sftp.product_repo import ProductRepo
from ..domain.firmware_validation import FirmwareValidation, ALL_FIELDS
from ..domain.scan_filter import ScanFilterConfig
from ..domain.config_ini import update_field, append_section, remove_section


# Scenario sections + their config-key names (must match the Kotlin parser)
_SCENARIOS = (
    ("after_antenna", "Antenne seule"),
    ("after_power",   "Power seul"),
    ("after_both",    "Les deux"),
)
_FIELDS = ("post_model", "antenna_version", "power_version")


class ConfigEditorDialog(QDialog):
    def __init__(self, repo: ProductRepo, name: str, current: FirmwareValidation,
                 current_text: str, pn_name: Optional[str] = None, parent=None):
        super().__init__(parent)
        self.repo = repo
        self.name = name
        self.pn_name = pn_name
        self.current_text = current_text
        self.current = current
        self.setWindowTitle(f"Modifier — {name}")
        self.setMinimumSize(700, 600)

        outer = QVBoxLayout(self)
        outer.setSpacing(10)

        # Validation
        val_box = QGroupBox("Validation")
        vform = QFormLayout(val_box)
        self.pre_model = QLineEdit(current.pre_model)
        self.post_model = QLineEdit(current.post_model)
        self.antenna_version = QLineEdit(current.antenna_version)
        self.power_version = QLineEdit(current.power_version)
        vform.addRow("pre_model :", self.pre_model)
        vform.addRow("post_model :", self.post_model)
        vform.addRow("antenna_version :", self.antenna_version)
        vform.addRow("power_version :", self.power_version)
        outer.addWidget(val_box)

        # Post-OTA checks — a 3x3 checkbox grid
        checks_box = QGroupBox("Vérifications post-OTA")
        checks_outer = QVBoxLayout(checks_box)
        hint = QLabel("Cochez les champs à vérifier après chaque type d'OTA.\n"
                      "Toutes cochées = comportement par défaut (la section est absente du fichier).")
        hint.setProperty("role", "caption")
        hint.setWordWrap(True)
        checks_outer.addWidget(hint)

        grid = QGridLayout()
        grid.setHorizontalSpacing(20)
        # Header row
        grid.addWidget(QLabel(""), 0, 0)
        for col, field in enumerate(_FIELDS, start=1):
            lbl = QLabel(field)
            lbl.setStyleSheet("font-weight: 600;")
            lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
            grid.addWidget(lbl, 0, col)
        # Data rows
        self.check_boxes: Dict[str, Dict[str, QCheckBox]] = {}
        # Capture the initial state so we can detect "no change" on apply.
        self._initial_checks: Dict[str, Optional[Set[str]]] = {}
        scenario_initial = {
            "after_antenna": current.after_antenna,
            "after_power":   current.after_power,
            "after_both":    current.after_both,
        }
        for row, (sect, label) in enumerate(_SCENARIOS, start=1):
            grid.addWidget(QLabel(label), row, 0)
            initial = scenario_initial[sect]
            self._initial_checks[sect] = initial
            # If section is absent → display all checked (default behaviour)
            display_set = initial if initial is not None else set(ALL_FIELDS)
            self.check_boxes[sect] = {}
            for col, field in enumerate(_FIELDS, start=1):
                cb = QCheckBox()
                cb.setChecked(field in display_set)
                grid.addWidget(cb, row, col, alignment=Qt.AlignmentFlag.AlignCenter)
                self.check_boxes[sect][field] = cb
        checks_outer.addLayout(grid)
        outer.addWidget(checks_box)

        # Scan filter
        sf_box = QGroupBox("Filtre de scan BLE")
        sf_outer = QVBoxLayout(sf_box)
        self.sf_enable = QCheckBox("Activer un filtre de scan personnalisé pour ce produit")
        self.sf_enable.setChecked(current.scan_filter is not None)
        self.sf_enable.toggled.connect(self._toggle_sf_enabled)
        sf_outer.addWidget(self.sf_enable)

        self.sf_form = QFormLayout()
        self.sf_name = QLineEdit(current.scan_filter.name if current.scan_filter else "")
        self.sf_rssi_min = QSpinBox()
        self.sf_rssi_min.setRange(-100, 0)
        self.sf_rssi_min.setValue(int(current.scan_filter.rssi_min) if current.scan_filter else -40)
        self.sf_rssi_max = QSpinBox()
        self.sf_rssi_max.setRange(-100, 0)
        self.sf_rssi_max.setValue(int(current.scan_filter.rssi_max) if current.scan_filter else 0)
        self.sf_only_connectable = QCheckBox()
        self.sf_only_bonded = QCheckBox()
        self.sf_only_favourite = QCheckBox()
        if current.scan_filter:
            self.sf_only_connectable.setChecked(current.scan_filter.only_connectable)
            self.sf_only_bonded.setChecked(current.scan_filter.only_bonded)
            self.sf_only_favourite.setChecked(current.scan_filter.only_favourite)
        self.sf_form.addRow("name :", self.sf_name)
        self.sf_form.addRow("rssi_min :", self.sf_rssi_min)
        self.sf_form.addRow("rssi_max :", self.sf_rssi_max)
        self.sf_form.addRow("only_connectable :", self.sf_only_connectable)
        self.sf_form.addRow("only_bonded :", self.sf_only_bonded)
        self.sf_form.addRow("only_favourite :", self.sf_only_favourite)
        sf_outer.addLayout(self.sf_form)
        self._toggle_sf_enabled(self.sf_enable.isChecked())
        outer.addWidget(sf_box)

        # Diff preview
        outer.addWidget(QLabel("Aperçu du fichier après modification :"))
        self.preview = QPlainTextEdit()
        self.preview.setReadOnly(True)
        self.preview.setStyleSheet("font-family: 'Consolas', monospace;")
        outer.addWidget(self.preview, stretch=1)

        # Buttons
        btn_row = QHBoxLayout()
        btn_row.addStretch(1)
        cancel = QPushButton("Annuler")
        cancel.setProperty("role", "ghost")
        cancel.clicked.connect(self.reject)
        btn_row.addWidget(cancel)
        preview_btn = QPushButton("Aperçu")
        preview_btn.setProperty("role", "ghost")
        preview_btn.clicked.connect(self._update_preview)
        btn_row.addWidget(preview_btn)
        apply_btn = QPushButton("Appliquer")
        apply_btn.clicked.connect(self._on_apply)
        btn_row.addWidget(apply_btn)
        outer.addLayout(btn_row)

        self._update_preview()

    def _toggle_sf_enabled(self, enabled: bool) -> None:
        for i in range(self.sf_form.rowCount()):
            for role in (QFormLayout.ItemRole.LabelRole, QFormLayout.ItemRole.FieldRole):
                item = self.sf_form.itemAt(i, role)
                if item and item.widget():
                    item.widget().setEnabled(enabled)

    def _build_new_text(self) -> str:
        text = self.current_text
        # Validation fields
        text = update_field(text, "validation", "pre_model", self.pre_model.text().strip())
        text = update_field(text, "validation", "post_model", self.post_model.text().strip())
        text = update_field(text, "validation", "antenna_version", self.antenna_version.text().strip())
        text = update_field(text, "validation", "power_version", self.power_version.text().strip())

        # Post-OTA check sections
        for sect, _label in _SCENARIOS:
            current_set = {f for f, cb in self.check_boxes[sect].items() if cb.isChecked()}
            initial = self._initial_checks[sect]
            # No change at all → leave file untouched for this section
            if initial == current_set:
                continue
            # User wants the "default" again (all 3 checked) AND it was already absent → no change
            # (covered by `initial is None` and current_set == ALL_FIELDS path)
            if initial is None and current_set == set(ALL_FIELDS):
                continue
            # User restored "all checked" state but section was previously explicit → strip it
            if initial is not None and current_set == set(ALL_FIELDS):
                if f"[{sect}]" in text:
                    text = remove_section(text, sect)
                continue
            # Otherwise write the section explicitly with the chosen fields
            check_value = ",".join(f for f in _FIELDS if f in current_set)
            text = update_field(text, sect, "check", check_value)

        # Scan filter — strip first, then re-add if enabled
        if "[scan_filter]" in text:
            text = remove_section(text, "scan_filter")
            if not text.endswith("\n"):
                text += "\n"
        if self.sf_enable.isChecked():
            kv = []
            kv.append(("name", self.sf_name.text().strip()))
            kv.append(("rssi_min", str(self.sf_rssi_min.value())))
            kv.append(("rssi_max", str(self.sf_rssi_max.value())))
            if self.sf_only_connectable.isChecked():
                kv.append(("only_connectable", "true"))
            if self.sf_only_bonded.isChecked():
                kv.append(("only_bonded", "true"))
            if self.sf_only_favourite.isChecked():
                kv.append(("only_favourite", "true"))
            text = append_section(text, "scan_filter", kv)
        return text

    def _update_preview(self) -> None:
        try:
            self.preview.setPlainText(self._build_new_text())
        except Exception as e:
            self.preview.setPlainText(f"[Erreur d'aperçu : {e}]")

    def _on_apply(self) -> None:
        try:
            new_text = self._build_new_text()
            if new_text == self.current_text:
                QMessageBox.information(self, "Aucun changement", "Aucune modification à enregistrer.")
                self.accept()
                return
            self.repo.save_config_text(self.name, new_text, pn_name=self.pn_name)
            QMessageBox.information(self, "Enregistré", "Configuration mise à jour sur le serveur.")
            self.accept()
        except Exception as e:
            QMessageBox.critical(self, "Erreur", f"Échec de l'écriture : {e}")
