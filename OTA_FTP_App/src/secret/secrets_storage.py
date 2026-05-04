"""
Secrets Storage - Lecture/Écriture du fichier binaire chiffré

Format du fichier secrets.bin:
┌──────────────────────────────────────────────────────────┐
│ HEADER (45 bytes)                                        │
├──────────────────────────────────────────────────────────┤
│ [0-7]   MAGIC: "TB45SEC\0" (8 bytes)                     │
│ [8]     VERSION: 1 (1 byte)                              │
│ [9-40]  MACHINE_HASH: SHA256 des IDs machine (32 bytes)  │
│ [41-44] DATA_LENGTH: Taille données chiffrées (4 bytes)  │
├──────────────────────────────────────────────────────────┤
│ ENCRYPTED DATA (variable)                                │
│ Données JSON chiffrées par DPAPI + entropy machine       │
└──────────────────────────────────────────────────────────┘
"""

import json
import struct
import hashlib
import logging
from pathlib import Path
from typing import Dict, Any, Optional

from .machine_id import generate_machine_id
from .crypto import encrypt, decrypt, CryptoError


logger = logging.getLogger("SecretsStorage")


# Constantes du format de fichier
MAGIC = b'TB45SEC\x00'  # 8 bytes
VERSION = 1             # Version actuelle du format
HEADER_SIZE = 45        # 8 + 1 + 32 + 4 bytes


class StorageError(Exception):
    """Erreur générique de stockage"""
    pass


class FileCorruptedError(StorageError):
    """Fichier secrets.bin corrompu"""
    pass


class MachineMismatchError(StorageError):
    """Le fichier a été créé sur une autre machine"""
    pass


def _get_machine_hash() -> bytes:
    """Retourne le hash de l'ID machine (32 bytes)"""
    machine_id = generate_machine_id()
    return hashlib.sha256(machine_id).digest()


def save_secrets(secrets: Dict[str, Dict[str, str]], file_path: Path) -> None:
    """
    Sauvegarde les secrets dans un fichier binaire chiffré

    Args:
        secrets: Dictionnaire {section: {key: value}}
        file_path: Chemin du fichier secrets.bin

    Raises:
        StorageError: Si la sauvegarde échoue
    """
    logger.info(f"Saving secrets to {file_path}")

    try:
        # Sérialiser en JSON
        json_data = json.dumps(secrets, ensure_ascii=False).encode('utf-8')
        logger.debug(f"JSON data size: {len(json_data)} bytes")

        # Chiffrer avec l'ID machine comme entropy
        machine_id = generate_machine_id()
        encrypted_data = encrypt(json_data, entropy=machine_id)
        logger.debug(f"Encrypted data size: {len(encrypted_data)} bytes")

        # Créer le dossier parent si nécessaire
        file_path.parent.mkdir(parents=True, exist_ok=True)

        # Écrire le fichier binaire
        with open(file_path, 'wb') as f:
            # Header
            f.write(MAGIC)                                    # 8 bytes - signature
            f.write(struct.pack('B', VERSION))                # 1 byte - version
            f.write(_get_machine_hash())                      # 32 bytes - hash machine
            f.write(struct.pack('>I', len(encrypted_data)))   # 4 bytes - taille données

            # Données chiffrées
            f.write(encrypted_data)

        logger.info(f"Secrets saved successfully ({len(secrets)} sections)")

    except CryptoError as e:
        raise StorageError(f"Encryption failed: {e}")
    except Exception as e:
        raise StorageError(f"Failed to save secrets: {e}")


