"""
SecretLoader - Gestionnaire principal des secrets TB45

Fonctionnalités:
- Charge les secrets depuis un fichier binaire chiffré (secrets.bin)
- Import automatique depuis un fichier INI en clair (secrets.ini) puis suppression
- Gestion des erreurs (fichier corrompu, mauvaise machine, etc.)
- API simple: get(section, key), set(section, key, value)

Usage:
    from secret import SecretLoader

    loader = SecretLoader(config_dir)
    loader.load()

    # Récupérer un secret
    api_key = loader.get("Azure", "client_secret")

    # Vérifier si les secrets sont disponibles
    if loader.is_loaded:
        ...
"""

import os
import sys
import logging
from pathlib import Path
from configparser import ConfigParser
from typing import Dict, Optional, List

from .secrets_storage import (
    save_secrets,
    load_secrets,
    file_exists,
    validate_file,
    delete_file,
    StorageError,
    FileCorruptedError,
    MachineMismatchError
)


logger = logging.getLogger("SecretLoader")


# Exceptions publiques
class SecretError(Exception):
    """Erreur générique des secrets"""
    pass


class SecretFileCorruptedError(SecretError):
    """Fichier secrets.bin corrompu"""
    pass


class SecretMachineMismatchError(SecretError):
    """Le fichier secrets.bin a été créé sur une autre machine"""
    pass


