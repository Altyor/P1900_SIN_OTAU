"""Tiny JSON-backed per-user preferences (theme, future tabs, etc.).

Storage: %APPDATA%\\P1900_Production_Manager\\settings.json on Windows,
~/.config/P1900_Production_Manager/settings.json elsewhere. The path is
chosen by the caller; this class just owns the read/write.
"""
from __future__ import annotations
import json
import logging
from pathlib import Path
from typing import Any


logger = logging.getLogger("Settings")


class Settings:
    def __init__(self, path: Path):
        self.path = Path(path)
        self._data: dict = {}
        if self.path.exists():
            try:
                self._data = json.loads(self.path.read_text(encoding="utf-8"))
            except Exception as e:
                logger.warning(f"settings.json unreadable, ignoring: {e}")
                self._data = {}

    def get(self, key: str, default: Any = None) -> Any:
        return self._data.get(key, default)

    def set(self, key: str, value: Any) -> None:
        self._data[key] = value
        self._save()

    def _save(self) -> None:
        try:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self.path.write_text(json.dumps(self._data, indent=2), encoding="utf-8")
        except Exception as e:
            logger.warning(f"Failed to save settings: {e}")
