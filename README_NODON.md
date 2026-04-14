# NodOn BLE OTA App (SIN_PRO)

> **[Francais](#francais)** | **[Espanol](#espanol)** | **[English](#english)**

Based on Silicon Labs SimplicityConnect Android SDK | Version **3.2.0**

---

<a id="francais"></a>

# FR - Application de Mise a Jour OTA pour Dispositifs BLE NodOn

## Vue d'ensemble

Application Android professionnelle developpee pour la mise a jour Over-The-Air (OTA) des dispositifs Bluetooth Low Energy (BLE) de la gamme NodOn SIN. Cette application permet de visualiser les versions de firmware et d'effectuer des mises a jour firmware de maniere simple et efficace.

## Fonctionnalites principales

### 1. Affichage des versions de firmware
- **Visualisation duale** : Affichage simultane des versions firmware Antenna et Power
- **Validation automatique** : Codes couleur pour identifier rapidement la conformite
  - Vert : Version conforme aux specifications
  - Rouge : Version non conforme necessitant une mise a jour
- **Format d'affichage** : Deux badges cote a cote pour une lecture facilitee

### 2. Identification du modele
- **Affichage du Model Number** : Identification automatique du modele de dispositif
- **Support multi-modeles** : Compatible avec les modeles certifies NodOn
- **Validation visuelle** : Code couleur pour confirmer la compatibilite

### 3. Processus OTA simplifie
- **Selection automatique du fichier** : L'application demande le fichier firmware (.gbl/.zigbee) au demarrage
- **Validation du fichier** : Verification automatique de l'extension et du format
- **Mise a jour en un clic** : Processus OTA automatise apres connexion au dispositif
- **Suivi en temps reel** : Indicateurs de progression pendant la mise a jour

## Modeles supportes

| Modele | Version Antenna | Version Power | Statut |
|--------|----------------|---------------|--------|
| **SIN-4-2-20** | 3.13.0 | 3.1.5 | Valide |
| **SIN-4-2-20_PRO** | 3.13.0 | 3.1.5 | Valide |
| **SIN-4-RS-20** | 3.12.0 | 3.1.5 | Valide |
| **SIN-4-RS-20_PRO** | 3.12.0 | 3.1.5 | Valide |

## Installation et utilisation

### Prerequis
- **Appareil Android** : Version 8.0 (API 26) ou superieure
- **Bluetooth** : BLE (Bluetooth Low Energy) active
- **Permissions** : Localisation, Bluetooth, Stockage

### Installation
1. Telecharger le fichier APK : `mobile-Si-Connect-release-3.2.0.apk`
2. Activer l'installation depuis des sources inconnues
3. Installer l'application en ouvrant le fichier APK
4. Accepter les permissions demandees lors du premier lancement

### Guide d'utilisation

1. **Lancement** : L'application demande automatiquement de selectionner un fichier firmware (.gbl ou .zigbee)
2. **Connexion** : Scanner et selectionner le dispositif NodOn SIN
3. **Verification** : Trois informations s'affichent : Model Number, Version Antenna, Version Power (vert = conforme, rouge = a mettre a jour)
4. **Mise a jour OTA** : Cliquer sur "OTA Firmware", suivre la progression, ne pas deconnecter le dispositif
5. **Verification post-mise a jour** : Les versions s'actualisent automatiquement

## Depannage

| Probleme | Solution |
|----------|----------|
| Dispositif non visible au scan | Verifier Bluetooth et permissions de localisation |
| Echec OTA | Verifier compatibilite firmware/modele, rester a proximite |
| Versions non affichees | Attendre quelques secondes, reconnecter si necessaire |
| "Fichier non valide" | Verifier extension .gbl/.zigbee, re-telecharger si corrompu |
| "App cannot be registered" | Desinstaller l'app precedente, reinstaller |

## Specifications techniques

- **Langage** : Kotlin
- **SDK minimum** : Android 8.0 (API 26)
- **SDK cible** : Android 14 (API 34)
- **Service OTA** : `1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0`
- **MTU optimise** : 247 bytes
- **Format firmware** : `X.Y.Z-A.B.C` (Antenna-Power)
- **Fichiers supportes** : `.gbl` (Gecko Bootloader), `.zigbee`

---

<a id="espanol"></a>

# ES - Aplicacion de Actualizacion OTA para Dispositivos BLE NodOn

## Resumen

Aplicacion Android modificada basada en Silicon Labs BLE Demo para realizar actualizaciones OTA de dispositivos NodOn SIN. Permite visualizar versiones de firmware y efectuar actualizaciones de forma simple.

## Cambios realizados

### 1. Soporte de archivos
- Soporte para archivos `.gbl` y `.zigbee`
- Explorador de archivos abre por defecto en `/Production/`

### 2. Filtros de escaneo
- **RSSI** : -40 dBm a 0 dBm (solo dispositivos muy cercanos)
- **Nombre** : Filtro "SIN" por defecto

### 3. Validacion de modelos

#### Model Number (box arriba derecha)
| Modelo | Color |
|--------|-------|
| SIN-4-2-20 | Verde |
| SIN-4-RS-20 | Verde |
| Otros | Rojo |

#### Firmware Antenna
| Modelo | Version Correcta | Color |
|--------|------------------|-------|
| SIN-4-2-20 | 3.13.0 | Verde |
| SIN-4-2-20_PRO | 3.13.0 | Verde |
| SIN-4-RS-20 | 3.12.0 | Verde |
| SIN-4-RS-20_PRO | 3.12.0 | Verde |

#### Firmware Power
| Version | Color |
|---------|-------|
| 3.1.5 | Verde |
| Otra | Rojo |

### 4. Wake Lock para OTA
- Previene desconexion durante actualizaciones OTA largas
- Timeout: 10 minutos maximo

### 5. UUIDs agregados
```kotlin
val DEVICE_INFORMATION: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
val FIRMWARE_VERSION: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
val MODEL_NUMBER: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
```

## Archivos principales modificados

```
silabs-source/mobile/src/main/java/com/siliconlabs/bledemo/
├── home_screen/
│   ├── activities/MainActivity.kt
│   ├── fragments/ScanFragment.kt
│   └── viewmodels/ScanFragmentViewModel.kt
├── features/scan/browser/
│   ├── activities/DeviceServicesActivity.kt
│   └── fragments/FilterFragment.kt
└── utils/UuidConsts.kt

silabs-source/mobile/src/main/res/
├── layout/activity_device_services.xml
└── values/range_slider_values.xml

silabs-source/mobile/src/main/AndroidManifest.xml
```

## Compilacion

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
export PATH="$JAVA_HOME/bin:$PATH"
cd silabs-source
./gradlew assembleSi-ConnectRelease
```

## APK de salida

```
silabs-source/mobile/Builds/silabs-source/mobile/outputs/apk/Si-Connect/release/mobile-Si-Connect-release-3.2.0.apk
```

## Instalacion

Si aparece error "app cannot be registered":
1. Desinstalar la app anterior del dispositivo
2. Instalar el nuevo APK

## Permisos requeridos

- BLUETOOTH, BLUETOOTH_ADMIN, BLUETOOTH_SCAN, BLUETOOTH_CONNECT
- ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION
- WAKE_LOCK
- READ/WRITE_EXTERNAL_STORAGE

---

<a id="english"></a>

# EN - OTA Update Application for NodOn BLE Devices

## Overview

Modified Android application based on Silicon Labs BLE Demo for performing Over-The-Air (OTA) firmware updates on NodOn SIN Bluetooth Low Energy devices. Displays firmware versions and enables simple, one-click OTA updates.

## Key Features

### 1. Dual firmware version display
- Simultaneous display of Antenna and Power firmware versions
- Color-coded validation: green = compliant, red = update needed
- Side-by-side badge layout

### 2. Device model identification
- Automatic model number display and validation
- Multi-model support for certified NodOn devices

### 3. Simplified OTA process
- Automatic firmware file selection (.gbl/.zigbee) at startup
- One-click OTA after device connection
- Real-time progress indicators
- Wake lock prevents disconnection during long updates (10 min max)

## Supported Models

| Model | Antenna Version | Power Version | Status |
|-------|----------------|---------------|--------|
| **SIN-4-2-20** | 3.13.0 | 3.1.5 | Validated |
| **SIN-4-2-20_PRO** | 3.13.0 | 3.1.5 | Validated |
| **SIN-4-RS-20** | 3.12.0 | 3.1.5 | Validated |
| **SIN-4-RS-20_PRO** | 3.12.0 | 3.1.5 | Validated |

## Installation

### Prerequisites
- **Android device**: Version 8.0 (API 26) or higher
- **Bluetooth**: BLE enabled
- **Permissions**: Location, Bluetooth, Storage

### Install
1. Download APK: `mobile-Si-Connect-release-3.2.0.apk`
2. Enable installation from unknown sources
3. Install and accept permissions

If "app cannot be registered" error appears: uninstall the previous app first.

### Usage

1. **Launch**: App prompts to select a firmware file (.gbl or .zigbee)
2. **Connect**: Scan and select NodOn SIN device
3. **Verify**: Check Model Number, Antenna Version, Power Version (green = OK, red = needs update)
4. **OTA Update**: Tap "OTA Firmware", follow progress, do not disconnect
5. **Post-update**: Versions refresh automatically

## Build from Source

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
export PATH="$JAVA_HOME/bin:$PATH"
cd silabs-source
./gradlew assembleSi-ConnectRelease
```

Output APK:
```
silabs-source/mobile/Builds/silabs-source/mobile/outputs/apk/Si-Connect/release/mobile-Si-Connect-release-3.2.0.apk
```

## Technical Specifications

- **Language**: Kotlin
- **Min SDK**: Android 8.0 (API 26)
- **Target SDK**: Android 14 (API 34)
- **OTA Service UUID**: `1d14d6ee-fd63-4fa1-bfa4-8f47b42119f0`
- **Optimized MTU**: 247 bytes
- **Firmware format**: `X.Y.Z-A.B.C` (Antenna-Power), e.g. `3.13.0-3.1.5`
- **Supported files**: `.gbl` (Gecko Bootloader), `.zigbee`

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Device not visible in scan | Check Bluetooth and location permissions |
| OTA failure | Verify firmware/model compatibility, stay close to device |
| Versions not displayed | Wait a few seconds, reconnect if needed |
| "Invalid file" | Check .gbl/.zigbee extension, re-download if corrupted |

---

## Changelog

### V1.0.0
- Custom app name: "NodOn BLE OTA MiF"
- Firmware version display next to device name
- Application startup OTA file selection prompt
- Session-persistent OTA file storage
- Direct OTA initiation without configuration dialogs
- Automatic OTA completion at 100% progress
- Default device name filter: "VAV"
- Default RSSI range filter: -60 dBm to 0 dBm
- FW version displayed in green box if v3.13.0, otherwise red

### V3.2.0 (Current)
- Dual firmware display (Antenna + Power)
- Model number validation with color coding
- Updated default filter: "SIN" with RSSI -40 to 0 dBm
- Support for .gbl and .zigbee files
- Wake lock during OTA (10 min max)
- Multi-model support: SIN-4-2-20, SIN-4-RS-20 (+ PRO variants)
- Periodic firmware refresh every 10 seconds
- Optimized MTU (247 bytes) and reliable upload mode

---

## License

Based on Silicon Labs SimplicityConnect SDK, distributed under Apache License 2.0.

Copyright (c) 2024 NodOn - All rights reserved.
