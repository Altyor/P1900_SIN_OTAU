# OTA FTP App

Outil de bureau (Windows / macOS / Linux) pour gérer le serveur SFTP des produits OTA (`sftp.altyor.solutions:/production/`). Permet aux équipes non-techniques de :

- Visualiser tous les produits déposés (galerie de cartes avec image + paramètres clés)
- Modifier le `config.ini` d'un produit existant (formulaire, pas d'édition INI brute)
- Ajouter un nouveau produit (assistant : nom, image, validation, firmware, filtre de scan)
- Remplacer le firmware d'un produit existant

## Installation (utilisateur)

### Windows

1. Copier `OTA_FTP_App.exe` dans un dossier sur la machine.
2. **Au premier lancement uniquement** : déposer le fichier `secrets.ini` (fourni par l'IT) à côté du `.exe`. L'application va le chiffrer en `secrets.bin` (dans `%APPDATA%\P1900_Production_Manager\`) et supprimer le `.ini` en clair.
3. Lancer l'EXE. Si tout va bien, la galerie des produits s'affiche.

Sur Windows, `secrets.bin` est chiffré via **DPAPI** lié à la machine — non déchiffrable ailleurs.

(Le dossier de stockage `%APPDATA%\P1900_Production_Manager\` conserve son nom historique pour ne pas casser les installations existantes.)

### macOS

1. Lancer `OTA_FTP_App.app`. Au premier lancement, faire un **clic droit → Ouvrir** (Gatekeeper bloque par défaut les apps non signées).
2. Déposer `secrets.ini` à côté du `.app` puis relancer.
3. Le fichier chiffré se trouve dans `~/Library/Application Support/P1900_Production_Manager/secrets.bin`.

Sur macOS la clé de chiffrement est dérivée de l'`IOPlatformUUID` + numéro de série matériel — le fichier ne peut pas être déchiffré sur une autre machine.

### Linux

1. Lancer le binaire `OTA_FTP_App` depuis un terminal ou un raccourci.
2. Déposer `secrets.ini` à côté du binaire puis relancer.
3. Le fichier chiffré se trouve dans `~/.config/P1900_Production_Manager/secrets.bin`.

Sur Linux la clé est dérivée de `/etc/machine-id` + identifiants DMI (UUID système, numéro de série carte mère).

### Si l'app est déplacée vers une autre machine

Le `secrets.bin` ne pourra plus être déchiffré. Supprimer le `.bin` (dans le dossier indiqué dans le message d'erreur) et redéposer un `secrets.ini`.

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

## Build / exécution (développeur)

Prérequis : Python 3.10+ sur la plateforme cible.

### Windows

```cmd
build.bat
```
EXE produit dans `dist\OTA_FTP_App.exe`.

### macOS

```bash
./build.sh
```
Bundle `.app` produit dans `dist/OTA_FTP_App.app`. **Non signé** par défaut — pour distribuer sans clic-droit/Ouvrir, signer avec un Developer ID Apple :

```bash
codesign --force --deep --sign "Developer ID Application: <votre nom>" dist/OTA_FTP_App.app
xcrun notarytool submit dist/OTA_FTP_App.app --wait …
```

### Linux

```bash
./build.sh
```
Binaire ELF dans `dist/OTA_FTP_App`. À placer dans `/opt/ota-ftp-app/` ou similaire.

### Exécution depuis les sources (toutes plateformes)

Aucun build requis pour tester :

```bash
python3 -m venv .venv
# Windows :    .venv\Scripts\activate
# macOS/Linux : source .venv/bin/activate
pip install -r requirements.txt
python -m src.main
```

## Mode test

L'application bascule entre `/production/` (production) et `/deposit/` (test) via le bouton dans l'en-tête. Toujours valider une nouvelle modification dans `/deposit/` avant de l'appliquer en `/production/`.

## Dépannage

- **« Aucun secret chargé »** au démarrage → déposer `secrets.ini` à côté du `.exe` puis relancer.
- **« Erreur secrets : Machine mismatch »** → le `secrets.bin` a été créé sur une autre machine. Le supprimer (Windows : `%APPDATA%\P1900_Production_Manager\` ; macOS : `~/Library/Application Support/P1900_Production_Manager/` ; Linux : `~/.config/P1900_Production_Manager/`) et redéposer un `secrets.ini`.
- **macOS — « impossible d'ouvrir, développeur non identifié »** → clic droit sur le `.app` → Ouvrir (uniquement la première fois). Pour les déploiements à grande échelle, signer avec un Developer ID (voir section Build).
- **« Connexion impossible »** → vérifier que le port 22 est ouvert vers `sftp.altyor.solutions` (firewall corporate). La clé peut aussi être expirée — demander à l'IT de fournir un nouveau `secrets.ini`.
