"""
Machine ID Generator - Générateur d'identifiant machine unique
Génère une empreinte unique basée sur le hardware (hors périphériques USB)

IDs utilisés:
- Windows: UUID système, numéro de série carte mère, ID processeur, numéro de série BIOS
- Linux: /etc/machine-id, product_uuid (si accessible)
"""

import hashlib
import subprocess
import logging
import platform
from pathlib import Path
from typing import List, Optional


logger = logging.getLogger("MachineID")

# Détection du système d'exploitation
IS_WINDOWS = platform.system() == "Windows"
IS_LINUX = platform.system() == "Linux"


def _run_wmic_command(command: str) -> Optional[str]:
    """
    Exécute une commande WMIC et retourne le résultat (Windows uniquement)

    Args:
        command: Commande WMIC complète

    Returns:
        Résultat de la commande ou None si erreur
    """
    if not IS_WINDOWS:
        return None

    try:
        result = subprocess.run(
            command,
            capture_output=True,
            text=True,
            shell=True,
            timeout=10,
            creationflags=subprocess.CREATE_NO_WINDOW if hasattr(subprocess, 'CREATE_NO_WINDOW') else 0
        )
        if result.returncode == 0:
            lines = result.stdout.strip().split('\n')
            # La première ligne est l'en-tête, on prend la dernière valeur non vide
            for line in reversed(lines):
                value = line.strip()
                if value and value.lower() not in ['uuid', 'serialnumber', 'processorid']:
                    return value
        return None
    except Exception as e:
        logger.debug(f"WMIC command failed: {command} - {e}")
        return None


def _run_powershell_command(ps_command: str) -> Optional[str]:
    """
    Exécute une commande PowerShell et retourne le résultat (Windows uniquement)
    Alternative à WMIC pour Windows 10/11

    Args:
        ps_command: Commande PowerShell

    Returns:
        Résultat de la commande ou None si erreur
    """
    if not IS_WINDOWS:
        return None

    try:
        result = subprocess.run(
            ["powershell", "-NoProfile", "-Command", ps_command],
            capture_output=True,
            text=True,
            timeout=10,
            creationflags=subprocess.CREATE_NO_WINDOW if hasattr(subprocess, 'CREATE_NO_WINDOW') else 0
        )
        if result.returncode == 0:
            value = result.stdout.strip()
            if value:
                return value
        return None
    except Exception as e:
        logger.debug(f"PowerShell command failed: {ps_command} - {e}")
        return None


# =============================================================================
# Fonctions Linux
# =============================================================================

def _read_file_content(file_path: str) -> Optional[str]:
    """
    Lit le contenu d'un fichier système (Linux)

    Args:
        file_path: Chemin du fichier

    Returns:
        Contenu du fichier ou None si erreur/non trouvé
    """
    try:
        path = Path(file_path)
        if path.exists() and path.is_file():
            content = path.read_text().strip()
            if content:
                return content
    except Exception as e:
        logger.debug(f"Failed to read {file_path}: {e}")
    return None


def get_linux_machine_id() -> Optional[str]:
    """
    Récupère le machine-id Linux (/etc/machine-id ou /var/lib/dbus/machine-id)

    Returns:
        Machine ID (32 caractères hex) ou None
    """
    if not IS_LINUX:
        return None

    # Essayer /etc/machine-id d'abord (standard systemd)
    machine_id = _read_file_content("/etc/machine-id")
    if machine_id:
        logger.debug(f"Linux machine-id from /etc/machine-id: {machine_id}")
        return machine_id

    # Fallback: /var/lib/dbus/machine-id (ancien systèmes)
    machine_id = _read_file_content("/var/lib/dbus/machine-id")
    if machine_id:
        logger.debug(f"Linux machine-id from /var/lib/dbus/machine-id: {machine_id}")
        return machine_id

    return None


def get_linux_product_uuid() -> Optional[str]:
    """
    Récupère le product_uuid du système (si accessible sans root)

    Returns:
        UUID système ou None
    """
    if not IS_LINUX:
        return None

    # Essayer de lire le product_uuid (peut nécessiter root)
    uuid = _read_file_content("/sys/class/dmi/id/product_uuid")
    if uuid:
        logger.debug(f"Linux product_uuid: {uuid}")
        return uuid

    return None


def get_linux_board_serial() -> Optional[str]:
    """
    Récupère le numéro de série de la carte mère (si accessible sans root)

    Returns:
        Numéro de série ou None
    """
    if not IS_LINUX:
        return None

    serial = _read_file_content("/sys/class/dmi/id/board_serial")
    if serial and serial.lower() not in ['to be filled by o.e.m.', 'default string', 'none']:
        logger.debug(f"Linux board serial: {serial}")
        return serial

    return None


def get_system_uuid() -> Optional[str]:
    """
    Récupère l'UUID système (BIOS/UEFI)
    Essaie WMIC puis PowerShell
    """
    # Essayer WMIC d'abord
    result = _run_wmic_command("wmic csproduct get uuid")
    if result:
        return result
    # Fallback PowerShell
    return _run_powershell_command("(Get-CimInstance -ClassName Win32_ComputerSystemProduct).UUID")