class SecretLoader:
    """
    Gestionnaire de secrets TB45

    Charge les secrets depuis:
    1. secrets.ini (fichier INI en clair) - importé puis supprimé
    2. secrets.bin (fichier binaire chiffré) - stockage permanent

    Les secrets sont organisés par sections:
        [Azure]
        client_secret=xxx

        [Database]
        password=yyy
    """

    # Noms des fichiers par défaut
    DEFAULT_FILENAME = "secrets"

    def __init__(self, config_dir: Optional[Path] = None, bin_dir: Optional[Path] = None, filename: Optional[str] = None):
        """
        Initialise le SecretLoader

        Args:
            config_dir: Dossier de configuration (contient secrets.ini)
                       Par défaut: dossier de l'EXE ou dossier courant
            bin_dir: Dossier pour le fichier secrets.bin (optionnel)
                    Si fourni: utilise ce dossier pour secrets.bin
                    Si None: utilise le dossier de l'EXE
            filename: Nom du fichier sans extension (optionnel)
                     Ex: "secrets", "my_app_secrets"
                     Par défaut: "secrets" -> secrets.ini et secrets.bin
        """
        # Déterminer le nom de fichier (sans extension)
        if filename and filename.strip():
            base_filename = filename.strip()
        else:
            base_filename = self.DEFAULT_FILENAME

        self.ini_filename = f"{base_filename}.ini"
        self.bin_filename = f"{base_filename}.bin"
        # Déterminer le dossier de l'EXE (utilisé par défaut)
        if getattr(sys, 'frozen', False):
            # Mode EXE (PyInstaller)
            exe_dir = Path(sys.executable).parent
        else:
            # Mode développement
            exe_dir = Path.cwd()

        # config_dir : pour secrets.ini
        if config_dir is None:
            config_dir = exe_dir

        self.config_dir = Path(config_dir)
        self.ini_path = self.config_dir / self.ini_filename

        # bin_dir : dossier pour secrets.bin
        if bin_dir is not None:
            self.bin_path = Path(bin_dir) / self.bin_filename
        else:
            # Par défaut : dossier de l'EXE
            self.bin_path = exe_dir / self.bin_filename

        # État interne
        self._secrets: Dict[str, Dict[str, str]] = {}
        self._loaded = False
        self._error: Optional[str] = None

        logger.debug(f"SecretLoader initialized with config_dir: {self.config_dir}")

    @property
    def is_loaded(self) -> bool:
        """True si les secrets ont été chargés avec succès"""
        return self._loaded

    @property
    def has_secrets(self) -> bool:
        """True si des secrets sont disponibles"""
        return self._loaded and len(self._secrets) > 0

    @property
    def error(self) -> Optional[str]:
        """Message d'erreur si le chargement a échoué"""
        return self._error

    def load(self) -> bool:
        """
        Charge les secrets

        Workflow:
        1. Si secrets.ini existe → importer dans secrets.bin puis supprimer
        2. Si secrets.bin existe → charger
        3. Sinon → aucun secret (pas d'erreur)

        Returns:
            True si chargement réussi (ou aucun fichier), False si erreur

        Note:
            En cas d'erreur (fichier corrompu, mauvaise machine), les tests
            ne pourront pas s'exécuter. L'erreur est accessible via self.error
        """
        self._error = None
        self._loaded = False
        self._secrets = {}

        try:
            # Étape 1: Importer secrets.ini si présent
            if self.ini_path.exists():
                logger.info(f"Found {self.ini_filename}, importing...")
                self._import_ini_file()

            # Étape 2: Charger secrets.bin si présent
            if self.bin_path.exists():
                logger.info(f"Loading {self.bin_filename}...")
                self._secrets = load_secrets(self.bin_path)
                self._loaded = True
                logger.info(f"Secrets loaded: {len(self._secrets)} sections")
            else:
                # Aucun fichier = OK, juste pas de secrets
                logger.info("No secrets file found (this is OK if secrets are not needed)")
                self._loaded = True

            return True

        except MachineMismatchError as e:
            self._error = str(e)
            logger.error(f"Machine mismatch: {e}")
            raise SecretMachineMismatchError(str(e))

        except FileCorruptedError as e:
            self._error = str(e)
            logger.error(f"File corrupted: {e}")
            raise SecretFileCorruptedError(str(e))

        except StorageError as e:
            self._error = str(e)
            logger.error(f"Storage error: {e}")
            raise SecretError(str(e))

        except Exception as e:
            self._error = f"Unexpected error: {e}"
            logger.exception(f"Unexpected error loading secrets: {e}")
            raise SecretError(self._error)

    def _import_ini_file(self) -> None:
        """
        Importe les secrets depuis le fichier INI en clair
        puis supprime le fichier INI

        Format du fichier INI:
            [SectionName]
            key=value

        Supports multi-line values (e.g., SSH keys):
            [SFTP]
            ssh_key=-----BEGIN OPENSSH PRIVATE KEY-----
            base64content...
            -----END OPENSSH PRIVATE KEY-----

        Exemple:
            [Azure]
            client_secret=xxx
            tenant_id=yyy

            [Database]
            password=zzz
        """
        logger.info(f"Importing secrets from {self.ini_path}")

        try:
            # Custom parser to handle multi-line values (e.g., SSH keys)
            new_secrets: Dict[str, Dict[str, str]] = {}
            current_section = None
            current_key = None
            current_value_lines = []

            with open(self.ini_path, 'r', encoding='utf-8') as f:
                for line in f:
                    line_stripped = line.strip()

                    # Skip empty lines and comments (unless we're in a multi-line value)
                    if not line_stripped or line_stripped.startswith('#') or line_stripped.startswith(';'):
                        if current_key and current_value_lines:
                            # Preserve empty lines within multi-line values
                            current_value_lines.append('')
                        continue

                    # Section header
                    if line_stripped.startswith('[') and line_stripped.endswith(']'):
                        # Save previous key if exists
                        if current_section and current_key:
                            value = '\n'.join(current_value_lines).strip()
                            new_secrets[current_section][current_key] = value
                            current_key = None
                            current_value_lines = []

                        current_section = line_stripped[1:-1]
                        new_secrets[current_section] = {}
                        continue

                    # Key=value line (but not if we're inside a PEM block that hasn't ended)
                    has_pem_begin = current_value_lines and any('-----BEGIN' in v for v in current_value_lines)
                    has_pem_end = current_value_lines and any('-----END' in v for v in current_value_lines)
                    in_pem_block = has_pem_begin and not has_pem_end
                    if '=' in line_stripped and current_section and not in_pem_block:
                        # Save previous key if exists
                        if current_key:
                            value = '\n'.join(current_value_lines).strip()
                            new_secrets[current_section][current_key] = value

                        key, _, value = line_stripped.partition('=')
                        current_key = key.strip().lower()
                        current_value_lines = [value.strip()]
                        continue

                    # Continuation of multi-line value
                    if current_key:
                        current_value_lines.append(line.rstrip())

            # Save last key
            if current_section and current_key:
                value = '\n'.join(current_value_lines).strip()
                new_secrets[current_section][current_key] = value

            # Clean up values (remove quotes)
            for section in new_secrets:
                for key in new_secrets[section]:
                    value = new_secrets[section][key]
                    if value.startswith('"') and value.endswith('"'):
                        new_secrets[section][key] = value[1:-1]
                    elif value.startswith("'") and value.endswith("'"):
                        new_secrets[section][key] = value[1:-1]

            if not new_secrets:
                logger.warning(f"No secrets found in {self.ini_path}")
                return

            logger.info(f"Found {len(new_secrets)} sections in INI file")

            # Charger les secrets existants si le fichier bin existe
            existing_secrets: Dict[str, Dict[str, str]] = {}
            if self.bin_path.exists():
                try:
                    existing_secrets = load_secrets(self.bin_path)
                    logger.info(f"Merging with {len(existing_secrets)} existing sections")
                except Exception as e:
                    logger.warning(f"Could not load existing secrets, will overwrite: {e}")

            # Fusionner (nouveaux secrets écrasent les anciens)
            for section, values in new_secrets.items():
                if section not in existing_secrets:
                    existing_secrets[section] = {}
                existing_secrets[section].update(values)

            # Sauvegarder dans le fichier binaire
            save_secrets(existing_secrets, self.bin_path)
            logger.info(f"Secrets saved to {self.bin_path}")

            # Supprimer le fichier INI de manière sécurisée
            self._secure_delete_ini()

        except Exception as e:
            logger.error(f"Failed to import INI file: {e}")
            raise StorageError(f"Failed to import secrets from INI: {e}")

    def _secure_delete_ini(self) -> None:
        """Supprime le fichier INI de manière sécurisée (écrase avant suppression)"""
        try:
            if self.ini_path.exists():
                # Écraser le contenu avec des zéros
                file_size = self.ini_path.stat().st_size
                with open(self.ini_path, 'wb') as f:
                    f.write(b'\x00' * file_size)

                # Supprimer le fichier
                self.ini_path.unlink()
                logger.info(f"INI file securely deleted: {self.ini_path}")

        except Exception as e:
            logger.error(f"Failed to delete INI file: {e}")
            # Ne pas lever d'exception, les secrets sont déjà importés

    def get(self, section: str, key: str, default: Optional[str] = None) -> Optional[str]:
        """
        Récupère un secret

        Args:
            section: Nom de la section (ex: "Azure")
            key: Nom de la clé (ex: "client_secret")
            default: Valeur par défaut si non trouvé

        Returns:
            Valeur du secret ou default si non trouvé

        Raises:
            SecretError: Si les secrets n'ont pas été chargés
        """
        if not self._loaded:
            raise SecretError("Secrets not loaded. Call load() first.")

        return self._secrets.get(section, {}).get(key, default)

    def get_section(self, section: str) -> Dict[str, str]:
        """
        Récupère tous les secrets d'une section

        Args:
            section: Nom de la section

        Returns:
            Dictionnaire {key: value} ou {} si section non trouvée
        """
        if not self._loaded:
            raise SecretError("Secrets not loaded. Call load() first.")

        return self._secrets.get(section, {}).copy()

    def has(self, section: str, key: Optional[str] = None) -> bool:
        """
        Vérifie si un secret existe

        Args:
            section: Nom de la section
            key: Nom de la clé (optionnel, si None vérifie juste la section)

        Returns:
            True si le secret existe
        """
        if not self._loaded:
            return False

        if section not in self._secrets:
            return False

        if key is None:
            return True

        return key in self._secrets[section]

    def set(self, section: str, key: str, value: str) -> None:
        """
        Définit un secret (et sauvegarde immédiatement)

        Args:
            section: Nom de la section
            key: Nom de la clé
            value: Valeur du secret
        """
        if section not in self._secrets:
            self._secrets[section] = {}

        self._secrets[section][key] = value

        # Sauvegarder immédiatement
        save_secrets(self._secrets, self.bin_path)
        logger.info(f"Secret set and saved: [{section}] {key}")

    def delete(self, section: str, key: Optional[str] = None) -> bool:
        """
        Supprime un secret ou une section entière

        Args:
            section: Nom de la section
            key: Nom de la clé (si None, supprime toute la section)

        Returns:
            True si supprimé, False si non trouvé
        """
        if section not in self._secrets:
            return False

        if key is None:
            # Supprimer toute la section
            del self._secrets[section]
        else:
            if key not in self._secrets[section]:
                return False
            del self._secrets[section][key]

            # Supprimer la section si vide
            if not self._secrets[section]:
                del self._secrets[section]

        # Sauvegarder
        save_secrets(self._secrets, self.bin_path)
        logger.info(f"Secret deleted: [{section}] {key if key else '(entire section)'}")
        return True

    def list_sections(self) -> List[str]:
        """
        Liste les sections disponibles

        Returns:
            Liste des noms de sections
        """
        if not self._loaded:
            return []
        return list(self._secrets.keys())

    def list_keys(self, section: str) -> List[str]:
        """
        Liste les clés d'une section

        Args:
            section: Nom de la section

        Returns:
            Liste des noms de clés
        """
        if not self._loaded or section not in self._secrets:
            return []
        return list(self._secrets[section].keys())

    def clear_all(self) -> None:
        """
        Supprime tous les secrets et le fichier binaire

        ⚠️ ATTENTION: Cette opération est irréversible
        """
        self._secrets = {}
        delete_file(self.bin_path)
        logger.warning("All secrets cleared!")

    def get_status(self) -> Dict[str, any]:
        """
        Retourne le statut du SecretLoader (pour debug/monitoring)

        Returns:
            Dictionnaire avec les informations de statut
        """
        return {
            "loaded": self._loaded,
            "has_secrets": self.has_secrets,
            "error": self._error,
            "sections_count": len(self._secrets) if self._loaded else 0,
            "sections": self.list_sections(),
            "bin_file_exists": self.bin_path.exists(),
            "ini_file_exists": self.ini_path.exists(),
            "config_dir": str(self.config_dir)
        }


