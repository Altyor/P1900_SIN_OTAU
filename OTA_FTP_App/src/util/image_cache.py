"""On-disk cache for product.png thumbnails.

Cache key: (product_name, server_size). Subsequent launches read from disk if the
remote file size hasn't changed — no SFTP transfer needed. When the size changes
(operator replaces the image), the old cache entry is silently discarded on next put.
"""
from __future__ import annotations
import logging
from pathlib import Path
from typing import Optional


logger = logging.getLogger("ImageCache")


def _safe_filename(name: str) -> str:
    return "".join(c if c.isalnum() or c in "-_" else "_" for c in name)


class ImageCache:
    def __init__(self, cache_dir: Path):
        self.dir = Path(cache_dir)
        self.dir.mkdir(parents=True, exist_ok=True)

    def _path(self, name: str, size: int) -> Path:
        return self.dir / f"{_safe_filename(name)}_{size}.png"

    def get(self, name: str, size: int) -> Optional[bytes]:
        p = self._path(name, size)
        if p.exists():
            try:
                return p.read_bytes()
            except OSError as e:
                logger.warning(f"Failed to read cached image for {name}: {e}")
                return None
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

    def clear(self) -> None:
        for p in self.dir.glob("*.png"):
            try:
                p.unlink()
            except OSError:
                pass

    def delete(self, name: str) -> None:
        """Drop every cached entry for this product (across all sizes)."""
        prefix = f"{_safe_filename(name)}_"
        for p in self.dir.glob(f"{prefix}*.png"):
            try:
                p.unlink()
            except OSError:
                pass