def load_secrets(file_path: Path) -> Dict[str, Dict[str, str]]:
    """
    Charge les secrets depuis un fichier binaire chiffré

    Args:
        file_path: Chemin du fichier secrets.bin

    Returns:
        Dictionnaire {section: {key: value}}

    Raises:
        FileNotFoundError: Fichier non trouvé
        FileCorruptedError: Fichier corrompu ou invalide
        MachineMismatchError: Fichier créé sur une autre machine
        StorageError: Autre erreur
    """
    logger.info(f"Loading secrets from {file_path}")

    if not file_path.exists():
        raise FileNotFoundError(f"Secrets file not found: {file_path}")

    try:
        with open(file_path, 'rb') as f:
            # Lire et vérifier le magic
            magic = f.read(8)
            if magic != MAGIC:
                raise FileCorruptedError(
                    f"Invalid file signature. Expected {MAGIC}, got {magic}"
                )

            # Lire la version
            version = struct.unpack('B', f.read(1))[0]
            if version > VERSION:
                raise FileCorruptedError(
                    f"Unsupported file version: {version} (max supported: {VERSION})"
                )

            # Lire et vérifier le hash machine
            stored_hash = f.read(32)
            current_hash = _get_machine_hash()

            if stored_hash != current_hash:
                logger.error("Machine hash mismatch - file created on different machine")
                logger.debug(f"Stored hash: {stored_hash.hex()}")
                logger.debug(f"Current hash: {current_hash.hex()}")
                raise MachineMismatchError(
                    "This secrets file was created on a different machine. "
                    "Secrets cannot be decrypted on this machine."
                )

            # Lire la taille des données
            data_length = struct.unpack('>I', f.read(4))[0]

            # Lire les données chiffrées
            encrypted_data = f.read(data_length)

            if len(encrypted_data) != data_length:
                raise FileCorruptedError(
                    f"Truncated file. Expected {data_length} bytes, got {len(encrypted_data)}"
                )

        # Déchiffrer
        machine_id = generate_machine_id()
        decrypted_data = decrypt(encrypted_data, entropy=machine_id)

        # Parser le JSON
        secrets = json.loads(decrypted_data.decode('utf-8'))

        logger.info(f"Secrets loaded successfully ({len(secrets)} sections)")
        return secrets

    except (FileCorruptedError, MachineMismatchError):
        raise
    except CryptoError as e:
        raise FileCorruptedError(f"Decryption failed - file may be corrupted: {e}")
    except json.JSONDecodeError as e:
        raise FileCorruptedError(f"Invalid JSON data after decryption: {e}")
    except Exception as e:
        raise StorageError(f"Failed to load secrets: {e}")


def file_exists(file_path: Path) -> bool:
    """Vérifie si le fichier secrets.bin existe"""
    return file_path.exists()


def validate_file(file_path: Path) -> Optional[str]:
    """
    Valide un fichier secrets.bin sans le déchiffrer

    Args:
        file_path: Chemin du fichier

    Returns:
        None si valide, message d'erreur sinon
    """
    if not file_path.exists():
        return "File not found"

    try:
        with open(file_path, 'rb') as f:
            # Vérifier le magic
            magic = f.read(8)
            if magic != MAGIC:
                return "Invalid file signature"

            # Vérifier la version
            version = struct.unpack('B', f.read(1))[0]
            if version > VERSION:
                return f"Unsupported version: {version}"

            # Vérifier le hash machine
            stored_hash = f.read(32)
            current_hash = _get_machine_hash()

            if stored_hash != current_hash:
                return "File created on different machine"

            # Vérifier la taille
            data_length = struct.unpack('>I', f.read(4))[0]
            encrypted_data = f.read()

            if len(encrypted_data) != data_length:
                return f"Truncated file (expected {data_length}, got {len(encrypted_data)})"

        return None  # Valide

    except Exception as e:
        return f"Validation error: {e}"


def delete_file(file_path: Path) -> bool:
    """
    Supprime le fichier secrets.bin de manière sécurisée

    Args:
        file_path: Chemin du fichier

    Returns:
        True si supprimé, False si n'existait pas
    """
    if not file_path.exists():
        return False

    try:
        # Écraser le contenu avant suppression (sécurité)
        file_size = file_path.stat().st_size
        with open(file_path, 'wb') as f:
            f.write(b'\x00' * file_size)

        # Supprimer
        file_path.unlink()
        logger.info(f"Secrets file deleted: {file_path}")
        return True

    except Exception as e:
        logger.error(f"Failed to delete secrets file: {e}")
        return False


if __name__ == "__main__":
    # Test du module
    logging.basicConfig(level=logging.DEBUG)

    print("=== Secrets Storage Test ===")
    print()

    test_file = Path("test_secrets.bin")

    # Test sauvegarde
    test_secrets = {
        "Azure": {
            "client_id": "test-client-id",
            "client_secret": "super-secret-value"
        },
        "Database": {
            "password": "db-password-123"
        }
    }

    print("Saving secrets...")
    save_secrets(test_secrets, test_file)
    print(f"✅ Saved to {test_file}")

    # Test chargement
    print()
    print("Loading secrets...")
    loaded = load_secrets(test_file)
    print(f"✅ Loaded: {loaded}")

    # Vérification
    assert loaded == test_secrets, "Data mismatch!"
    print()
    print("✅ All tests passed!")

    # Nettoyage
    delete_file(test_file)
    print(f"✅ Test file deleted")