if __name__ == "__main__":
    # Test du module
    logging.basicConfig(level=logging.DEBUG)

    print("=== SecretLoader Test ===")
    print()

    # Créer un dossier de test
    test_dir = Path("test_secrets_dir")
    test_dir.mkdir(exist_ok=True)

    # Créer un fichier INI de test
    ini_content = """
[Azure]
client_secret=test-azure-secret
tenant_id=test-tenant-id

[Database]
password=test-db-password
host=localhost
"""
    ini_file = test_dir / "secrets.ini"
    ini_file.write_text(ini_content)
    print(f"Created test INI file: {ini_file}")

    # Tester le loader
    loader = SecretLoader(test_dir)
    loader.load()

    print()
    print(f"Status: {loader.get_status()}")
    print()

    # Vérifier l'import
    assert not ini_file.exists(), "INI file should be deleted after import"
    print("✅ INI file deleted after import")

    assert loader.has("Azure", "client_secret"), "Azure secret should exist"
    print(f"✅ Azure client_secret: {loader.get('Azure', 'client_secret')}")

    assert loader.has("Database", "password"), "Database secret should exist"
    print(f"✅ Database password: {loader.get('Database', 'password')}")

    # Test reload (depuis le fichier binaire)
    print()
    print("Testing reload from binary file...")
    loader2 = SecretLoader(test_dir)
    loader2.load()

    assert loader2.get("Azure", "client_secret") == "test-azure-secret"
    print("✅ Secrets correctly reloaded from binary file")

    # Nettoyage
    print()
    print("Cleaning up...")
    loader2.clear_all()
    test_dir.rmdir()
    print("✅ Test directory cleaned")

    print()
    print("=== All tests passed! ===")
