# Application de Mise à Jour OTA pour Dispositifs BLE NodOn

## Vue d'ensemble

Application Android professionnelle développée pour la mise à jour Over-The-Air (OTA) des dispositifs Bluetooth Low Energy (BLE) de la gamme NodOn SIN. Cette application permet de visualiser les versions de firmware et d'effectuer des mises à jour firmware de manière simple et efficace.

Version: **3.2.0**
Basée sur: Silicon Labs SimplicityConnect Android SDK

## Fonctionnalités principales

### 1. Affichage des versions de firmware
- **Visualisation duale**: Affichage simultané des versions firmware Antenna et Power
- **Validation automatique**: Codes couleur pour identifier rapidement la conformité
  - 🟢 **Vert**: Version conforme aux spécifications
  - 🔴 **Rouge**: Version non conforme nécessitant une mise à jour
- **Format d'affichage**: Deux badges côte à côte pour une lecture facilitée

### 2. Identification du modèle
- **Affichage du Model Number**: Identification automatique du modèle de dispositif
- **Support multi-modèles**: Compatible avec les modèles certifiés NodOn
- **Validation visuelle**: Code couleur pour confirmer la compatibilité

### 3. Processus OTA simplifié
- **Sélection automatique du fichier**: L'application demande le fichier firmware (.gbl/.zigbee) au démarrage
- **Validation du fichier**: Vérification automatique de l'extension et du format
- **Mise à jour en un clic**: Processus OTA automatisé après connexion au dispositif
- **Suivi en temps réel**: Indicateurs de progression pendant la mise à jour

## Modèles supportés

| Modèle | Version Antenna | Version Power | Statut |
|--------|----------------|---------------|---------|
| **SIN-4-2-20** | 3.13.0 | 3.1.5 | ✅ Validé |
| **SIN-4-RS-20** | 3.12.0 | 3.1.5 | ✅ Validé |

## Installation et utilisation

### Prérequis
- **Appareil Android**: Version 8.0 (API 26) ou supérieure
- **Bluetooth**: BLE (Bluetooth Low Energy) activé
- **Permissions**:
  - Localisation (requise pour le scan BLE)
  - Bluetooth (scan, connexion, publicité)
  - Stockage (lecture du fichier firmware)

### Installation
1. Télécharger le fichier APK: `mobile-Si-Connect-release-3.2.0.apk`
2. Activer l'installation depuis des sources inconnues sur votre appareil Android
3. Installer l'application en ouvrant le fichier APK
4. Accepter les permissions demandées lors du premier lancement

### Guide d'utilisation

#### Première utilisation
1. **Lancement de l'application**
   - Au premier démarrage, l'application demande automatiquement de sélectionner un fichier firmware
   - Naviguer vers le dossier contenant le fichier `.gbl` ou `.zigbee`
   - Sélectionner le fichier firmware approprié pour votre dispositif

2. **Connexion au dispositif**
   - Scanner les dispositifs BLE disponibles
   - Sélectionner le dispositif NodOn SIN à mettre à jour
   - L'application se connecte automatiquement

3. **Vérification des versions**
   - Une fois connecté, trois informations s'affichent:
     - **Model Number** (en haut à droite)
     - **Version Antenna** (en bas à gauche)
     - **Version Power** (en bas à droite)
   - Les badges verts indiquent des versions conformes
   - Les badges rouges indiquent des versions nécessitant une mise à jour

4. **Mise à jour OTA**
   - Cliquer sur le bouton **"OTA Firmware"**
   - L'application utilise automatiquement le fichier sélectionné au démarrage
   - Suivre la progression de la mise à jour sur l'écran
   - **Ne pas déconnecter** le dispositif pendant la mise à jour

5. **Vérification post-mise à jour**
   - Après la mise à jour, les versions s'actualisent automatiquement
   - Vérifier que les badges sont maintenant verts
   - Le dispositif peut redémarrer automatiquement

#### Utilisation quotidienne
- À chaque lancement, l'application demande de sélectionner un fichier firmware
- Cela permet de mettre à jour différents dispositifs avec des firmwares différents
- Il est possible d'annuler la sélection et de continuer sans fichier (fonctionnalités limitées)

## Spécifications techniques

### Architecture
- **Langage**: Kotlin
- **SDK minimum**: Android 8.0 (API 26)
- **SDK cible**: Android 14 (API 34)
- **Framework BLE**: Android Bluetooth Low Energy API
- **Interface**: Material Design Components

### Caractéristiques BLE
- **Service Device Information**: `0x180A`
- **Firmware Version**: `0x2A26`
- **Model Number**: `0x2A24`
- **Service OTA**: `1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0`
- **MTU optimisé**: 247 bytes pour des transferts rapides

### Format de version firmware
Les versions sont affichées au format: `X.Y.Z-A.B.C`
- `X.Y.Z`: Version du firmware Antenna
- `A.B.C`: Version du firmware Power

Exemple: `3.13.0-3.1.5`

### Formats de fichiers supportés
- `.gbl` (Gecko Bootloader)
- `.zigbee` (Format propriétaire Zigbee)

## Sécurité et performance

### Sécurité
- ✅ Validation des fichiers firmware avant utilisation
- ✅ Permissions runtime conformes aux standards Android
- ✅ Connexions BLE sécurisées
- ✅ Vérification de l'intégrité des données pendant le transfert

### Performance
- ⚡ Transfert optimisé avec MTU élevé (247 bytes)
- ⚡ Connexion prioritaire haute vitesse pendant l'OTA
- ⚡ Actualisation automatique toutes les 10 secondes
- ⚡ Mode fiable activé par défaut (reliable mode)

## Dépannage

### Problèmes courants

**Le dispositif n'apparaît pas dans le scan**
- Vérifier que le Bluetooth est activé
- Vérifier que les permissions de localisation sont accordées
- S'assurer que le dispositif est allumé et à proximité
- Redémarrer l'application et le Bluetooth

**La mise à jour OTA échoue**
- Vérifier que le fichier firmware est compatible avec le modèle
- S'assurer que le dispositif est suffisamment chargé
- Rester à proximité du dispositif pendant toute la durée de la mise à jour
- Ne pas mettre l'écran en veille pendant la mise à jour

**Les versions ne s'affichent pas**
- Attendre quelques secondes après la connexion
- L'application actualise automatiquement les informations
- Si le problème persiste, déconnecter et reconnecter le dispositif

**Message "Fichier non valide"**
- Vérifier que le fichier a l'extension `.gbl` ou `.zigbee`
- S'assurer que le fichier n'est pas corrompu
- Télécharger à nouveau le firmware si nécessaire

## Support et contact

Pour toute question ou assistance technique:
- **Documentation technique**: Consulter la documentation Silicon Labs
- **Support produit**: Contacter le service client NodOn
- **Mises à jour**: Vérifier régulièrement la disponibilité de nouvelles versions

## Informations légales

### Licence
Cette application est basée sur le SDK SimplicityConnect de Silicon Labs, distribué sous licence MIT.

Copyright (c) 2024 NodOn
Tous droits réservés.

### Conformité
- Conforme aux normes Bluetooth SIG
- Respecte le RGPD pour la protection des données
- Testé selon les standards de qualité industriels

### Mentions importantes
- Cette application est destinée à un usage professionnel et industriel
- L'utilisation incorrecte peut entraîner des dysfonctionnements du dispositif
- Toujours vérifier la compatibilité du firmware avant la mise à jour
- Conserver une sauvegarde de la configuration avant mise à jour

---

**Version du document**: 1.0
**Dernière mise à jour**: Février 2024
**Langues disponibles**: Français (FR), Español (ES)
