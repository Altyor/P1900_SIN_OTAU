"""Top-level window. Holds gallery + detail views, switches between them."""
from __future__ import annotations
from typing import Optional

from PyQt6.QtCore import QThread, pyqtSignal
from PyQt6.QtGui import QShortcut, QKeySequence
from PyQt6.QtWidgets import (
    QMainWindow, QStackedWidget, QMessageBox, QStatusBar, QInputDialog, QLineEdit,
    QApplication
)

from ..sftp.product_repo import ProductRepo
from ..util.image_cache import ImageCache
from ..util.settings import Settings
from .product_gallery import ProductGallery
from .product_detail import ProductDetailPage
from .config_editor import ConfigEditorDialog
from .new_product_wizard import NewProductWizard
from .add_variant_wizard import AddVariantWizard
from .replace_fw_dialog import ReplaceFirmwareDialog


class _DeleteWorker(QThread):
    succeeded = pyqtSignal(str, object)   # (name, pn_name|None)
    failed = pyqtSignal(str, str)

    def __init__(self, repo: ProductRepo, cache: ImageCache, name: str, pn_name=None,
                  also_clear_image: bool = True):
        super().__init__()
        self.repo = repo
        self.cache = cache
        self.name = name
        self.pn_name = pn_name
        self.also_clear_image = also_clear_image

    def run(self) -> None:
        try:
            self.repo.delete_product(self.name, self.pn_name)
            # Image cache is keyed by product name. Only purge when the whole
            # product (or its last variant) is gone — i.e. when no PN was
            # specified, or when the parent folder is now empty.
            if self.also_clear_image:
                self.cache.delete(self.name)
            self.succeeded.emit(self.name, self.pn_name)
        except Exception as e:
            self.failed.emit(self.name, str(e))