def get_baseboard_serial() -> Optional[str]:
    """
    Récupère le numéro de série de la carte mère
    Essaie WMIC puis PowerShell
    """
    result = _run_wmic_command("wmic baseboard get serialnumber")
    if result:
        return result
    return _run_powershell_command("(Get-CimInstance -ClassName Win32_BaseBoard).SerialNumber")


def get_processor_id() -> Optional[str]:
    """
    Récupère l'ID du processeur
    Essaie WMIC puis PowerShell
    """
    result = _run_wmic_command("wmic cpu get processorid")
    if result:
        return result
    return _run_powershell_command("(Get-CimInstance -ClassName Win32_Processor).ProcessorId")


def get_bios_serial() -> Optional[str]:
    """
    Récupère le numéro de série du BIOS
    Essaie WMIC puis PowerShell
    """
    result = _run_wmic_command("wmic bios get serialnumber")
    if result:
        return result
    return _run_powershell_command("(Get-CimInstance -ClassName Win32_BIOS).SerialNumber")


def get_all_hardware_ids() -> List[str]:
    """
    Récupère tous les IDs hardware disponibles (Windows et Linux)

    Returns:
        Liste des IDs hardware (peut être vide si aucun ID disponible)
    """
    ids = []

    if IS_WINDOWS:
        # === Windows ===
        system_uuid = get_system_uuid()
        if system_uuid:
            ids.append(f"UUID:{system_uuid}")
            logger.debug(f"System UUID: {system_uuid}")

        baseboard = get_baseboard_serial()
        if baseboard and baseboard.lower() not in ['to be filled by o.e.m.', 'default string', 'none']:
            ids.append(f"BOARD:{baseboard}")
            logger.debug(f"Baseboard serial: {baseboard}")

        processor = get_processor_id()
        if processor:
            ids.append(f"CPU:{processor}")
            logger.debug(f"Processor ID: {processor}")

        bios = get_bios_serial()
        if bios and bios.lower() not in ['to be filled by o.e.m.', 'default string', 'none']:
            ids.append(f"BIOS:{bios}")
            logger.debug(f"BIOS serial: {bios}")

    elif IS_LINUX:
        # === Linux ===
        # Machine ID (toujours disponible, ne nécessite pas root)
        machine_id = get_linux_machine_id()
        if machine_id:
            ids.append(f"MACHINE_ID:{machine_id}")
            logger.debug(f"Linux machine-id: {machine_id}")

        # Product UUID (peut nécessiter root)
        product_uuid = get_linux_product_uuid()
        if product_uuid:
            ids.append(f"UUID:{product_uuid}")
            logger.debug(f"Linux product UUID: {product_uuid}")

        # Board serial (peut nécessiter root)
        board_serial = get_linux_board_serial()
        if board_serial:
            ids.append(f"BOARD:{board_serial}")
            logger.debug(f"Linux board serial: {board_serial}")

    else:
        # === Autre OS (macOS, etc.) ===
        logger.warning(f"Unsupported OS: {platform.system()}")

    return ids


def generate_machine_id() -> bytes:
    """
    Génère un identifiant unique de la machine basé sur le hardware

    L'ID est un hash SHA-256 de tous les identifiants hardware combinés.
    Si aucun ID hardware n'est disponible, utilise le nom de la machine comme fallback.

    Returns:
        bytes: Hash SHA-256 (32 bytes) unique à cette machine
    """
    ids = get_all_hardware_ids()

    if not ids:
        # Fallback: utiliser le nom de la machine
        import os
        import socket

        if IS_WINDOWS:
            computer_name = os.environ.get('COMPUTERNAME', 'UNKNOWN')
        else:
            # Linux/Unix: utiliser le hostname
            computer_name = socket.gethostname() or os.environ.get('HOSTNAME', 'UNKNOWN')

        logger.warning(f"No hardware IDs found, using computer name as fallback: {computer_name}")
        ids = [f"HOSTNAME:{computer_name}"]

    # Combiner tous les IDs avec un sel
    combined = "|".join(sorted(ids)) + "|TB45_SALT_2024"

    # Générer le hash
    machine_hash = hashlib.sha256(combined.encode('utf-8')).digest()

    logger.info(f"Machine ID generated from {len(ids)} hardware identifiers")
    logger.debug(f"Hardware IDs used: {ids}")

    return machine_hash


def get_machine_id_hex() -> str:
    """
    Retourne l'ID machine en format hexadécimal (pour debug/affichage)

    Returns:
        str: Hash en hexadécimal (64 caractères)
    """
    return generate_machine_id().hex()


if __name__ == "__main__":
    # Test du module
    logging.basicConfig(level=logging.DEBUG)

    print("=== Machine ID Generator Test ===")
    print()
    print(f"Operating System: {platform.system()}")
    print(f"IS_WINDOWS: {IS_WINDOWS}")
    print(f"IS_LINUX: {IS_LINUX}")
    print()

    print("Hardware IDs found:")
    for hw_id in get_all_hardware_ids():
        print(f"  - {hw_id}")

    print()
    print(f"Machine ID (hex): {get_machine_id_hex()}")
