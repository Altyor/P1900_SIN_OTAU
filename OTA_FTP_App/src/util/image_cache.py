"""On-disk cache for product.png thumbnails.

Cache key: (product_name, server_size). Subsequent launches read from disk if the
remote file size hasn't changed — no SFTP transfer needed. When the size changes
(operator replaces the image), the old cache entry is silently discarded on next put.

Because the on-disk filename is a *lossy* slug of the product name (non-alphanumeric
chars collapse to `_`), a small `index.json` sidecar records the original name for
each entry so callers (e.g. the new-product wizard's "reuse a cached image" picker)
can show the real product name rather than the mangled slug.
"""
from __future__ import annotations
import json
import logging
from pathlib import Path
from typing import Dict, List, Optional, Tuple


logger = logging.getLogger("ImageCache")

_INDEX_FILE = "index.json"


def _safe_filename(name: str) -> str:
    return "".join(c if c.isalnum() or c in "-_" else "_" for c in name)


class ImageCache:
    def __init__(self, cache_dir: Path):
        self.dir = Path(cache_dir)
        self.dir.mkdir(parents=True, exist_ok=True)

    def _path(self, name: str, size: int) -> Path:
        return self.dir / f"{_safe_filename(name)}_{size}.png"

    # ------- name index (slug → original product name) -------

    def _index_path(self) -> Path:
        return self.dir / _INDEX_FILE

    def _read_index(self) -> Dict[str, str]:
        p = self._index_path()
        if not p.exists():
            return {}
        try:
            data = json.loads(p.read_text(encoding="utf-8"))
            return data if isinstance(data, dict) else {}
        except (OSError, ValueError) as e:
            logger.warning(f"Failed to read image-cache index: {e}")
            return {}

    def _write_index(self, index: Dict[str, str]) -> None:
        try:
            self._index_path().write_text(
                json.dumps(index, ensure_ascii=False, indent=0), encoding="utf-8"
            )
        except OSError as e:
            logger.warning(f"Failed to write image-cache index: {e}")

    def get(self, name: str, size: int) -> Optional[bytes]:
        p = self._path(name, size)
        if p.exists():
            try:
                data = p.read_bytes()
            except OSError as e:
                logger.warning(f"Failed to read cached image for {name}: {e}")
                return None
            # Backfill the name index for entries cached before it existed, so the
            # "reuse a cached image" picker shows real names rather than slugs.
            index = self._read_index()
            if index.get(_safe_filename(name)) != name:
                index[_safe_filename(name)] = name
                self._write_index(index)
            return data
        return None

    def put(self, name: str, size: int, data: bytes) -> None:
        p = self._path(name, size)
        # Drop any stale entries for this product (different sizes)
        prefix = f"{_safe_filename(name)}_"
        for old in self.dir.glob(f"{prefix}*.png"):
            if old != p:
                try:
                    old.unlink()
                except OSError:
                    pass
        try:
            p.write_bytes(data)
        except OSError as e:
            logger.warning(f"Failed to write image cache for {name}: {e}")
            return
        # Record the real product name so the slug can be reversed for display.
        index = self._read_index()
        if index.get(_safe_filename(name)) != name:
            index[_safe_filename(name)] = name
            self._write_index(index)

    def list_cached(self) -> List[Tuple[str, Path]]:
        """Every cached product image as (display_name, path), one entry per
        product, sorted by display name. `display_name` is the real product name
        when known (from the index), otherwise the on-disk slug as a fallback."""
        index = self._read_index()
        by_slug: Dict[str, Path] = {}
        for p in self.dir.glob("*.png"):
            # Filenames are "{slug}_{size}.png"; strip the trailing "_{size}".
            stem = p.stem
            slug = stem.rsplit("_", 1)[0] if "_" in stem else stem
            # One image per product is enough; any size will do.
            by_slug.setdefault(slug, p)
        out = [(index.get(slug, slug), path) for slug, path in by_slug.items()]
        out.sort(key=lambda t: t[0].lower())
        return out

    def rename(self, old: str, new: str) -> None:
        """Move every cached entry for `old` to `new` (keeping the size suffix)
        and update the name index. Used when a product folder is renamed on the
        server, so the reused image stays cached instead of being re-downloaded."""
        old_slug = _safe_filename(old)
        new_slug = _safe_filename(new)
        if old_slug == new_slug:
            return
        for p in self.dir.glob(f"{old_slug}_*.png"):
            stem = p.stem
            # Only exact-slug matches — "SIN-4-2-20" must not swallow
            # "SIN-4-2-20_PRO_…" entries that share the prefix.
            slug = stem.rsplit("_", 1)[0] if "_" in stem else stem
            if slug != old_slug:
                continue
            size_part = stem[len(old_slug) + 1:]
            target = self.dir / f"{new_slug}_{size_part}.png"
            try:
                p.replace(target)
            except OSError as e:
                logger.warning(f"Failed to move cached image {p} → {target}: {e}")
        index = self._read_index()
        index.pop(old_slug, None)
        index[new_slug] = new
        self._write_index(index)

    def clear(self) -> None:
        for p in self.dir.glob("*.png"):
            try:
                p.unlink()
            except OSError:
                pass
        self._write_index({})

    def delete(self, name: str) -> None:
        """Drop every cached entry for this product (across all sizes)."""
        prefix = f"{_safe_filename(name)}_"
        for p in self.dir.glob(f"{prefix}*.png"):
            try:
                p.unlink()
            except OSError:
                pass
        index = self._read_index()
        if index.pop(_safe_filename(name), None) is not None:
            self._write_index(index)
