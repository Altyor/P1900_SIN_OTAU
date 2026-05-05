"""Step-by-step wizard to create a new product folder on SFTP."""
from __future__ import annotations
import io
import os
from typing import Optional

from PIL import Image
from PyQt6.QtCore import Qt
from PyQt6.QtGui import QPixmap
from PyQt6.QtWidgets import (
    QWizard, QWizardPage, QVBoxLayout, QHBoxLayout, QLineEdit, QFormLayout,
    QPushButton, QLabel, QFileDialog, QCheckBox, QSpinBox, QGroupBox,
    QMessageBox, QGridLayout
)
import logging
_wlog = logging.getLogger("Wizard")

from ..sftp.product_repo import ProductRepo
from ..domain.firmware_validation import FirmwareValidation
from ..domain.scan_filter import ScanFilterConfig


THUMB_SIZE = 240


class _NamePage(QWizardPage):
    def __init__(self):
        super().__init__()
        self.setTitle("Nom du produit")
        self.setSubTitle("Ce nom sera celui du dossier sur le serveur SFTP.")
        layout = QFormLayout(self)
        self.name = QLineEdit()
        self.name.setPlaceholderText("ex. SIN-4-FP-21_VAV - NodOn")
        self.registerField("name*", self.name)
        layout.addRow("Nom :", self.name)