class MainWindow(QMainWindow):
    def __init__(self, repo: ProductRepo, image_cache: ImageCache,
                  settings: Settings, initial_theme: str):
        super().__init__()
        self.repo = repo
        self.image_cache = image_cache
        self.settings = settings
        self._initial_theme = initial_theme
        self.setWindowTitle("OTA FTP App")
        self.resize(1100, 760)

        self.stack = QStackedWidget()
        self.setCentralWidget(self.stack)

        # Status bar at the bottom — global "what's happening" indicator
        self.status_bar = QStatusBar()
        self.setStatusBar(self.status_bar)
        self.status_bar.showMessage("Prêt")

        self.gallery = ProductGallery(repo, image_cache)
        self.gallery.product_clicked.connect(self._open_product)
        self.gallery.new_product_clicked.connect(self._open_wizard)
        self.gallery.refresh_clicked.connect(self.gallery.refresh)
        self.gallery.root_change_requested.connect(self.gallery.update_root)
        self.gallery.status_changed.connect(self._set_status)
        self.stack.addWidget(self.gallery)

        self.detail = ProductDetailPage(repo, image_cache)
        self.detail.back_clicked.connect(self._show_gallery)
        self.detail.edit_config_clicked.connect(self._open_editor)
        self.detail.replace_fw_clicked.connect(self._open_replace_fw)
        self.detail.delete_clicked.connect(self._open_delete)
        self.detail.add_variant_clicked.connect(self._open_add_variant)
        self.detail.status_changed.connect(self._set_status)
        self.stack.addWidget(self.detail)
        self._delete_worker = None

        self.gallery.refresh()

        # Theme cycling: Ctrl+T flips between available stylesheets and persists
        # the choice to per-user settings so it survives across launches.
        from .. import main as _main
        try:
            self._theme_index = _main.THEMES.index(self._initial_theme)
        except ValueError:
            self._theme_index = 0
        QShortcut(QKeySequence("Ctrl+T"), self, activated=self._cycle_theme)

    def _cycle_theme(self) -> None:
        from .. import main as _main  # avoid circular import at module load
        themes = _main.THEMES
        self._theme_index = (self._theme_index + 1) % len(themes)
        name = themes[self._theme_index]
        qss = _main.load_theme(name)
        app = QApplication.instance()
        if app is not None:
            app.setStyleSheet(qss)
        self.settings.set("theme", name)
        self._set_status(f"Thème : {name}")

    def _set_status(self, msg: str) -> None:
        self.status_bar.showMessage(msg)

    def _show_gallery(self) -> None:
        self.stack.setCurrentWidget(self.gallery)

    def _open_product(self, name: str) -> None:
        self.detail.load(name)
        self.stack.setCurrentWidget(self.detail)

    def _open_wizard(self) -> None:
        """Top-level "Ajouter" entry point. Three sources: brand-new manual
        creation, variant of an existing product, or import from /deposit/."""
        box = QMessageBox(self)
        box.setIcon(QMessageBox.Icon.Question)
        box.setWindowTitle("Type d'ajout")
        box.setText("Que voulez-vous ajouter au serveur ?")
        new_btn = box.addButton("Nouveau produit", QMessageBox.ButtonRole.AcceptRole)
        var_btn = box.addButton("Variante d'un produit existant", QMessageBox.ButtonRole.AcceptRole)
        imp_btn = box.addButton("Importer depuis /deposit/", QMessageBox.ButtonRole.AcceptRole)
        box.addButton("Annuler", QMessageBox.ButtonRole.RejectRole)
        box.exec()
        clicked = box.clickedButton()
        if clicked is new_btn:
            self._launch_new_product_wizard()
        elif clicked is var_btn:
            self._launch_add_variant_flow()
        elif clicked is imp_btn:
            self._launch_import_from_deposit_flow()

    def _launch_new_product_wizard(self) -> None:
        wiz = NewProductWizard(self.repo, self)
        if wiz.exec():
            self.gallery.refresh()

    def _launch_import_from_deposit_flow(self) -> None:
        """Pull a customer-deposited product from /deposit/, pre-fill the
        new-product wizard with its values, let the operator validate / tweak,
        then push to the active root (typically /production/)."""
        deposit_root = "/deposit"
        try:
            names = self.repo.list_products_at(deposit_root)
        except Exception as e:
            QMessageBox.critical(self, "Erreur", f"Lecture de {deposit_root} impossible : {e}")
            return
        if not names:
            QMessageBox.information(self, "Vide", f"Rien à importer sous {deposit_root}.")
            return
        chosen, ok = QInputDialog.getItem(
            self, "Importer depuis /deposit/",
            f"Produits disponibles dans {deposit_root} :",
            names, 0, False,
        )
        if not ok:
            return

        # Stage the deposit content locally so the wizard's existing file-path
        # fields work unchanged.
        import tempfile
        from pathlib import Path
        local_dir = Path(tempfile.mkdtemp(prefix="ota_deposit_"))
        self._set_status(f"Téléchargement de {chosen} depuis {deposit_root}…")
        try:
            payload = self.repo.download_for_import(deposit_root, chosen, local_dir)
        except Exception as e:
            QMessageBox.critical(self, "Import échoué", str(e))
            self._set_status(f"Erreur import : {e}")
            return
        self._set_status(f"Aperçu de {chosen} prêt à valider")

        wiz = NewProductWizard(self.repo, parent=self, initial_payload=payload)
        if not wiz.exec():
            return
        self.gallery.refresh()

        # On success, offer to clear the deposit source — keeps /deposit/ tidy
        # so customers see only what hasn't been processed yet.
        deposit_path = f"{deposit_root}/{chosen}"
        ack = QMessageBox.question(
            self,
            "Vider la source",
            f"Importation réussie.\n\n"
            f"Supprimer aussi la source dans {deposit_path}/ ?",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
            QMessageBox.StandardButton.Yes,
        )
        if ack == QMessageBox.StandardButton.Yes:
            try:
                self.repo.cli.delete_tree(deposit_path)
                self._set_status(f"{deposit_path} supprimé")
            except Exception as e:
                QMessageBox.warning(
                    self, "Suppression échouée",
                    f"Le dossier {deposit_path} n'a pas pu être supprimé :\n\n{e}",
                )
                self._set_status(f"Échec suppression {deposit_path} : {e}")

    def _launch_add_variant_flow(self) -> None:
        # Pick the parent product
        try:
            names = self.repo.list_product_names()
        except Exception as e:
            QMessageBox.critical(self, "Erreur", str(e))
            return
        if not names:
            QMessageBox.information(self, "Aucun produit", "Aucun produit existant sur le serveur.")
            return
        parent, ok = QInputDialog.getItem(
            self, "Produit parent",
            "Produit auquel ajouter une variante :",
            names, 0, False,
        )
        if not ok:
            return

        try:
            existing_pns = self.repo.list_pns(parent)
        except Exception as e:
            QMessageBox.critical(self, "Erreur", str(e))
            return

        # Direct-layout parent → must be converted before a variant can be added.
        # We do this in-line (move the existing FW under a PN sub-folder) so the
        # operator sees one continuous flow.
        if not existing_pns:
            existing_pn, ok = QInputDialog.getText(
                self, "Conversion vers layout PN",
                f"« {parent} » utilise un layout direct (sans sous-dossier PN).\n"
                "Pour permettre des variantes, les fichiers actuels doivent être déplacés\n"
                "sous un dossier PN. Quel PN attribuer à la configuration existante ?",
                QLineEdit.EchoMode.Normal, "",
            )
            if not ok or not existing_pn.strip():
                return
            existing_pn = existing_pn.strip()
            if "/" in existing_pn or "\\" in existing_pn:
                QMessageBox.warning(self, "Nom invalide", "Le PN ne peut pas contenir « / » ou « \\ ».")
                return
            try:
                self._set_status(f"Conversion de {parent} vers layout PN…")
                self.repo.convert_to_pn_layout(parent, existing_pn)
                existing_pns = [existing_pn]
                self._set_status(f"{parent} converti — PN existant : {existing_pn}")
            except Exception as e:
                QMessageBox.critical(self, "Conversion échouée", str(e))
                self._set_status(f"Erreur conversion : {e}")
                return

        wiz = AddVariantWizard(self.repo, parent, existing_pns, self)
        if wiz.exec():
            self.gallery.refresh()

    def _open_editor(self, name: str) -> None:
        # Reuse the detail already loaded by the detail page — no re-fetch.
        detail = self.detail.current_detail()
        if detail is None or detail.name != name:
            QMessageBox.information(self, "Patientez", "Configuration encore en cours de chargement…")
            return
        dlg = ConfigEditorDialog(self.repo, name, detail.validation, detail.config_text,
                                  pn_name=detail.pn_name, parent=self)
        if dlg.exec():
            self.detail.load(name, pn_name=detail.pn_name)

    def _open_delete(self, name: str) -> None:
        # Decide scope: if the product has >1 PN sub-folder, delete only the
        # currently-selected PN. Otherwise, delete the whole product folder.
        pn_count = self.detail.variant_count()
        active_pn = self.detail.current_pn()
        scope_pn = active_pn if pn_count > 1 else None

        # Build the human-readable description that matches the actual delete target.
        if scope_pn:
            target_path = f"{self.repo.root}/{name}/{scope_pn}/"
            label = f"{name} / PN {scope_pn}"
            confirm_text = f"{name}/{scope_pn}"
        else:
            target_path = f"{self.repo.root}/{name}/"
            label = name
            confirm_text = name

        ack = QMessageBox.warning(
            self, "Supprimer",
            f"Cette action supprimera <b>{label}</b> du serveur "
            f"({target_path}) ainsi que tous ses fichiers.<br><br>"
            "Cette opération est irréversible.<br><br>Continuer ?",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.Cancel,
            QMessageBox.StandardButton.Cancel,
        )
        if ack != QMessageBox.StandardButton.Yes:
            return
        typed, ok = QInputDialog.getText(
            self, "Confirmation finale",
            f"Pour confirmer, tapez exactement :\n\n{confirm_text}",
            QLineEdit.EchoMode.Normal, "",
        )
        if not ok:
            return
        if typed.strip() != confirm_text:
            QMessageBox.information(self, "Annulé", "Le texte saisi ne correspond pas. Suppression annulée.")
            return

        self.detail.setEnabled(False)
        self._set_status(f"Suppression de {label} en cours…")
        # Only purge the image cache when the whole product is being removed.
        clear_image = scope_pn is None
        self._delete_worker = _DeleteWorker(
            self.repo, self.image_cache, name, scope_pn, also_clear_image=clear_image
        )
        self._delete_worker.succeeded.connect(self._on_delete_succeeded)
        self._delete_worker.failed.connect(self._on_delete_failed)
        self._delete_worker.start()

    def _on_delete_succeeded(self, name: str, pn_name) -> None:
        self.detail.setEnabled(True)
        label = f"{name} / PN {pn_name}" if pn_name else name
        self._set_status(f"{label} supprimé")
        QMessageBox.information(self, "Supprimé", f"{label} a été retiré du serveur.")
        self._show_gallery()
        self.gallery.refresh()

    def _on_delete_failed(self, name: str, err: str) -> None:
        self.detail.setEnabled(True)
        self._set_status(f"Échec suppression {name} : {err}")
        QMessageBox.critical(self, "Erreur", f"Suppression de {name} échouée :\n\n{err}")

    def _open_add_variant(self, name: str) -> None:
        existing = self.detail.available_pns()
        wiz = AddVariantWizard(self.repo, name, existing, self)
        if wiz.exec():
            # Reload the detail page; the new PN should appear in the dropdown.
            self.detail.load(name, pn_name=self.detail.current_pn())

    def _open_replace_fw(self, name: str, slot: str) -> None:
        detail = self.detail.current_detail()
        if detail is None or detail.name != name:
            QMessageBox.information(self, "Patientez", "Configuration encore en cours de chargement…")
            return
        current = detail.antenna_files if slot == "Antenna" else detail.power_files
        dlg = ReplaceFirmwareDialog(self.repo, name, slot, current,
                                     pn_name=detail.pn_name, parent=self)
        if dlg.exec():
            self.detail.load(name, pn_name=detail.pn_name)
