"""ScanFilterConfig — mirrors the Kotlin data class on the Android side.

Reference: mobile/src/main/java/com/siliconlabs/bledemo/features/firmware_browser/domain/ScanFilterConfig.kt
"""
from __future__ import annotations
from dataclasses import dataclass, field
from typing import List, Optional


@dataclass
class ScanFilterConfig:
    name: Optional[str]
    rssi_min: float
    rssi_max: float
    ble_formats: List[str] = field(default_factory=list)  # UNSPECIFIED / ALT_BEACON / I_BEACON / EDDYSTONE
    only_connectable: bool = False
    only_bonded: bool = False
    only_favourite: bool = False

    @classmethod
    def factory(cls) -> "ScanFilterConfig":
        return cls(name="SIN", rssi_min=-40.0, rssi_max=0.0)
