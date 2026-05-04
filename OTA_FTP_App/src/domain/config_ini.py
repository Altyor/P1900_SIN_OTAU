"""Multi-section INI parser for product config.ini files on the SFTP server.

Behavioural twin of `SftpRepositoryImpl.parseConfigIni` on the Android side.
Whatever this writes, the tablet must read identically.

Round-trip strategy: when editing an existing file, do **minimal-edit** rewrites
(`update_field`, `set_field`, `remove_section`) that preserve every untouched
line and its whitespace. Full re-serialisation is available for new files.
"""
from __future__ import annotations
from typing import Dict, List, Optional, Set, Tuple

from .firmware_validation import FirmwareValidation
from .scan_filter import ScanFilterConfig


def _parse_sections(text: str) -> Dict[str, Dict[str, str]]:
    sections: Dict[str, Dict[str, str]] = {}
    current = ""
    for line in text.splitlines():
        s = line.strip()
        if not s or s.startswith("#") or s.startswith(";"):
            continue
        if s.startswith("[") and s.endswith("]"):
            current = s[1:-1].strip()
            sections.setdefault(current, {})
            continue
        if "=" in s:
            key, _, value = s.partition("=")
            sections.setdefault(current, {})[key.strip()] = value.strip()
    return sections


def _parse_checks(section: Optional[Dict[str, str]]) -> Optional[Set[str]]:
    """Missing section → None (caller falls back to ALL_FIELDS).
    Present-but-empty `check=` → empty set."""
    if section is None:
        return None
    raw = section.get("check")
    if raw is None:
        return set()
    items = [s.strip() for s in raw.split(",") if s.strip()]
    return set(items)


def _parse_scan_filter(section: Optional[Dict[str, str]]) -> Optional[ScanFilterConfig]:
    if section is None:
        return None

    def b(key: str, default: bool) -> bool:
        v = section.get(key, "").strip().lower()
        return v in ("true", "1", "yes") if v else default

    def f(key: str, default: float) -> float:
        try:
            return float(section.get(key, "").strip())
        except (TypeError, ValueError):
            return default

    formats_raw = section.get("ble_formats", "")
    formats = [t.strip().upper() for t in formats_raw.split(",") if t.strip()]

    name = section.get("name", "").strip()
    return ScanFilterConfig(
        name=name if name else None,
        rssi_min=f("rssi_min", -40.0),
        rssi_max=f("rssi_max", 0.0),
        ble_formats=formats,
        only_connectable=b("only_connectable", False),
        only_bonded=b("only_bonded", False),
        only_favourite=b("only_favourite", False),
    )


def parse(text: str) -> FirmwareValidation:
    """Parse a config.ini text blob into a FirmwareValidation."""
    sections = _parse_sections(text)
    v = sections.get("validation") or sections.get("firmware") or {}
    return FirmwareValidation(
        pre_model=v.get("pre_model", ""),
        post_model=v.get("post_model", ""),
        antenna_version=v.get("antenna_version", ""),
        power_version=v.get("power_version", ""),
        after_antenna=_parse_checks(sections.get("after_antenna")),
        after_power=_parse_checks(sections.get("after_power")),
        after_both=_parse_checks(sections.get("after_both")),
        scan_filter=_parse_scan_filter(sections.get("scan_filter")),
    )


# ----- minimal-edit operations ----------------------------------------------

def _split_eol(line: str) -> Tuple[str, str]:
    for suffix in ("\r\n", "\n", "\r"):
        if line.endswith(suffix):
            return line[: -len(suffix)], suffix
    return line, ""


def update_field(text: str, section: str, key: str, new_value: str) -> str:
    """Replace `key=...` inside `[section]`. Preserve whitespace + unrelated lines.
    Adds the key (and section if missing) when not present."""
    out: List[str] = []
    in_section = False
    found_section = False
    found_key = False
    section_end_idx: Optional[int] = None  # index in `out` where to insert if key missing

    for raw_line in text.splitlines(keepends=True):
        body, eol = _split_eol(raw_line)
        s = body.strip()

        if s.startswith("[") and s.endswith("]"):
            # Closing the previous section?
            if in_section and not found_key:
                section_end_idx = len(out)
            in_section = (s[1:-1].strip() == section)
            if in_section:
                found_section = True
            out.append(raw_line)
            continue

        if in_section and not found_key and "=" in s:
            k, _, _v = s.partition("=")
            if k.strip() == key:
                eq_pos = body.index("=")
                out.append(body[: eq_pos + 1] + new_value + eol)
                found_key = True
                continue

        out.append(raw_line)

    # Trailing section never closed by another header
    if in_section and not found_key and section_end_idx is None:
        section_end_idx = len(out)

    if not found_key:
        if not found_section:
            # Append the section + key at the end
            sep = "" if not text or text.endswith("\n") else "\n"
            return text + f"{sep}\n[{section}]\n{key}={new_value}\n"
        # Insert before the next section (or at end of file)
        new_line = f"{key}={new_value}\n"
        out.insert(section_end_idx if section_end_idx is not None else len(out), new_line)

    return "".join(out)


def remove_section(text: str, section: str) -> str:
    """Strip `[section]` and all its key=value lines until the next header / EOF."""
    out: List[str] = []
    in_target = False
    for raw_line in text.splitlines(keepends=True):
        body, _ = _split_eol(raw_line)
        s = body.strip()
        if s.startswith("[") and s.endswith("]"):
            in_target = (s[1:-1].strip() == section)
            if in_target:
                continue
        if in_target:
            continue
        out.append(raw_line)
    return "".join(out)


def append_section(text: str, section: str, kv_pairs: List[Tuple[str, str]]) -> str:
    """Append a brand-new `[section]\\nk1=v1\\nk2=v2\\n` block to the end."""
    sep = "" if not text or text.endswith("\n") else "\n"
    body = f"\n[{section}]\n" + "".join(f"{k}={v}\n" for k, v in kv_pairs)
    return text + sep + body


# ----- full-write helpers (for new files) ------------------------------------

def serialise_new(validation: FirmwareValidation) -> str:
    """Render a fresh config.ini for a new product. Used by the wizard."""
    lines = [
        "[validation]",
        f"pre_model={validation.pre_model}",
        f"post_model={validation.post_model}",
        f"antenna_version={validation.antenna_version}",
        f"power_version={validation.power_version}",
        "",
        "[after_antenna]",
        "check=antenna_version",
    ]
    sf = validation.scan_filter
    if sf is not None:
        lines += [
            "",
            "[scan_filter]",
            f"name={sf.name or ''}",
            f"rssi_min={int(sf.rssi_min) if sf.rssi_min.is_integer() else sf.rssi_min}",
            f"rssi_max={int(sf.rssi_max) if sf.rssi_max.is_integer() else sf.rssi_max}",
        ]
        if sf.ble_formats:
            lines.append(f"ble_formats={','.join(sf.ble_formats)}")
        if sf.only_connectable:
            lines.append("only_connectable=true")
        if sf.only_bonded:
            lines.append("only_bonded=true")
        if sf.only_favourite:
            lines.append("only_favourite=true")
    return "\n".join(lines) + "\n"
