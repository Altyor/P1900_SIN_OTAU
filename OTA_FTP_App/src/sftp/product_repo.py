"""High-level repository for /production/ (or /deposit/) products.

Wraps SftpClient with operations the UI cares about: list products, fetch a
product's config + image, save edits, create a new product, replace firmware.

Layout assumed (mirrors the Android side):
    {root}/{product_name}/
        product.png
        FW/
            config.ini
            Antenna/{file}.gbl|.zigbee
            Power/{file}.gbl|.zigbee   (optional)

Some legacy products have an extra PN sub-folder ({root}/{product}/{pn}/FW/...).
This v1 only handles the no-PN layout (matches every product currently in use).
"""
from __future__ import annotations
import io
import logging
import os
import stat as stat_mod
from dataclasses import dataclass
from typing import List, Optional, Tuple


from .client import SftpClient
from ..domain.config_ini import parse, update_field, append_section, remove_section, serialise_new
from ..domain.firmware_validation import FirmwareValidation
from ..domain.scan_filter import ScanFilterConfig


logger = logging.getLogger("ProductRepo")
FIRMWARE_EXTENSIONS = ("gbl", "zigbee")
PRODUCT_IMAGE = "product.png"
CONFIG_FILE = "config.ini"


@dataclass
class FirmwareFile:
    path: str
    name: str
    size: int


@dataclass
class ProductSummary:
    """Lightweight info shown on a card in the gallery."""
    name: str
    has_image: bool
    config_text: Optional[str]  # raw config.ini text; None on read failure
    validation: Optional[FirmwareValidation]
    has_pn_subfolder: bool  # mirrors `pn_name is not None` — kept for back-compat
    pn_name: Optional[str] = None   # populated when the product uses a PN sub-folder layout


@dataclass
class ProductDetail:
    """Full info shown on the detail page."""
    name: str
    image_bytes: Optional[bytes]
    config_text: str
    validation: FirmwareValidation
    antenna_files: List[FirmwareFile]
    power_files: List[FirmwareFile]
    pn_name: Optional[str] = None


