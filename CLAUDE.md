# SIN_PRO - NodOn BLE OTA App

## Resumen del Proyecto

Aplicación Android modificada basada en Silicon Labs BLE Demo para realizar actualizaciones OTA de dispositivos NodOn.

## Cambios Realizados

### 1. Soporte de Archivos
- **Archivo**: `MainActivity.kt`
- Soporte para archivos `.gbl` y `.zigbee`
- Explorador de archivos abre por defecto en `/Production/`

### 2. Filtros de Escaneo
- **RSSI**: -40 dBm a 0 dBm (solo dispositivos muy cercanos)
- **Nombre**: Filtro "SIN" por defecto
- **Archivos modificados**:
  - `ScanFragment.kt`
  - `ScanFragmentViewModel.kt`
  - `FilterFragment.kt`
  - `range_slider_values.xml`

### 3. Validación de Modelos
- **Archivo**: `DeviceServicesActivity.kt`

#### Model Number (box arriba derecha)
| Modelo | Color |
|--------|-------|
| SIN-4-2-20 | Verde |
| SIN-4-RS-20 | Verde |
| Otros | Rojo |

#### Firmware Antenna
| Modelo | Versión Correcta | Color |
|--------|------------------|-------|
| SIN-4-2-20 | 3.13.0 | Verde |
| SIN-4-2-20_PRO | 3.13.0 | Verde |
| SIN-4-RS-20 | 3.12.0 | Verde |
| SIN-4-RS-20_PRO | 3.12.0 | Verde |

#### Firmware Power
| Versión | Color |
|---------|-------|
| 3.1.5 | Verde |
| Otra | Rojo |

### 4. Wake Lock para OTA
- **Archivo**: `DeviceServicesActivity.kt`
- Previene desconexión durante actualizaciones OTA largas
- Timeout: 10 minutos máximo

### 5. UUIDs Agregados
- **Archivo**: `UuidConsts.kt`
```kotlin
val DEVICE_INFORMATION: UUID = UUID.fromString("0000180a-0000-1000-8000-00805f9b34fb")
val FIRMWARE_VERSION: UUID = UUID.fromString("00002a26-0000-1000-8000-00805f9b34fb")
val MODEL_NUMBER: UUID = UUID.fromString("00002a24-0000-1000-8000-00805f9b34fb")
```

## Archivos Principales Modificados

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

## Compilación

```bash
export JAVA_HOME="C:/Program Files/Android/Android Studio/jbr"
export PATH="$JAVA_HOME/bin:$PATH"
cd silabs-source
./gradlew assembleSi-ConnectRelease
```

## APK de Salida

```
silabs-source/mobile/Builds/silabs-source/mobile/outputs/apk/Si-Connect/release/mobile-Si-Connect-release-3.2.0.apk
```

## Notas de Instalación

Si aparece error "app cannot be registered":
1. Desinstalar la app anterior del dispositivo
2. Instalar el nuevo APK

## Permisos Requeridos

- BLUETOOTH, BLUETOOTH_ADMIN, BLUETOOTH_SCAN, BLUETOOTH_CONNECT
- ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION
- WAKE_LOCK
- READ/WRITE_EXTERNAL_STORAGE