class _ImagePage(QWizardPage):
    def __init__(self):
        super().__init__()
        self.setTitle("Image du produit")
        self.setSubTitle("Sera redimensionnée à 600×600 et enregistrée comme product.png.")
        outer = QVBoxLayout(self)
        row = QHBoxLayout()
        self.path_edit = QLineEdit()
        self.path_edit.setReadOnly(True)
        row.addWidget(self.path_edit, stretch=1)
        browse = QPushButton("Parcourir…")
        browse.clicked.connect(self._on_browse)
        row.addWidget(browse)
        outer.addLayout(row)
        self.preview = QLabel("(aucune image)")
        self.preview.setFixedSize(THUMB_SIZE, THUMB_SIZE)
        self.preview.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.preview.setStyleSheet("background-color: #1a1a1a; border-radius: 4px;")
        outer.addWidget(self.preview, alignment=Qt.AlignmentFlag.AlignCenter)
        self._resized_path: Optional[str] = None
        # Re-render preview whenever the path changes — covers both Browse
        # and any external pre-fill (e.g. import-from-deposit flow).
        self.path_edit.textChanged.connect(self._refresh_preview)
        self.registerField("image_path*", self.path_edit)

    def _refresh_preview(self, path: str) -> None:
        if path and os.path.exists(path):
            pix = QPixmap(path).scaled(
                THUMB_SIZE, THUMB_SIZE,
                Qt.AspectRatioMode.KeepAspectRatio,
                Qt.TransformationMode.SmoothTransformation,
            )
            self.preview.setPixmap(pix)
        else:
            self.preview.clear()
            self.preview.setText("(aucune image)")

    def _on_browse(self) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "Choisir l'image", "", "Images (*.png *.jpg *.jpeg)")
        if not path:
            return
        try:
            with Image.open(path) as im:
                if im.mode != "RGBA":
                    im = im.convert("RGBA")
                if im.size[0] != im.size[1]:
                    canvas = Image.new("RGBA", (max(im.size),) * 2, (0, 0, 0, 0))
                    canvas.paste(im, ((canvas.size[0] - im.size[0]) // 2, (canvas.size[1] - im.size[1]) // 2))
                    im = canvas
                # Pillow ≥10 prefers Image.Resampling.LANCZOS; fall back to legacy alias.
                resample = getattr(getattr(Image, "Resampling", None), "LANCZOS", None) or getattr(Image, "LANCZOS")
                resized = im.resize((600, 600), resample)
                # Save to a temp PNG next to the source so the wizard has a stable path
                out_path = os.path.join(os.path.dirname(path), ".product_resized.png")
                resized.save(out_path, format="PNG", optimize=True)
                self._resized_path = out_path
                self.path_edit.setText(out_path)
            pix = QPixmap(out_path).scaled(
                THUMB_SIZE, THUMB_SIZE,
                Qt.AspectRatioMode.KeepAspectRatio,
                Qt.TransformationMode.SmoothTransformation,
            )
            self.preview.setPixmap(pix)
        except Exception as e:
            QMessageBox.critical(self, "Erreur", f"Image invalide : {e}")


class _ValidationPage(QWizardPage):
    def __init__(self):
        super().__init__()
        self.setTitle("Validation")
        self.setSubTitle("Valeurs attendues — voir config.ini sur le serveur.")
        form = QFormLayout(self)
        self.pre_model = QLineEdit()
        self.post_model = QLineEdit()
        self.antenna_version = QLineEdit()
        self.antenna_version.setPlaceholderText("(vide si pas de carte antenne)")
        self.power_version = QLineEdit()
        self.power_version.setPlaceholderText("(vide si pas de carte power)")
        form.addRow("pre_model :", self.pre_model)
        form.addRow("post_model :", self.post_model)
        form.addRow("antenna_version :", self.antenna_version)
        form.addRow("power_version :", self.power_version)
        self.registerField("pre_model*", self.pre_model)
        self.registerField("post_model*", self.post_model)
        self.registerField("antenna_version", self.antenna_version)
        self.registerField("power_version", self.power_version)


class _FirmwarePage(QWizardPage):
    def __init__(self):
        super().__init__()
        self.setTitle("Firmwares")
        self.setSubTitle("Antenne et/ou power — au moins un firmware obligatoire.")
        outer = QVBoxLayout(self)
        self.antenna_edit = self._file_picker(outer, "Antenne (.gbl/.zigbee)", "antenna_path")
        self.power_edit = self._file_picker(outer, "Power (.gbl/.zigbee)", "power_path")
        # Re-evaluate completeness whenever either edit changes
        self.antenna_edit.textChanged.connect(self.completeChanged.emit)
        self.power_edit.textChanged.connect(self.completeChanged.emit)

    def _file_picker(self, outer, label: str, field_name: str):
        outer.addWidget(QLabel(label))
        h = QHBoxLayout()
        edit = QLineEdit()
        edit.setReadOnly(True)
        h.addWidget(edit, stretch=1)
        btn = QPushButton("Parcourir…")
        btn.clicked.connect(lambda: self._on_browse(edit))
        h.addWidget(btn)
        outer.addLayout(h)
        self.registerField(field_name, edit)
        return edit

    def _on_browse(self, edit: QLineEdit) -> None:
        path, _ = QFileDialog.getOpenFileName(self, "Choisir le firmware", "", "Firmware (*.gbl *.zigbee)")
        if path:
            edit.setText(path)

    def isComplete(self) -> bool:
        return bool(self.antenna_edit.text().strip() or self.power_edit.text().strip())


class _PostOtaChecksPage(QWizardPage):
    """3×3 checkbox grid for [after_antenna]/[after_power]/[after_both].

    Defaults match the universal rule applied to every existing product:
      - antenna-only OTA → check antenna_version
      - power-only OTA   → check post_model + power_version
      - both             → check post_model + antenna_version + power_version
    Operators can deviate per product if needed."""
    FIELDS = ("post_model", "antenna_version", "power_version")
    SCENARIOS = (
        ("after_antenna", "Antenne seule", ("antenna_version",)),
        ("after_power",   "Power seul",     ("post_model", "power_version")),
        ("after_both",    "Les deux",        ("post_model", "antenna_version", "power_version")),
    )

    def __init__(self):
        super().__init__()
        self.setTitle("Vérifications post-OTA")
        self.setSubTitle(
            "Cochez les champs à vérifier après chaque type d'OTA. "
            "Les défauts proposés sont la règle universelle appliquée par tous les produits actuels."
        )
        outer = QVBoxLayout(self)
        grid = QGridLayout()
        grid.setHorizontalSpacing(20)
        grid.addWidget(QLabel(""), 0, 0)
        for col, field in enumerate(self.FIELDS, start=1):
            lbl = QLabel(field)
            lbl.setStyleSheet("font-weight: 600;")
            lbl.setAlignment(Qt.AlignmentFlag.AlignCenter)
            grid.addWidget(lbl, 0, col)
        self._checkboxes = {}
        for row, (sect, label, defaults) in enumerate(self.SCENARIOS, start=1):
            grid.addWidget(QLabel(label), row, 0)
            for col, field in enumerate(self.FIELDS, start=1):
                cb = QCheckBox()
                cb.setChecked(field in defaults)
                grid.addWidget(cb, row, col, alignment=Qt.AlignmentFlag.AlignCenter)
                self._checkboxes[(sect, field)] = cb
        outer.addLayout(grid)

    def check_sets(self) -> dict:
        """Return current checkbox state as {section: set(field)}."""
        return {
            sect: {f for f in self.FIELDS if self._checkboxes[(sect, f)].isChecked()}
            for sect, _, _ in self.SCENARIOS
        }

    def set_from_validation(self, after_antenna, after_power, after_both) -> None:
        """Pre-fill from an existing config (used on import). Sections that were
        absent in the source (None) are left at their defaults."""
        sets = {
            "after_antenna": after_antenna,
            "after_power":   after_power,
            "after_both":    after_both,
        }
        for sect, fields in sets.items():
            if fields is None:
                continue
            for f in self.FIELDS:
                self._checkboxes[(sect, f)].setChecked(f in fields)


class _ScanFilterPage(QWizardPage):
    def __init__(self):
        super().__init__()
        self.setTitle("Filtre de scan (optionnel)")
        self.setSubTitle("Laissez désactivé pour utiliser le défaut usine (SIN, RSSI -40…0 dBm).")
        outer = QVBoxLayout(self)
        self.enable = QCheckBox("Définir un filtre de scan personnalisé")
        outer.addWidget(self.enable)
        self.box = QGroupBox()
        form = QFormLayout(self.box)
        self.name = QLineEdit("SIN")
        # Integer RSSI — RSSI values are always integers in the existing configs.
        # Plain QSpinBox avoids QDoubleSpinBox locale-formatting quirks under fr_FR.
        self.rssi_min = QSpinBox(); self.rssi_min.setRange(-100, 0); self.rssi_min.setValue(-40)
        self.rssi_max = QSpinBox(); self.rssi_max.setRange(-100, 0); self.rssi_max.setValue(0)
        form.addRow("name :", self.name)
        form.addRow("rssi_min :", self.rssi_min)
        form.addRow("rssi_max :", self.rssi_max)
        outer.addWidget(self.box)
        self.box.setEnabled(False)
        # Use a plain method so we can log it — useful while debugging the
        # earlier crash report on this exact toggle.
        self.enable.toggled.connect(self._on_toggle)
        self.registerField("sf_enabled", self.enable)
        self.registerField("sf_name", self.name)
        self.registerField("sf_rssi_min", self.rssi_min)
        self.registerField("sf_rssi_max", self.rssi_max)

    def _on_toggle(self, checked: bool) -> None:
        _wlog.info(f"_ScanFilterPage toggle → {checked}")
        try:
            self.box.setEnabled(checked)
        except Exception:
            _wlog.exception("setEnabled failed")
            raise


class _ReviewPage(QWizardPage):
    def __init__(self):
        super().__init__()
        self.setTitle("Revue + envoi")
        self.setSubTitle("Vérifiez puis cliquez Terminer pour pousser sur le serveur.")
        self.summary = QLabel()
        self.summary.setWordWrap(True)
        self.summary.setStyleSheet("font-family: 'Consolas', monospace;")
        layout = QVBoxLayout(self)
        layout.addWidget(self.summary)

    def initializePage(self) -> None:
        f = self.wizard().field
        sf = ""
        if f("sf_enabled"):
            sf = (
                f"\n[scan_filter]\nname={f('sf_name')}\n"
                f"rssi_min={int(f('sf_rssi_min'))}\nrssi_max={int(f('sf_rssi_max'))}\n"
            )
        # Show basenames, not full temp/staging paths — long paths overflow the
        # QLabel without wrapping. Full path goes in the tooltip.
        def short(path: str) -> str:
            return os.path.basename(path) if path else "(aucune)"
        full_paths = (
            f"Image   : {f('image_path') or ''}\n"
            f"Antenne : {f('antenna_path') or ''}\n"
            f"Power   : {f('power_path') or ''}"
        )
        self.summary.setText(
            f"Nom    : {f('name')}\n"
            f"Image  : {short(f('image_path'))}\n"
            f"Antenne: {short(f('antenna_path'))}\n"
            f"Power  : {short(f('power_path'))}\n\n"
            f"[validation]\n"
            f"pre_model={f('pre_model')}\n"
            f"post_model={f('post_model')}\n"
            f"antenna_version={f('antenna_version')}\n"
            f"power_version={f('power_version')}\n"
            f"\n[after_antenna]\ncheck=antenna_version\n{sf}"
        )
        self.summary.setToolTip(full_paths)


class NewProductWizard(QWizard):
    def __init__(self, repo: ProductRepo, parent=None, initial_payload=None):
        super().__init__(parent)
        self.repo = repo
        title = "Importer depuis /deposit/" if initial_payload else "Nouveau produit"
        self.setWindowTitle(title)
        self.setMinimumSize(640, 540)
        self.setWizardStyle(QWizard.WizardStyle.ModernStyle)
        self.checks_page = _PostOtaChecksPage()
        self.addPage(_NamePage())
        self.addPage(_ImagePage())
        self.addPage(_ValidationPage())
        self.addPage(_FirmwarePage())
        self.addPage(self.checks_page)
        self.addPage(_ScanFilterPage())
        self.addPage(_ReviewPage())
        if initial_payload:
            self._prefill(initial_payload)

    def cleanupPage(self, id: int) -> None:
        # Default Qt behavior resets the page's fields when the user clicks Back.
        # That throws away values pre-filled from import-from-deposit (and
        # values the user has already typed). Suppress the reset so navigation
        # is non-destructive.
        return

    def _prefill(self, p: dict) -> None:
        """Pre-populate every page's fields from a payload dict. Used by the
        import-from-deposit flow so the operator can review/modify a customer
        drop with the same UI they'd use to create a product manually."""
        if p.get("name"):
            self.setField("name", p["name"])
        if p.get("image_path"):
            self.setField("image_path", p["image_path"])
        if p.get("antenna_path"):
            self.setField("antenna_path", p["antenna_path"])
        if p.get("power_path"):
            self.setField("power_path", p["power_path"])
        v = p.get("validation")
        if v is not None:
            self.setField("pre_model", v.pre_model)
            self.setField("post_model", v.post_model)
            self.setField("antenna_version", v.antenna_version)
            self.setField("power_version", v.power_version)
            self.checks_page.set_from_validation(v.after_antenna, v.after_power, v.after_both)
            sf = v.scan_filter
            if sf is not None:
                self.setField("sf_enabled", True)
                if sf.name is not None:
                    self.setField("sf_name", sf.name)
                self.setField("sf_rssi_min", int(sf.rssi_min))
                self.setField("sf_rssi_max", int(sf.rssi_max))

    def accept(self) -> None:
        try:
            f = self.field
            name = f("name").strip()
            _wlog.info(f"Wizard accept: name={name!r} sf_enabled={f('sf_enabled')}")
            sf = None
            if f("sf_enabled"):
                sf = ScanFilterConfig(
                    name=f("sf_name").strip() or None,
                    rssi_min=float(f("sf_rssi_min")),
                    rssi_max=float(f("sf_rssi_max")),
                )
            checks = self.checks_page.check_sets()
            validation = FirmwareValidation(
                pre_model=f("pre_model").strip(),
                post_model=f("post_model").strip(),
                antenna_version=f("antenna_version").strip(),
                power_version=f("power_version").strip(),
                after_antenna=checks["after_antenna"],
                after_power=checks["after_power"],
                after_both=checks["after_both"],
                scan_filter=sf,
            )
            try:
                self.repo.create_product(
                    name=name,
                    validation=validation,
                    local_image_path=f("image_path"),
                    local_antenna_path=f("antenna_path") or None,
                    local_power_path=f("power_path") or None,
                )
                QMessageBox.information(self, "Créé", f"Produit {name} ajouté sur le serveur.")
                super().accept()
            except FileExistsError as e:
                QMessageBox.warning(self, "Conflit", str(e))
            except Exception as e:
                QMessageBox.critical(self, "Erreur", f"Échec : {e}")
        except Exception:
            _wlog.exception("Wizard accept crashed")
            raise
