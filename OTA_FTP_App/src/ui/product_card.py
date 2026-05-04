"""Single product tile in the gallery. Click → emits `clicked(name)`."""
from __future__ import annotations
from typing import Optional

from PyQt6.QtCore import Qt, pyqtSignal
from PyQt6.QtGui import QPixmap, QImage
from PyQt6.QtWidgets import QFrame, QLabel, QVBoxLayout, QSizePolicy

from ..sftp.product_repo import ProductSummary


CARD_WIDTH = 220
CARD_HEIGHT = 280
THUMB_SIZE = 180


class ProductCard(QFrame):
    clicked = pyqtSignal(str)

    def __init__(self, summary: ProductSummary, image_bytes: Optional[bytes] = None):
        super().__init__()
        self.summary = summary
        self.setProperty("role", "card")
        self.setFixedSize(CARD_WIDTH, CARD_HEIGHT)
        self.setSizePolicy(QSizePolicy.Policy.Fixed, QSizePolicy.Policy.Fixed)
        self.setCursor(Qt.CursorShape.PointingHandCursor)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(10, 10, 10, 10)
        layout.setSpacing(6)

        # Thumbnail
        self.thumb = QLabel()
        self.thumb.setFixedSize(THUMB_SIZE, THUMB_SIZE)
        self.thumb.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.thumb.setStyleSheet("background-color: #1a1a1a; border-radius: 4px;")
        if image_bytes:
            self.set_image(image_bytes)
        else:
            self.thumb.setText("…")
        layout.addWidget(self.thumb, alignment=Qt.AlignmentFlag.AlignCenter)

        # Name
        self.name_label = QLabel(summary.name)
        self.name_label.setWordWrap(True)
        self.name_label.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.name_label.setStyleSheet("font-weight: 600;")
        layout.addWidget(self.name_label)

        # Subtitle — gets populated when the config summary arrives
        self.subtitle = QLabel("")
        self.subtitle.setProperty("role", "caption")
        self.subtitle.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.subtitle.setWordWrap(True)
        layout.addWidget(self.subtitle)
        self.set_summary(summary)

        layout.addStretch(1)

    def set_summary(self, summary: ProductSummary) -> None:
        """Re-render the subtitle from a (possibly updated) summary."""
        self.summary = summary
        if summary.validation:
            v = summary.validation
            parts = []
            if v.post_model:
                parts.append(v.post_model)
            if summary.pn_name:
                parts.append(f"PN {summary.pn_name}")
            if v.antenna_version:
                parts.append(f"antenne {v.antenna_version}")
            if v.power_version:
                parts.append(f"power {v.power_version}")
            if v.scan_filter:
                parts.append(f"filtre : {v.scan_filter.name or '*'}")
            self.subtitle.setText(" · ".join(parts))
        elif summary.has_pn_subfolder:
            self.subtitle.setText("(layout PN non géré)")
        elif summary.config_text is None:
            self.subtitle.setText("…")
        else:
            self.subtitle.setText("")

    def set_image(self, image_bytes: bytes) -> None:
        img = QImage.fromData(image_bytes)
        if img.isNull():
            self.thumb.setText("(image illisible)")
            return
        pix = QPixmap.fromImage(img).scaled(
            THUMB_SIZE, THUMB_SIZE,
            Qt.AspectRatioMode.KeepAspectRatio,
            Qt.TransformationMode.SmoothTransformation,
        )
        self.thumb.setPixmap(pix)

    def mousePressEvent(self, event) -> None:
        if event.button() == Qt.MouseButton.LeftButton:
            self.clicked.emit(self.summary.name)
        super().mousePressEvent(event)


class NewProductCard(QFrame):
    clicked = pyqtSignal()

    def __init__(self):
        super().__init__()
        self.setProperty("role", "card-new")
        self.setFixedSize(CARD_WIDTH, CARD_HEIGHT)
        self.setSizePolicy(QSizePolicy.Policy.Fixed, QSizePolicy.Policy.Fixed)
        self.setCursor(Qt.CursorShape.PointingHandCursor)

        layout = QVBoxLayout(self)
        layout.setContentsMargins(10, 10, 10, 10)
        layout.addStretch(1)
        plus = QLabel("+")
        plus.setAlignment(Qt.AlignmentFlag.AlignCenter)
        plus.setStyleSheet("font-size: 64px; color: #0a5a8a; font-weight: 300;")
        layout.addWidget(plus)
        title = QLabel("Ajouter")
        title.setAlignment(Qt.AlignmentFlag.AlignCenter)
        title.setStyleSheet("font-weight: 600;")
        layout.addWidget(title)
        sub = QLabel("Nouveau produit\nou variante")
        sub.setAlignment(Qt.AlignmentFlag.AlignCenter)
        sub.setProperty("role", "caption")
        layout.addWidget(sub)
        layout.addStretch(1)

    def mousePressEvent(self, event) -> None:
        if event.button() == Qt.MouseButton.LeftButton:
            self.clicked.emit()
        super().mousePressEvent(event)
