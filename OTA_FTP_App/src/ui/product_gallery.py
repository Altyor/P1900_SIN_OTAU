"""Card grid showing every product on the SFTP server + a 'Nouveau produit' tile.

Loading is three-phase so the UI never feels stuck:
  1. **names_ready**  : 1 SFTP round trip (listdir of /production/) → cards rendered
                        as placeholders with names only. Almost-instant.
  2. **summary_ready**: per-product config.ini reads streamed one at a time → each
                        card fills in its subtitle (model, antenna_version, filter).
  3. **image_ready**  : images streamed last, lower priority. Each is checked against
                        a local disk cache keyed by (name, server_size); cache hit
                        avoids the download entirely.
"""
from __future__ import annotations
from typing import Dict, List, Optional

from PyQt6.QtCore import Qt, QThread, pyqtSignal
from PyQt6.QtWidgets import QWidget, QVBoxLayout, QHBoxLayout, QLabel, QPushButton, QScrollArea, QGridLayout, QFrame

from ..sftp.product_repo import ProductRepo, ProductSummary
from ..util.image_cache import ImageCache
from .product_card import ProductCard, NewProductCard


DEFAULT_COLUMNS = 4
CARD_WIDTH = 220       # mirrors product_card.CARD_WIDTH
GRID_SPACING = 16


class _LoadProductsWorker(QThread):
    names_ready = pyqtSignal(list)                 # List[str]
    summary_ready = pyqtSignal(object)              # ProductSummary
    image_ready = pyqtSignal(str, bytes)            # (name, png bytes)
    finished_streaming = pyqtSignal()
    failed = pyqtSignal(str)

    def __init__(self, repo: ProductRepo, cache: ImageCache):
        super().__init__()
        self.repo = repo
        self.cache = cache

    def run(self) -> None:
        # Phase 1: cheap listing
        try:
            names = self.repo.list_product_names()
            self.names_ready.emit(names)
        except Exception as e:
            self.failed.emit(str(e))
            return

        # Phase 2: configs (one round trip each, streamed)
        summaries: List[ProductSummary] = []
        for name in names:
            try:
                s = self.repo.fetch_summary(name)
            except Exception:
                continue
            summaries.append(s)
            self.summary_ready.emit(s)

        # Phase 3: images, cache-first
        for s in summaries:
            try:
                path = f"{self.repo.root}/{s.name}/product.png"
                size = self.repo.cli.stat_size(path)
                if size is None:
                    continue
                cached = self.cache.get(s.name, size)
                if cached is not None:
                    self.image_ready.emit(s.name, cached)
                    continue
                data = self.repo.cli.read_bytes(path)
                self.cache.put(s.name, size, data)
                self.image_ready.emit(s.name, data)
            except Exception:
                pass
        self.finished_streaming.emit()