class ProductRepo:
    def __init__(self, client: SftpClient, root: str = "/production"):
        self.cli = client
        self.root = root.rstrip("/")

    # ------- list / fetch -------

    def list_products(self) -> List[ProductSummary]:
        """All-at-once variant. Use list_product_names + fetch_summary for streaming."""
        return [self.fetch_summary(n) for n in self.list_product_names()]

    def list_product_names(self) -> List[str]:
        """Cheap listing — one round trip. Returns sorted product folder names."""
        out: List[str] = []
        for entry in sorted(self.cli.listdir(self.root), key=lambda e: e.filename):
            if stat_mod.S_ISDIR(entry.st_mode):
                out.append(entry.filename)
        return out

    def fetch_summary(self, name: str) -> ProductSummary:
        """Read one product's config.ini. ~1 SFTP round trip in the common case
        (direct layout). Falls back to scanning PN sub-folders for legacy
        products like SIN-4-1-21/100004/FW/config.ini."""
        product_path = f"{self.root}/{name}"
        config_text: Optional[str] = None
        validation: Optional[FirmwareValidation] = None
        pn_name: Optional[str] = None
        # Direct layout — try read first; failure tells us this isn't direct.
        try:
            config_text = self.cli.read_text(f"{product_path}/FW/{CONFIG_FILE}")
            validation = parse(config_text)
        except IOError:
            # PN sub-folder layout — pick the first PN that has a readable config.
            try:
                for entry in self.cli.listdir(product_path):
                    if not stat_mod.S_ISDIR(entry.st_mode):
                        continue
                    pn_cfg = f"{product_path}/{entry.filename}/FW/{CONFIG_FILE}"
                    try:
                        config_text = self.cli.read_text(pn_cfg)
                        validation = parse(config_text)
                        pn_name = entry.filename
                        break
                    except IOError:
                        continue
            except IOError:
                pass
        except Exception as e:
            logger.warning(f"Failed to read/parse config for {name}: {e}")
        return ProductSummary(
            name=name, has_image=True, config_text=config_text,
            validation=validation,
            has_pn_subfolder=(pn_name is not None),
            pn_name=pn_name,
        )

    def _resolve_layout(self, name: str, pn_name: Optional[str] = None) -> Tuple[str, Optional[str]]:
        """Return (fw_dir, pn_name). pn_name is None for the direct layout.
        If `pn_name` is given, force that PN regardless of which one is first."""
        product_path = f"{self.root}/{name}"
        if pn_name:
            return f"{product_path}/{pn_name}/FW", pn_name
        direct = f"{product_path}/FW"
        if self.cli.exists(f"{direct}/{CONFIG_FILE}"):
            return direct, None
        try:
            for entry in self.cli.listdir(product_path):
                if not stat_mod.S_ISDIR(entry.st_mode):
                    continue
                pn_fw = f"{product_path}/{entry.filename}/FW"
                if self.cli.exists(f"{pn_fw}/{CONFIG_FILE}"):
                    return pn_fw, entry.filename
        except IOError:
            pass
        return direct, None  # subsequent read will surface the error

    def list_pns(self, name: str) -> List[str]:
        """All PN sub-folders for a product. Empty list ⇒ direct layout (no PNs)."""
        product_path = f"{self.root}/{name}"
        # If direct config exists at top level, this product has no PN sub-folders.
        if self.cli.exists(f"{product_path}/FW/{CONFIG_FILE}"):
            return []
        out: List[str] = []
        try:
            for entry in self.cli.listdir(product_path):
                if not stat_mod.S_ISDIR(entry.st_mode):
                    continue
                if self.cli.exists(f"{product_path}/{entry.filename}/FW/{CONFIG_FILE}"):
                    out.append(entry.filename)
        except IOError:
            pass
        return sorted(out)

    def fetch_config(self, name: str, pn_name: Optional[str] = None) -> ProductDetail:
        """Lighter than fetch_detail — does NOT fetch the image. ~3 SFTP round trips
        (config + 2 FW dir listings). The caller fetches the image separately so
        the UI can show config first and stream the image afterwards.
        Handles both direct and PN-subfolder layouts. When `pn_name` is given,
        forces that variant; otherwise the first one found wins."""
        fw_dir, pn_name = self._resolve_layout(name, pn_name)
        cfg_path = f"{fw_dir}/{CONFIG_FILE}"
        config_text = self.cli.read_text(cfg_path)
        validation = parse(config_text)
        return ProductDetail(
            name=name,
            image_bytes=None,
            config_text=config_text,
            validation=validation,
            antenna_files=self._list_fw(f"{fw_dir}/Antenna"),
            power_files=self._list_fw(f"{fw_dir}/Power"),
            pn_name=pn_name,
        )

    def fetch_image(self, name: str) -> Optional[bytes]:
        """Fetch the product.png. Returns None if missing."""
        path = f"{self.root}/{name}/{PRODUCT_IMAGE}"
        try:
            return self.cli.read_bytes(path)
        except IOError:
            return None

    def fetch_detail(self, name: str) -> ProductDetail:
        """Full fetch — config + image. Kept for callers that don't care about
        progressive loading."""
        d = self.fetch_config(name)
        d.image_bytes = self.fetch_image(name)
        return d

    def _list_fw(self, fw_dir: str) -> List[FirmwareFile]:
        # Try listdir directly — paramiko raises IOError if the dir doesn't exist,
        # which saves us a round trip vs an explicit exists() check.
        try:
            entries = self.cli.listdir(fw_dir)
        except IOError:
            return []
        out: List[FirmwareFile] = []
        for e in entries:
            if stat_mod.S_ISDIR(e.st_mode):
                continue
            ext = e.filename.rsplit(".", 1)[-1].lower() if "." in e.filename else ""
            if ext in FIRMWARE_EXTENSIONS:
                out.append(FirmwareFile(path=f"{fw_dir}/{e.filename}", name=e.filename, size=e.st_size))
        return out

    # ------- edit -------

    def save_config_field(self, name: str, section: str, key: str, value: str,
                          pn_name: Optional[str] = None) -> str:
        """Minimal-edit one key. Returns the new file content."""
        fw_dir, _ = self._resolve_layout(name, pn_name)
        cfg_path = f"{fw_dir}/{CONFIG_FILE}"
        before = self.cli.read_text(cfg_path)
        after = update_field(before, section, key, value)
        if after != before:
            self.cli.write_text(cfg_path, after)
        return after

    def save_config_text(self, name: str, text: str, pn_name: Optional[str] = None) -> None:
        """Overwrite a product's config.ini with the given text. Used by the
        editor dialog after composing the new file via update_field/append_section."""
        fw_dir, _ = self._resolve_layout(name, pn_name)
        self.cli.write_text(f"{fw_dir}/{CONFIG_FILE}", text)

    def save_scan_filter(self, name: str, sf: Optional[ScanFilterConfig],
                          pn_name: Optional[str] = None) -> str:
        """Add / replace / remove the [scan_filter] section.

        sf=None → strip the section entirely (factory default applies).
        """
        fw_dir, _ = self._resolve_layout(name, pn_name)
        cfg_path = f"{fw_dir}/{CONFIG_FILE}"
        before = self.cli.read_text(cfg_path)
        # Strip first, then re-add if needed
        intermediate = remove_section(before, "scan_filter") if "[scan_filter]" in before else before
        if sf is None:
            after = intermediate
        else:
            kv: List[Tuple[str, str]] = [
                ("name", sf.name or ""),
                ("rssi_min", str(int(sf.rssi_min) if sf.rssi_min.is_integer() else sf.rssi_min)),
                ("rssi_max", str(int(sf.rssi_max) if sf.rssi_max.is_integer() else sf.rssi_max)),
            ]
            if sf.ble_formats:
                kv.append(("ble_formats", ",".join(sf.ble_formats)))
            if sf.only_connectable:
                kv.append(("only_connectable", "true"))
            if sf.only_bonded:
                kv.append(("only_bonded", "true"))
            if sf.only_favourite:
                kv.append(("only_favourite", "true"))
            after = append_section(intermediate, "scan_filter", kv)
        if after != before:
            self.cli.write_text(cfg_path, after)
        return after

    # ------- create new product -------

    def create_product(
        self,
        name: str,
        validation: FirmwareValidation,
        local_image_path: str,
        local_antenna_path: Optional[str],
        local_power_path: Optional[str],
    ) -> None:
        """Create a fresh product folder. Refuses if it already exists."""
        product_path = f"{self.root}/{name}"
        if self.cli.exists(product_path):
            raise FileExistsError(f"{product_path} already exists on SFTP")
        self.cli.mkdirs(product_path)
        self.cli.mkdirs(f"{product_path}/FW")
        if local_antenna_path:
            self.cli.mkdirs(f"{product_path}/FW/Antenna")
        if local_power_path:
            self.cli.mkdirs(f"{product_path}/FW/Power")

        # Image
        self.cli.upload(local_image_path, f"{product_path}/{PRODUCT_IMAGE}")
        # Config
        self.cli.write_text(f"{product_path}/FW/{CONFIG_FILE}", serialise_new(validation))
        # FW
        if local_antenna_path:
            fname = os.path.basename(local_antenna_path)
            self.cli.upload(local_antenna_path, f"{product_path}/FW/Antenna/{fname}")
        if local_power_path:
            fname = os.path.basename(local_power_path)
            self.cli.upload(local_power_path, f"{product_path}/FW/Power/{fname}")
        logger.info(f"Created product {product_path}")

    # ------- import from deposit -------

    def list_products_at(self, root: str) -> List[str]:
        """List product folders at an arbitrary SFTP root (e.g. /deposit/)."""
        out: List[str] = []
        try:
            for entry in sorted(self.cli.listdir(root), key=lambda e: e.filename):
                if stat_mod.S_ISDIR(entry.st_mode):
                    out.append(entry.filename)
        except IOError:
            pass
        return out

    def download_for_import(self, source_root: str, name: str, local_dir):
        """Download a product folder from `source_root/{name}/` (e.g. /deposit/)
        into `local_dir`. Returns a dict the wizard can consume to pre-fill its
        pages — same shape the wizard would build from an operator's manual picks."""
        from pathlib import Path
        local_dir = Path(local_dir)
        local_dir.mkdir(parents=True, exist_ok=True)
        src = f"{source_root.rstrip('/')}/{name}"
        if not self.cli.exists(src):
            raise FileNotFoundError(f"{src} introuvable sur le serveur")

        payload = {
            "name": name,
            "image_path": None,
            "config_text": "",
            "validation": None,
            "antenna_path": None,
            "power_path": None,
        }

        # Image (top-level product.png)
        img_remote = f"{src}/{PRODUCT_IMAGE}"
        if self.cli.exists(img_remote):
            img_local = local_dir / PRODUCT_IMAGE
            self.cli.download(img_remote, str(img_local))
            payload["image_path"] = str(img_local)

        # Resolve direct vs PN-layout — for now we only support importing
        # direct-layout deposits. PN layouts at deposit time are unusual.
        cfg_remote = f"{src}/FW/{CONFIG_FILE}"
        if not self.cli.exists(cfg_remote):
            raise ValueError(
                f"{src}/FW/{CONFIG_FILE} introuvable. Cet outil supporte uniquement "
                "les dépôts au layout direct (pas de sous-dossier PN)."
            )
        config_text = self.cli.read_text(cfg_remote)
        payload["config_text"] = config_text
        payload["validation"] = parse(config_text)

        # Firmware files: download the first .gbl/.zigbee in each slot
        for slot in ("Antenna", "Power"):
            slot_remote = f"{src}/FW/{slot}"
            if not self.cli.exists(slot_remote):
                continue
            for entry in self.cli.listdir(slot_remote):
                if stat_mod.S_ISDIR(entry.st_mode):
                    continue
                ext = entry.filename.rsplit(".", 1)[-1].lower() if "." in entry.filename else ""
                if ext not in FIRMWARE_EXTENSIONS:
                    continue
                slot_local_dir = local_dir / slot
                slot_local_dir.mkdir(exist_ok=True)
                local_file = slot_local_dir / entry.filename
                self.cli.download(f"{slot_remote}/{entry.filename}", str(local_file))
                payload[f"{slot.lower()}_path"] = str(local_file)
                break  # first file wins, matches the tablet's behaviour

        return payload

    # ------- direct → multi-PN conversion -------

    def convert_to_pn_layout(self, name: str, existing_pn: str) -> None:
        """One-shot rename: /production/{name}/FW/ → /production/{name}/{existing_pn}/FW/.

        Used when an operator wants to add a variant to a product that was
        originally created with the direct layout. After this runs, the product
        has exactly one PN sub-folder (containing what used to be at FW/), and
        new variants can be added alongside it.

        Refuses if:
          - the product doesn't exist
          - it doesn't actually have a direct-layout FW/
          - the target PN sub-folder already exists (would clobber)
        """
        if not name or "/" in name or not existing_pn or "/" in existing_pn:
            raise ValueError(f"Bad name/pn: {name!r} / {existing_pn!r}")
        product_path = f"{self.root}/{name}"
        direct_fw = f"{product_path}/FW"
        target_pn = f"{product_path}/{existing_pn}"
        target_fw = f"{target_pn}/FW"

        if not self.cli.exists(product_path):
            raise FileNotFoundError(f"{product_path} n'existe pas")
        if not self.cli.exists(direct_fw):
            raise ValueError(f"{direct_fw} introuvable — le produit n'utilise pas un layout direct")
        if self.cli.exists(target_pn):
            raise FileExistsError(f"{target_pn} existe déjà — choisissez un autre PN")

        # Create the new PN sub-folder, then move FW into it via SFTP rename
        # (atomic on the server; no upload-then-delete window of risk).
        logger.info(f"Converting {name} to PN layout under {existing_pn}")
        self.cli.mkdirs(target_pn)
        self.cli.rename(direct_fw, target_fw)

    # ------- add variant -------

    def add_variant(self, name: str, pn_name: str,
                     validation: FirmwareValidation,
                     local_antenna_path: Optional[str],
                     local_power_path: Optional[str]) -> None:
        """Add a new PN sub-folder to an existing product. Refuses if:
          - the product doesn't exist
          - the product uses direct layout (would need a restructure first)
          - the requested PN already exists"""
        if not name or not pn_name or "/" in name or "/" in pn_name:
            raise ValueError(f"Bad name/pn: {name!r} / {pn_name!r}")
        product_path = f"{self.root}/{name}"
        if not self.cli.exists(product_path):
            raise FileNotFoundError(f"{product_path} does not exist on SFTP")
        if self.cli.exists(f"{product_path}/FW/{CONFIG_FILE}"):
            raise ValueError(
                f"{name} utilise un layout direct ({product_path}/FW/) — "
                "convertissez-le d'abord en layout PN avant d'ajouter une variante."
            )
        pn_path = f"{product_path}/{pn_name}"
        if self.cli.exists(pn_path):
            raise FileExistsError(f"{pn_path} existe déjà")

        self.cli.mkdirs(pn_path)
        self.cli.mkdirs(f"{pn_path}/FW")
        if local_antenna_path:
            self.cli.mkdirs(f"{pn_path}/FW/Antenna")
        if local_power_path:
            self.cli.mkdirs(f"{pn_path}/FW/Power")

        self.cli.write_text(f"{pn_path}/FW/{CONFIG_FILE}", serialise_new(validation))
        if local_antenna_path:
            fname = os.path.basename(local_antenna_path)
            self.cli.upload(local_antenna_path, f"{pn_path}/FW/Antenna/{fname}")
        if local_power_path:
            fname = os.path.basename(local_power_path)
            self.cli.upload(local_power_path, f"{pn_path}/FW/Power/{fname}")
        logger.info(f"Added variant {pn_path}")

    # ------- delete -------

    def delete_product(self, name: str, pn_name: Optional[str] = None) -> None:
        """Recursively delete a product or one of its variants.

        - `pn_name=None` → deletes /production/{name}/ wholesale.
        - `pn_name=X`    → deletes /production/{name}/{X}/. If that was the
                            only PN sub-folder, the now-empty parent product
                            folder is removed too (no orphan dirs left behind)."""
        if not name or "/" in name:
            raise ValueError(f"Refusing to delete suspicious name: {name!r}")
        if pn_name is not None and (not pn_name or "/" in pn_name):
            raise ValueError(f"Refusing to delete suspicious pn: {pn_name!r}")

        product_path = f"{self.root}/{name}"
        target = f"{product_path}/{pn_name}" if pn_name else product_path
        if not self.cli.exists(target):
            raise FileNotFoundError(f"{target} does not exist on SFTP")

        logger.info(f"Deleting {target}")
        self.cli.delete_tree(target)

        # If we just deleted a PN and the parent has nothing left, drop it too.
        if pn_name is not None:
            try:
                if not list(self.cli.listdir(product_path)):
                    logger.info(f"Pruning empty parent {product_path}")
                    self.cli.rmdir(product_path)
            except IOError:
                pass

    # ------- replace firmware -------

    def replace_firmware(self, name: str, slot: str, local_path: str,
                          pn_name: Optional[str] = None) -> None:
        """slot ∈ {'Antenna', 'Power'}. Uploads new file then removes any pre-existing
        files in the same slot. Upload-before-delete so a failure can't strand the product."""
        if slot not in ("Antenna", "Power"):
            raise ValueError(f"slot must be 'Antenna' or 'Power', got {slot!r}")
        product_fw, _ = self._resolve_layout(name, pn_name)
        fw_dir = f"{product_fw}/{slot}"
        self.cli.mkdirs(fw_dir)
        new_name = os.path.basename(local_path)
        new_remote = f"{fw_dir}/{new_name}"
        # Pick a temp name if a file with that exact name already exists, so we
        # don't clobber it before confirming the upload succeeded.
        tmp_remote = f"{fw_dir}/.upload.{new_name}"
        self.cli.upload(local_path, tmp_remote)
        # Remove existing FW in this slot (every .gbl/.zigbee, except the temp upload)
        for entry in self.cli.listdir(fw_dir):
            if stat_mod.S_ISDIR(entry.st_mode):
                continue
            if entry.filename == os.path.basename(tmp_remote):
                continue
            ext = entry.filename.rsplit(".", 1)[-1].lower() if "." in entry.filename else ""
            if ext in FIRMWARE_EXTENSIONS:
                self.cli.remove(f"{fw_dir}/{entry.filename}")
        # Final rename: temp → real
        if self.cli.exists(new_remote):
            self.cli.remove(new_remote)
        self.cli.rename(tmp_remote, new_remote)
        logger.info(f"Replaced firmware {fw_dir} → {new_name}")
