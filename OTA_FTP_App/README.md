# OTA FTP App

Outil Windows pour gérer le serveur SFTP des produits OTA (`sftp.altyor.solutions:/production/`). Permet aux équipes non-techniques de :

- Visualiser tous les produits déposés (galerie de cartes avec image + paramètres clés)
- Modifier le `config.ini` d'un produit existant (formulaire, pas d'édition INI brute)
- Ajouter un nouveau produit (assistant : nom, image, validation, firmware, filtre de scan)
- Remplacer le firmware d'un produit existant

## Installation (utilisateur)

1. Copier `OTA_FTP_App.exe` dans un dossier sur la machine.
2. **Au premier lancement uniquement** : déposer le fichier `secrets.ini` (fourni par l'IT) à côté du `.exe`. L'application va le chiffrer en `secrets.bin` (dans `%APPDATA%\P1900_Production_Manager\`) et supprimer le `.ini` en clair.
3. Lancer l'EXE. Si tout va bien, la galerie des produits s'affiche.

Le `secrets.bin` est chiffré via DPAPI Windows lié à la machine — il ne peut pas être déchiffré sur une autre machine. Si l'EXE est déplacé ailleurs, il faut redéposer un nouveau `secrets.ini`.

(Le dossier de stockage `%APPDATA%\P1900_Production_Manager\` conserve son nom historique pour ne pas casser les installations existantes.)

## Format de `secrets.ini`

```ini
[SFTP]
host=sftp.altyor.solutions
port=22
username=P1900-production-manager
private_key=-----BEGIN OPENSSH PRIVATE KEY-----
…contenu de la clé privée…
-----END OPENSSH PRIVATE KEY-----
key_passphrase=la_passphrase
root_dir=/production
```

## Build (développeur)

Prérequis : Python 3.10+ sur Windows.

```cmd
build.bat
```

Le `.exe` est produit dans `dist\OTA_FTP_App.exe`.

## Mode test

L'application bascule entre `/production/` (production) et `/deposit/` (test) via le bouton dans l'en-tête. Toujours valider une nouvelle modification dans `/deposit/` avant de l'appliquer en `/production/`.

## Dépannage

- **« Aucun secret chargé »** au démarrage → déposer `secrets.ini` à côté du `.exe` puis relancer.
- **« Erreur secrets : Machine mismatch »** → le `secrets.bin` a été créé sur une autre machine. Le supprimer (dans le dossier indiqué dans le message d'erreur) et redéposer un `secrets.ini`.
- **« Connexion impossible »** → vérifier que le port 22 est ouvert vers `sftp.altyor.solutions` (firewall corporate). La clé peut aussi être expirée — demander à l'IT de fournir un nouveau `secrets.ini`.
