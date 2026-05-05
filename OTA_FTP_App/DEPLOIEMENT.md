# Guide de déploiement — OTA FTP App

Ce document est destiné à la personne qui installe l'application sur le poste d'un opérateur. Il couvre Windows uniquement (la version macOS/Linux est documentée dans le `README.md`).

L'application supporte **deux modes de déploiement** :
- **Local** : un EXE par poste, copié dans `C:\OTA\` ou similaire.
- **Réseau partagé** : un seul EXE sur un partage réseau (`\\serveur\share\OTA\`), lancé par plusieurs utilisateurs Windows. Les credentials et préférences restent isolés par utilisateur.

Les deux modes utilisent la même procédure pour les credentials : `secrets.ini` est déposé dans le dossier `%APPDATA%` de chaque utilisateur, **pas à côté du `.exe`**.

---

## Ce qu'il vous faut

1. **`OTA_FTP_App.exe`** — l'exécutable Windows fourni dans `dist\OTA_FTP_App.exe` après build (~37 Mo, autonome — aucune dépendance Python à installer côté poste).
2. **`secrets.ini`** — fichier de credentials SFTP fourni par l'IT. Format :

   ```ini
   [SFTP]
   host=sftp.altyor.solutions
   port=22
   username=P1900-production-manager
   private_key=-----BEGIN OPENSSH PRIVATE KEY-----
   ...contenu de la clé privée...
   -----END OPENSSH PRIVATE KEY-----
   key_passphrase=la_passphrase
   root_dir=/production
   ```

   ⚠️ Ce fichier contient la clé privée en clair — à transmettre par un canal sûr (clé USB, gestionnaire de mots de passe interne) et **jamais** par email.

---

## Procédure d'installation

### Étape A — Placer l'EXE

**Mode local (un EXE par poste)** : copier `OTA_FTP_App.exe` dans un dossier permanent du poste, p.ex. `C:\OTA\`. Éviter le bureau de l'utilisateur (peut être déplacé par mégarde).

**Mode réseau partagé (un EXE pour plusieurs utilisateurs)** : copier `OTA_FTP_App.exe` sur un partage réseau accessible aux utilisateurs concernés, p.ex. `\\serveur\share\OTA\OTA_FTP_App.exe`. **Aucun fichier de credentials n'est posé à côté du `.exe`.** Une mise à jour de l'EXE sur le partage profite à tous les utilisateurs au prochain lancement.

### Étape B — Déposer `secrets.ini` (une fois par utilisateur Windows)

Pour chaque session Windows qui lancera l'application, déposer `secrets.ini` dans le dossier APPDATA de cet utilisateur :

```
%APPDATA%\P1900_Production_Manager\
```

Chemin complet typique :
```
C:\Users\<utilisateur>\AppData\Roaming\P1900_Production_Manager\secrets.ini
```

Si le dossier n'existe pas encore, le créer.

**Ligne PowerShell pratique** (à exécuter dans la session Windows de l'utilisateur cible) :
```powershell
$dst = "$env:APPDATA\P1900_Production_Manager"
New-Item -ItemType Directory -Force -Path $dst | Out-Null
Copy-Item -Path "<chemin\vers\secrets.ini>" -Destination $dst
```

### Étape C — Premier lancement

Double-cliquer sur `OTA_FTP_App.exe` (ou son raccourci). Au démarrage, l'application :
- Lit `secrets.ini` depuis `%APPDATA%\P1900_Production_Manager\`
- Le chiffre via DPAPI Windows (lié à la machine + au compte Windows)
- Écrit `secrets.bin` dans le même dossier
- **Supprime** `secrets.ini` (le fichier en clair n'existe plus après le premier lancement)

Si la galerie des produits s'affiche : le déploiement est réussi.
Si une erreur apparaît : voir la section **Dépannage** ci-dessous.

### Raccourci sur le bureau (optionnel)

Clic droit sur `OTA_FTP_App.exe` (qu'il soit local ou sur le partage réseau) → Envoyer vers → Bureau (créer un raccourci).

### Mise à jour vers une nouvelle version

1. Fermer l'application si elle est ouverte (sur tous les postes en mode réseau).
2. Remplacer l'`OTA_FTP_App.exe` :
   - **Mode local** : dans `C:\OTA\` du poste concerné.
   - **Mode réseau** : sur le partage. Une seule mise à jour suffit pour tous les utilisateurs.
3. Relancer. Le `secrets.bin` de chaque utilisateur reste valide — **pas besoin de redéposer `secrets.ini`** tant que le compte Windows et la machine sont les mêmes.

---

## Emplacements des fichiers locaux

Tous sont sous `%APPDATA%\P1900_Production_Manager\` (raccourci pour `C:\Users\<utilisateur>\AppData\Roaming\P1900_Production_Manager\`) :

| Fichier | Rôle |
|---|---|
| `secrets.bin` | Credentials SFTP chiffrés (DPAPI). Non-portable — lié à la machine. |
| `settings.json` | Préférences utilisateur (thème actif, etc.). |
| `image_cache\<produit>_<taille>.png` | Cache des miniatures pour accélérer les chargements. |

L'application n'écrit **rien d'autre** sur le poste.

---

## Désinstallation

1. Supprimer le `.exe` (et le raccourci s'il existe).
2. Supprimer le dossier `%APPDATA%\P1900_Production_Manager\` pour effacer aussi les credentials chiffrés et les préférences.

---

## Multi-utilisateurs (un seul EXE partagé)

Le mode réseau partagé est explicitement supporté : un seul `OTA_FTP_App.exe` sur un partage, plusieurs utilisateurs Windows qui le lancent en parallèle.

Chaque utilisateur a son propre `%APPDATA%` Windows, et donc son propre :
- `secrets.bin` (credentials chiffrés liés à son compte)
- `settings.json` (thème, préférences)
- `image_cache/` (vignettes des produits)

Le `secrets.bin` chiffré par un utilisateur **ne peut pas** être déchiffré par un autre utilisateur, même sur la même machine — DPAPI est lié au compte Windows. Chaque utilisateur doit donc passer une fois par l'étape B (dépôt de `secrets.ini` dans son APPDATA).

Pour automatiser le dépôt sur plusieurs comptes, l'IT peut :
- Pousser le fichier via un GPO (Group Policy → Files → Replace dans `%APPDATA%\P1900_Production_Manager\`)
- Exécuter le snippet PowerShell de l'étape B via un script de logon
- Utiliser un outil de gestion de configuration (SCCM, Intune, etc.)

---

## Dépannage

| Symptôme | Cause probable | Solution |
|---|---|---|
| « Aucun secret chargé » au démarrage | `secrets.ini` non déposé dans `%APPDATA%\P1900_Production_Manager\` pour l'utilisateur courant | Voir étape B. |
| « Erreur secrets : Machine mismatch » | `secrets.bin` créé sur une autre machine (poste cloné, image disque restaurée) | Supprimer `%APPDATA%\P1900_Production_Manager\secrets.bin` et redéposer un `secrets.ini` au même endroit, puis relancer. |
| « Connexion impossible : timeout » | Pare-feu d'entreprise bloque le port 22 sortant vers `sftp.altyor.solutions` | Demander à l'IT d'ouvrir le port 22 sortant (et le DNS pour `sftp.altyor.solutions`). |
| « Connexion impossible : Authentication failed » | Clé SFTP révoquée ou expirée | Demander à l'IT un nouveau `secrets.ini`, supprimer l'ancien `secrets.bin`, redéposer. |
| L'EXE est bloqué par l'antivirus | PyInstaller `--onefile` génère parfois des faux positifs | Faire whitelister `OTA_FTP_App.exe` par l'IT, ou demander une version signée Authenticode. |
| L'EXE ne se lance pas du tout | Windows SmartScreen bloque les exécutables non signés | Clic droit → Propriétés → onglet Général → cocher « Débloquer », OK. |

---

## Côté serveur SFTP (rappel pour l'IT)

L'application accède à `sftp.altyor.solutions:22` avec :
- L'utilisateur `P1900-production-manager`
- La clé privée fournie dans `secrets.ini`
- Racine de travail par défaut : `/production/` (configurable via `root_dir` dans `secrets.ini`)
- Racine de dépôt client : `/deposit/` (basculable depuis l'interface)

Toutes les opérations passent par cette unique session SFTP — aucune autre connexion sortante n'est établie.

---

## Contact

Pour les problèmes d'authentification ou de credentials, contacter l'IT (renouvellement de la clé SFTP).
Pour les bugs ou demandes d'évolution de l'application, contacter l'équipe développement.
