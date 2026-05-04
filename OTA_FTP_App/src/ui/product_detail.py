"""Detail page for one product. Image + parsed config + action buttons."""
from __future__ import annotations
from typing import Optional

from PyQt6.QtCore import Qt, QThread, pyqtSignal
from PyQt6.QtGui import QPixmap, QImage
from PyQt6.QtWidgets import (
    QWidget, QVBoxLayout, QHBoxLayout, QLabel, QPushButton, QFormLayout,
    QGroupBox, QScrollArea, QFrame, QSizePolicy, QMessageBox, QComboBox
)

from ..sftp.product_repo import ProductRepo, ProductDetail
from ..util.image_cache import ImageCache


class _LoadDetailWorker(QThread):
    """Two-phase fetch off the UI thread:
       1. PN listing + config + FW listings → emit `config_ready(detail, pns)`
          where pns is the list of available PN sub-folders (empty for direct).
       2. image: cache hit returns instantly, miss reads from SFTP and caches.
    """
    config_ready = pyqtSignal(object, list)     # (ProductDetail, List[str])
    image_ready = pyqtSignal(str, object)         # (name, bytes or None)
    failed = pyqtSignal(str)

    def __init__(self, repo: ProductRepo, cache: ImageCache, name: str,
                  pn_name=None):
        super().__init__()
        self.repo = repo
        self.cache = cache
        self.name = name
        self.pn_name = pn_name

    def run(self) -> None:
        try:
            pns = self.repo.list_pns(self.name)
            detail = self.repo.fetch_config(self.name, self.pn_name)
            self.config_ready.emit(detail, pns)
        except Exception as e:
            self.failed.emit(str(e))
            return
        # Phase 2: image
        try:
            path = f"{self.repo.root}/{self.name}/product.png"
            size = self.repo.cli.stat_size(path)
            if size is None:
                self.image_ready.emit(self.name, None)
                return
            cached = self.cache.get(self.name, size)
            if cached is not None:
                self.image_ready.emit(self.name, cached)
                return
            data = self.repo.cli.read_bytes(path)
            self.cache.put(self.name, size, data)
            self.image_ready.emit(self.name, data)
        except Exception:
            self.image_ready.emit(self.name, None)


IMAGE_DISPLAY_SIZE = 280


