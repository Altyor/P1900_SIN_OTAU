"""
Crypto Module - Chiffrement/Déchiffrement des secrets
Utilise Windows DPAPI avec entropy basée sur l'ID machine

DPAPI (Data Protection API) est une API Windows qui permet de chiffrer
des données de manière sécurisée, liée à l'utilisateur et/ou à la machine.
"""

import logging
from typing import Optional

from .machine_id import generate_machine_id


logger = logging.getLogger("SecretCrypto")

# Essayer d'importer DPAPI (Windows uniquement)
try:
    import win32crypt
    DPAPI_AVAILABLE = True
    logger.debug("DPAPI available (win32crypt loaded)")
except ImportError:
    DPAPI_AVAILABLE = False
    logger.warning("DPAPI not available (win32crypt not installed)")

# Fallback avec cryptography si DPAPI non disponible
try:
    from cryptography.fernet import Fernet
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.kdf.pbkdf2 import PBKDF2HMAC
    import base64
    CRYPTOGRAPHY_AVAILABLE = True
    logger.debug("Cryptography fallback available")
except ImportError:
    CRYPTOGRAPHY_AVAILABLE = False
    logger.debug("Cryptography not available")


class CryptoError(Exception):
    """Erreur de chiffrement/déchiffrement"""
    pass


def _get_fernet_key(machine_id: bytes) -> bytes:
    """
    Génère une clé Fernet à partir de l'ID machine (fallback non-Windows)

    Args:
        machine_id: ID machine (32 bytes)

    Returns:
        Clé Fernet (base64 encoded)
    """
    if not CRYPTOGRAPHY_AVAILABLE:
        raise CryptoError("cryptography library not available for fallback encryption")

    kdf = PBKDF2HMAC(
        algorithm=hashes.SHA256(),
        length=32,
        salt=b'TB45_SECRET_SALT',
        iterations=100000,
    )
    key = base64.urlsafe_b64encode(kdf.derive(machine_id))
    return key


def encrypt(data: bytes, entropy: Optional[bytes] = None) -> bytes:
    """
    Chiffre des données avec DPAPI (ou fallback cryptography)

    Args:
        data: Données à chiffrer
        entropy: Entropy additionnelle (ID machine par défaut)

    Returns:
        Données chiffrées

    Raises:
        CryptoError: Si le chiffrement échoue
    """
    if entropy is None:
        entropy = generate_machine_id()

    if DPAPI_AVAILABLE:
        try:
            # DPAPI avec entropy = données liées à la machine
            encrypted = win32crypt.CryptProtectData(
                data,
                "TB45_Secrets",  # Description
                entropy,         # Entropy (machine ID)
                None,           # Reserved
                None,           # Prompt struct
                0x01            # CRYPTPROTECT_LOCAL_MACHINE flag
            )
            logger.debug(f"Data encrypted with DPAPI ({len(data)} -> {len(encrypted)} bytes)")
            return encrypted
        except Exception as e:
            raise CryptoError(f"DPAPI encryption failed: {e}")

    elif CRYPTOGRAPHY_AVAILABLE:
        try:
            # Fallback avec Fernet
            key = _get_fernet_key(entropy)
            f = Fernet(key)
            encrypted = f.encrypt(data)
            logger.debug(f"Data encrypted with Fernet fallback ({len(data)} -> {len(encrypted)} bytes)")
            return encrypted
        except Exception as e:
            raise CryptoError(f"Fernet encryption failed: {e}")

    else:
        raise CryptoError("No encryption library available (install pywin32 or cryptography)")


def decrypt(encrypted_data: bytes, entropy: Optional[bytes] = None) -> bytes:
    """
    Déchiffre des données avec DPAPI (ou fallback cryptography)

    Args:
        encrypted_data: Données chiffrées
        entropy: Entropy utilisée lors du chiffrement (ID machine par défaut)

    Returns:
        Données déchiffrées

    Raises:
        CryptoError: Si le déchiffrement échoue (mauvaise machine, données corrompues)
    """
    if entropy is None:
        entropy = generate_machine_id()

    if DPAPI_AVAILABLE:
        try:
            # DPAPI déchiffrement
            _, decrypted = win32crypt.CryptUnprotectData(
                encrypted_data,
                entropy,        # Même entropy que pour le chiffrement
                None,          # Reserved
                None,          # Prompt struct
                0              # Flags
            )
            logger.debug(f"Data decrypted with DPAPI ({len(encrypted_data)} -> {len(decrypted)} bytes)")
            return decrypted
        except Exception as e:
            # DPAPI échoue si mauvaise machine ou données corrompues
            raise CryptoError(f"DPAPI decryption failed (wrong machine or corrupted data): {e}")

    elif CRYPTOGRAPHY_AVAILABLE:
        try:
            # Fallback avec Fernet
            key = _get_fernet_key(entropy)
            f = Fernet(key)
            decrypted = f.decrypt(encrypted_data)
            logger.debug(f"Data decrypted with Fernet fallback ({len(encrypted_data)} -> {len(decrypted)} bytes)")
            return decrypted
        except Exception as e:
            raise CryptoError(f"Fernet decryption failed (wrong machine or corrupted data): {e}")

    else:
        raise CryptoError("No encryption library available (install pywin32 or cryptography)")


def is_encryption_available() -> bool:
    """
    Vérifie si le chiffrement est disponible

    Returns:
        True si DPAPI ou cryptography est disponible
    """
    return DPAPI_AVAILABLE or CRYPTOGRAPHY_AVAILABLE


def get_encryption_method() -> str:
    """
    Retourne la méthode de chiffrement utilisée

    Returns:
        "DPAPI", "Fernet" ou "None"
    """
    if DPAPI_AVAILABLE:
        return "DPAPI"
    elif CRYPTOGRAPHY_AVAILABLE:
        return "Fernet"
    else:
        return "None"


if __name__ == "__main__":
    # Test du module
    logging.basicConfig(level=logging.DEBUG)

    print("=== Crypto Module Test ===")
    print()
    print(f"Encryption available: {is_encryption_available()}")
    print(f"Encryption method: {get_encryption_method()}")
    print()

    if is_encryption_available():
        test_data = b"Secret test data 12345"
        print(f"Original data: {test_data}")

        encrypted = encrypt(test_data)
        print(f"Encrypted ({len(encrypted)} bytes): {encrypted[:50]}...")

        decrypted = decrypt(encrypted)
        print(f"Decrypted: {decrypted}")

        assert decrypted == test_data, "Decryption failed!"
        print()
        print("✅ Encryption/Decryption test passed!")