class ProductGallery(QWidget):
    product_clicked = pyqtSignal(str)
    new_product_clicked = pyqtSignal()
    refresh_clicked = pyqtSignal()
    root_change_requested = pyqtSignal(str)
    status_changed = pyqtSignal(str)  # for /production/ ↔ /deposit/ toggle

    def __init__(self, repo: ProductRepo, image_cache: ImageCache):
        super().__init__()
        self.repo = repo
        self.image_cache = image_cache
        self._summaries: List[ProductSummary] = []
        self._cards_by_name: Dict[str, ProductCard] = {}
        self._worker: Optional[_LoadProductsWorker] = None
        self._loaded_image_count = 0
        self._total_with_images = 0
        self._current_cols = DEFAULT_COLUMNS

        outer = QVBoxLayout(self)
        outer.setContentsMargins(20, 20, 20, 20)
        outer.setSpacing(12)

        # Header
        header = QHBoxLayout()
        title = QLabel("Bibliothèque de produits")
        title.setProperty("role", "title")
        header.addWidget(title)
        header.addStretch(1)

        self.root_label = QLabel(repo.root)
        self.root_label.setProperty("role", "subtitle")
        header.addWidget(self.root_label)

        self.toggle_root_btn = QPushButton(self._toggle_label())
        self.toggle_root_btn.setProperty("role", "ghost")
        self.toggle_root_btn.clicked.connect(self._on_toggle_root)
        header.addWidget(self.toggle_root_btn)

        self.refresh_btn = QPushButton("Rafraîchir")
        self.refresh_btn.setProperty("role", "ghost")
        self.refresh_btn.clicked.connect(self.refresh_clicked.emit)
        header.addWidget(self.refresh_btn)
        outer.addLayout(header)

        self.status_label = QLabel("Chargement…")
        self.status_label.setProperty("role", "subtitle")
        outer.addWidget(self.status_label)

        # Scrollable grid
        self.scroll = QScrollArea()
        self.scroll.setWidgetResizable(True)
        self.grid_host = QFrame()
        self.grid = QGridLayout(self.grid_host)
        self.grid.setContentsMargins(0, 0, 0, 0)
        self.grid.setSpacing(16)
        self.grid.setAlignment(Qt.AlignmentFlag.AlignTop | Qt.AlignmentFlag.AlignLeft)
        self.scroll.setWidget(self.grid_host)
        outer.addWidget(self.scroll, stretch=1)

    def _toggle_label(self) -> str:
        return "Basculer vers /deposit/" if self.repo.root.endswith("production") else "Basculer vers /production/"

    def _on_toggle_root(self) -> None:
        new_root = "/deposit" if self.repo.root.endswith("production") else "/production"
        self.root_change_requested.emit(new_root)

    def update_root(self, new_root: str) -> None:
        self.repo.root = new_root.rstrip("/")
        self.root_label.setText(self.repo.root)
        self.toggle_root_btn.setText(self._toggle_label())
        self.refresh()

    # ------- data loading -------

    def refresh(self) -> None:
        self.status_label.setText("Chargement…")
        self.status_changed.emit(f"Chargement depuis {self.repo.root}…")
        self._clear_grid()
        self._cards_by_name.clear()
        self._summaries = []
        self._loaded_image_count = 0
        self._total_with_images = 0
        if self._worker is not None and self._worker.isRunning():
            self._worker.wait(500)
        self._worker = _LoadProductsWorker(self.repo, self.image_cache)
        self._worker.names_ready.connect(self._on_names)
        self._worker.summary_ready.connect(self._on_summary)
        self._worker.image_ready.connect(self._on_image)
        self._worker.finished_streaming.connect(self._on_done)
        self._worker.failed.connect(self._on_failed)
        self._worker.start()

    def _on_names(self, names: List[str]) -> None:
        # Render placeholder cards immediately — full grid is laid out before any
        # config or image arrives.
        self._render_placeholders(names)
        msg = f"{len(names)} produit(s) — lecture des configurations…"
        self.status_label.setText(msg)
        self.status_changed.emit(msg)

    def _on_summary(self, s: ProductSummary) -> None:
        # Update the placeholder card for this product with the parsed config.
        self._summaries.append(s)
        if s.has_image:
            self._total_with_images += 1
        card = self._cards_by_name.get(s.name)
        if card is not None:
            card.set_summary(s)
        self.status_changed.emit(
            f"Configurations : {len(self._summaries)} produit(s) lu(s)"
        )

    def _on_image(self, name: str, data: bytes) -> None:
        card = self._cards_by_name.get(name)
        if card is not None:
            card.set_image(data)
        self._loaded_image_count += 1
        if self._total_with_images > 0:
            self.status_changed.emit(
                f"Images : {self._loaded_image_count}/{self._total_with_images}"
            )

    def _on_done(self) -> None:
        msg = f"{len(self._summaries)} produit(s) — prêt"
        self.status_label.setText(msg)
        self.status_changed.emit(msg)

    def _on_failed(self, err: str) -> None:
        self.status_label.setText(f"Erreur : {err}")
        self.status_changed.emit(f"Erreur : {err}")

    # ------- grid layout -------

    def _clear_grid(self) -> None:
        while self.grid.count():
            item = self.grid.takeAt(0)
            w = item.widget()
            if w is not None:
                w.setParent(None)

    def _render_placeholders(self, names: List[str]) -> None:
        """Lay out the grid with placeholder cards (name only). Configs + images
        fill in afterwards via set_summary / set_image."""
        self._clear_grid()
        self._cards_by_name.clear()
        cols = self._compute_columns()
        self._current_cols = cols
        new_card = NewProductCard()
        new_card.clicked.connect(self.new_product_clicked.emit)
        self.grid.addWidget(new_card, 0, 0)

        idx = 1
        for name in names:
            row, col = divmod(idx, cols)
            placeholder = ProductSummary(
                name=name, has_image=True, config_text=None,
                validation=None, has_pn_subfolder=False,
            )
            card = ProductCard(placeholder, image_bytes=None)
            card.clicked.connect(self.product_clicked.emit)
            self.grid.addWidget(card, row, col)
            self._cards_by_name[name] = card
            idx += 1

    # ------- responsive reflow -------

    def _compute_columns(self) -> int:
        """Pick a column count based on available viewport width."""
        available = self.scroll.viewport().width()
        if available <= 0:
            return DEFAULT_COLUMNS
        # Account for the outer 20px margin (already excluded from viewport)
        # and the grid's per-card spacing.
        card_slot = CARD_WIDTH + GRID_SPACING
        cols = max(1, available // card_slot)
        return cols

    def _relayout(self) -> None:
        new_cols = self._compute_columns()
        if new_cols == self._current_cols or self.grid.count() == 0:
            self._current_cols = new_cols
            return
        self._current_cols = new_cols
        # Collect widgets in current order, then re-place them with new column count
        widgets = []
        while self.grid.count():
            item = self.grid.takeAt(0)
            w = item.widget()
            if w is not None:
                widgets.append(w)
        for idx, w in enumerate(widgets):
            row, col = divmod(idx, new_cols)
            self.grid.addWidget(w, row, col)

    def resizeEvent(self, event):
        super().resizeEvent(event)
        self._relayout()