class ProductDetailPage(QWidget):
    back_clicked = pyqtSignal()
    edit_config_clicked = pyqtSignal(str)         # product name
    replace_fw_clicked = pyqtSignal(str, str)     # product name, slot ('Antenna'|'Power')
    delete_clicked = pyqtSignal(str)              # product name
    add_variant_clicked = pyqtSignal(str)         # product name (PN-layout products only)
    status_changed = pyqtSignal(str)              # bottom-bar status text

    def __init__(self, repo: ProductRepo, image_cache: ImageCache):
        super().__init__()
        self.repo = repo
        self.image_cache = image_cache
        self._current_name: Optional[str] = None
        self._current_detail: Optional[ProductDetail] = None
        self._worker: Optional[_LoadDetailWorker] = None

        outer = QVBoxLayout(self)
        outer.setContentsMargins(20, 20, 20, 20)
        outer.setSpacing(12)

        # Header bar
        bar = QHBoxLayout()
        self.back_btn = QPushButton("← Retour")
        self.back_btn.setProperty("role", "ghost")
        self.back_btn.clicked.connect(self.back_clicked.emit)
        bar.addWidget(self.back_btn)
        self.title = QLabel()
        self.title.setProperty("role", "title")
        bar.addWidget(self.title, stretch=1)

        # PN selector — visible only when the product has multiple PN sub-folders
        self.pn_label = QLabel("PN :")
        self.pn_label.setVisible(False)
        bar.addWidget(self.pn_label)
        self.pn_combo = QComboBox()
        self.pn_combo.setVisible(False)
        self.pn_combo.currentTextChanged.connect(self._on_pn_changed)
        bar.addWidget(self.pn_combo)

        self.add_variant_btn = QPushButton("+ Variante")
        self.add_variant_btn.setProperty("role", "ghost")
        self.add_variant_btn.setVisible(False)
        self.add_variant_btn.clicked.connect(self._on_add_variant)
        bar.addWidget(self.add_variant_btn)

        self.edit_btn = QPushButton("Modifier la configuration")
        self.edit_btn.clicked.connect(self._on_edit)
        bar.addWidget(self.edit_btn)
        self.delete_btn = QPushButton("Supprimer")
        self.delete_btn.setProperty("role", "danger")
        self.delete_btn.setEnabled(False)
        self.delete_btn.clicked.connect(self._on_delete)
        bar.addWidget(self.delete_btn)
        outer.addLayout(bar)
        self._available_pns: list = []

        # Body — scrollable
        scroll = QScrollArea()
        scroll.setWidgetResizable(True)
        host = QWidget()
        body = QHBoxLayout(host)
        body.setContentsMargins(0, 0, 0, 0)
        body.setSpacing(20)

        # Left: image
        self.image_label = QLabel()
        self.image_label.setFixedSize(IMAGE_DISPLAY_SIZE, IMAGE_DISPLAY_SIZE)
        self.image_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.image_label.setStyleSheet("background-color: #1a1a1a; border-radius: 6px;")
        body.addWidget(self.image_label, alignment=Qt.AlignmentFlag.AlignTop)

        # Right: config + FW
        right = QVBoxLayout()
        right.setSpacing(12)

        self.validation_group = QGroupBox("Validation")
        self.validation_form = QFormLayout(self.validation_group)
        right.addWidget(self.validation_group)

        self.scan_group = QGroupBox("Filtre de scan BLE")
        self.scan_form = QFormLayout(self.scan_group)
        right.addWidget(self.scan_group)

        self.fw_group = QGroupBox("Firmwares")
        self.fw_layout = QVBoxLayout(self.fw_group)
        right.addWidget(self.fw_group)

        right.addStretch(1)
        body.addLayout(right, stretch=1)

        scroll.setWidget(host)
        outer.addWidget(scroll, stretch=1)

    def load(self, name: str, pn_name=None) -> None:
        self._current_name = name
        self._current_detail = None
        self.title.setText(name)
        self.edit_btn.setEnabled(False)
        self.delete_btn.setEnabled(False)
        self.status_changed.emit(f"Chargement de {name}…")
        # Clear stale content
        self._reset_form(self.validation_form)
        self._reset_form(self.scan_form)
        self._reset_layout(self.fw_layout)
        self.image_label.setText("…")
        self.image_label.setPixmap(QPixmap())

        if self._worker is not None and self._worker.isRunning():
            self._worker.wait(500)
        self._worker = _LoadDetailWorker(self.repo, self.image_cache, name, pn_name)
        self._worker.config_ready.connect(self._on_config_ready)
        self._worker.image_ready.connect(self._on_image_ready)
        self._worker.failed.connect(self._on_failed)
        self._worker.start()

    def _on_config_ready(self, detail: ProductDetail, pns: list) -> None:
        self._current_detail = detail
        self._available_pns = pns

        # PN selector: only shown when the product has more than one variant.
        # Block signals while we rebuild so the change handler doesn't fire.
        self.pn_combo.blockSignals(True)
        self.pn_combo.clear()
        if len(pns) > 1:
            self.pn_combo.addItems(pns)
            if detail.pn_name and detail.pn_name in pns:
                self.pn_combo.setCurrentText(detail.pn_name)
            self.pn_label.setVisible(True)
            self.pn_combo.setVisible(True)
        else:
            self.pn_label.setVisible(False)
            self.pn_combo.setVisible(False)
        self.pn_combo.blockSignals(False)

        # "+ Variante" button is shown for PN-layout products only —
        # it appends another PN sub-folder under the same product.
        self.add_variant_btn.setVisible(detail.pn_name is not None)

        # Title: prefix with PN if the layout uses one
        if detail.pn_name:
            self.title.setText(f"{detail.name}    (PN {detail.pn_name})")
        else:
            self.title.setText(detail.name)

        self._render(detail)
        self.edit_btn.setEnabled(True)
        self.delete_btn.setEnabled(True)
        self.status_changed.emit(f"{detail.name} : configuration chargée, image en cours…")

    def _on_pn_changed(self, new_pn: str) -> None:
        if not new_pn or self._current_name is None:
            return
        if self._current_detail and self._current_detail.pn_name == new_pn:
            return  # already showing this PN
        self.load(self._current_name, pn_name=new_pn)

    def current_detail(self) -> Optional[ProductDetail]:
        """Used by MainWindow to open the editor without re-fetching."""
        return self._current_detail

    def current_pn(self) -> Optional[str]:
        """Currently-selected PN sub-folder, or None for direct-layout products."""
        return self._current_detail.pn_name if self._current_detail else None

    def variant_count(self) -> int:
        """0 = direct layout (no PN). 1+ = PN layout, that many variants exist."""
        return len(self._available_pns)

    def _on_image_ready(self, name: str, data) -> None:
        if name != self._current_name:
            return  # user navigated away
        if data is None:
            self.image_label.setText("(aucune image)")
            self.status_changed.emit(f"{name} chargé (sans image)")
            return
        img = QImage.fromData(data)
        if img.isNull():
            self.image_label.setText("(image illisible)")
        else:
            pix = QPixmap.fromImage(img).scaled(
                IMAGE_DISPLAY_SIZE, IMAGE_DISPLAY_SIZE,
                Qt.AspectRatioMode.KeepAspectRatio,
                Qt.TransformationMode.SmoothTransformation,
            )
            self.image_label.setPixmap(pix)
        self.status_changed.emit(f"{name} chargé")

    def _on_failed(self, err: str) -> None:
        # Config couldn't be parsed — most often the legacy PN sub-folder layout
        # (e.g. SIN-4-1-21/100004/FW/config.ini), which v1 doesn't read.
        # Show the diagnostic inline rather than as a popup, and keep the delete
        # button enabled so the operator can still purge the folder if they want.
        self._reset_form(self.validation_form)
        self._reset_form(self.scan_form)
        self._reset_layout(self.fw_layout)
        warn = QLabel(
            "Configuration illisible (probablement layout PN historique non géré).\n"
            "La suppression reste possible."
        )
        warn.setProperty("role", "subtitle")
        warn.setWordWrap(True)
        self.fw_layout.addWidget(warn)
        self.image_label.setText("(non chargé)")
        self.delete_btn.setEnabled(True)
        self._current_detail = None  # editor stays disabled
        self.status_changed.emit(f"Erreur : {err}")

    def _render(self, d: ProductDetail) -> None:
        # Image — only set here if eagerly provided; otherwise the worker's
        # image_ready signal will fill it in.
        if d.image_bytes:
            img = QImage.fromData(d.image_bytes)
            if not img.isNull():
                pix = QPixmap.fromImage(img).scaled(
                    IMAGE_DISPLAY_SIZE, IMAGE_DISPLAY_SIZE,
                    Qt.AspectRatioMode.KeepAspectRatio,
                    Qt.TransformationMode.SmoothTransformation,
                )
                self.image_label.setPixmap(pix)
            else:
                self.image_label.setText("(image illisible)")

        # Validation
        self._reset_form(self.validation_form)
        v = d.validation
        self.validation_form.addRow("Modèle pré-OTA :", QLabel(v.pre_model or "(vide)"))
        self.validation_form.addRow("Modèle post-OTA :", QLabel(v.post_model or "(vide)"))
        self.validation_form.addRow("Version antenne :", QLabel(v.antenna_version or "(vide)"))
        self.validation_form.addRow("Version power :", QLabel(v.power_version if v.power_version else "(aucune)"))
        for label, fields in (
            ("Vérif après antenne :", v.after_antenna),
            ("Vérif après power :",   v.after_power),
            ("Vérif après les deux :", v.after_both),
        ):
            if fields is None:
                self.validation_form.addRow(label, QLabel("(non spécifié — défaut: tous)"))
            else:
                self.validation_form.addRow(label, QLabel(", ".join(sorted(fields)) or "(aucun)"))

        # Scan filter
        self._reset_form(self.scan_form)
        sf = v.scan_filter
        if sf is None:
            self.scan_form.addRow(QLabel("(défaut usine : SIN, RSSI -40…0 dBm)"))
        else:
            self.scan_form.addRow("Nom :", QLabel(sf.name or "(aucun)"))
            self.scan_form.addRow("RSSI :", QLabel(f"{sf.rssi_min} → {sf.rssi_max} dBm"))
            if sf.ble_formats:
                self.scan_form.addRow("Formats :", QLabel(", ".join(sf.ble_formats)))
            flags = []
            if sf.only_connectable: flags.append("connectable")
            if sf.only_bonded:      flags.append("apparié")
            if sf.only_favourite:   flags.append("favori")
            if flags:
                self.scan_form.addRow("Filtres :", QLabel(", ".join(flags)))

        # Firmwares
        self._reset_layout(self.fw_layout)
        for slot, files in (("Antenna", d.antenna_files), ("Power", d.power_files)):
            slot_row = QHBoxLayout()
            label = QLabel(f"{slot} :")
            label.setStyleSheet("font-weight: 600;")
            slot_row.addWidget(label)
            if files:
                for f in files:
                    item = QLabel(f"{f.name}  ({f.size:,} octets)")
                    slot_row.addWidget(item)
            else:
                slot_row.addWidget(QLabel("(aucun)"))
            slot_row.addStretch(1)
            replace_btn = QPushButton(f"Remplacer {slot}")
            replace_btn.setProperty("role", "ghost")
            replace_btn.clicked.connect(lambda _checked, s=slot: self._on_replace_fw(s))
            slot_row.addWidget(replace_btn)
            wrap = QFrame()
            wrap.setLayout(slot_row)
            self.fw_layout.addWidget(wrap)

    def _reset_form(self, form: QFormLayout) -> None:
        while form.rowCount() > 0:
            form.removeRow(0)

    def _reset_layout(self, layout: QVBoxLayout) -> None:
        while layout.count() > 0:
            item = layout.takeAt(0)
            w = item.widget()
            if w is not None:
                w.setParent(None)

    def _on_edit(self) -> None:
        if self._current_name:
            self.edit_config_clicked.emit(self._current_name)

    def _on_delete(self) -> None:
        if self._current_name:
            self.delete_clicked.emit(self._current_name)

    def _on_add_variant(self) -> None:
        if self._current_name:
            self.add_variant_clicked.emit(self._current_name)

    def available_pns(self) -> list:
        return list(self._available_pns)

    def _on_replace_fw(self, slot: str) -> None:
        if self._current_name:
            self.replace_fw_clicked.emit(self._current_name, slot)
