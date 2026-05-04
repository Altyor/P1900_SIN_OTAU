"""
Secret Management Module - Gestion des secrets TB45
Module de gestion sécurisée des secrets (clés API, mots de passe, tokens)

Les secrets sont stockés dans un fichier binaire chiffré lié à la machine.
Secrets are stored in an encrypted binary file bound to the machine.
"""

from .secret_loader import SecretLoader, SecretError, SecretFileCorruptedError, SecretMachineMismatchError

__version__ = "1.3"

__all__ = [
    'SecretLoader',
    'SecretError',
    'SecretFileCorruptedError',
    'SecretMachineMismatchError'
]
