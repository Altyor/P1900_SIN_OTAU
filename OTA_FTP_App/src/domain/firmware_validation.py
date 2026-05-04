"""FirmwareValidation — mirrors the Kotlin data class on the Android side.

Reference: mobile/src/main/java/com/siliconlabs/bledemo/features/firmware_browser/domain/FirmwareValidation.kt
"""
from __future__ import annotations
from dataclasses import dataclass
from typing import Optional, Set

from .scan_filter import ScanFilterConfig


ALL_FIELDS: Set[str] = {"post_model", "antenna_version", "power_version"}


@dataclass
class FirmwareValidation:
    pre_model: str = ""
    post_model: str = ""
    antenna_version: str = ""
    power_version: str = ""
    after_antenna: Optional[Set[str]] = None
    after_power: Optional[Set[str]] = None
    after_both: Optional[Set[str]] = None
    scan_filter: Optional[ScanFilterConfig] = None
