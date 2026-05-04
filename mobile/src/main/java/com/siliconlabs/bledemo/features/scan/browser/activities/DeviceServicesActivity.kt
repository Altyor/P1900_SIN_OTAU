/*
 * Bluegiga’s Bluetooth Smart Android SW for Bluegiga BLE modules
 * Contact: support@bluegiga.com.
 *
 * This is free software distributed under the terms of the MIT license reproduced below.
 *
 * Copyright (c) 2013, Bluegiga Technologies
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation files ("Software")
 * to deal in the Software without restriction, including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
 * and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * THIS CODE AND INFORMATION ARE PROVIDED "AS IS" WITHOUT WARRANTY OF
 * ANY KIND, EITHER EXPRESSED OR IMPLIED, INCLUDING BUT
 * NOT LIMITED TO THE IMPLIED WARRANTIES OF MERCHANTABILITY AND/OR FITNESS FOR A  PARTICULAR PURPOSE.
 */
package com.siliconlabs.bledemo.features.scan.browser.activities

import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.TextUtils
import android.util.Log
import android.view.*
import android.view.View.OnScrollChangeListener
import android.widget.*
import androidx.core.content.ContextCompat
import androidx.core.content.ContextCompat.startActivity
import androidx.core.view.children
import androidx.fragment.app.Fragment
import by.kirich1409.viewbindingdelegate.viewBinding
import com.siliconlabs.bledemo.base.activities.BaseActivity
import com.siliconlabs.bledemo.bluetooth.ble.ErrorCodes
import com.siliconlabs.bledemo.bluetooth.ble.TimeoutGattCallback
import com.siliconlabs.bledemo.bluetooth.services.BluetoothService
import com.siliconlabs.bledemo.features.scan.browser.dialogs.*
import com.siliconlabs.bledemo.features.scan.browser.dialogs.ErrorDialog.OtaErrorCallback
import com.siliconlabs.bledemo.features.scan.browser.fragments.*
import com.siliconlabs.bledemo.features.scan.browser.models.OtaFileType
import com.siliconlabs.bledemo.R
import com.siliconlabs.bledemo.bluetooth.ble.BluetoothDeviceInfo
import com.siliconlabs.bledemo.common.views.FlyInBar
import com.siliconlabs.bledemo.databinding.ActivityDeviceServicesBinding
import com.siliconlabs.bledemo.features.scan.browser.fragments.BrowserFragment.Companion.ORIGIN
import com.siliconlabs.bledemo.features.scan.browser.fragments.BrowserFragment.Companion.RECYCLERVIEW_POSITION
import com.siliconlabs.bledemo.home_screen.viewmodels.ScanFragmentViewModel
import com.siliconlabs.bledemo.utils.*
import timber.log.Timber
import java.io.*
import java.util.*

@SuppressWarnings("LogNotTimber")
@SuppressLint("MissingPermission")
class DeviceServicesActivity : BaseActivity() {

    private lateinit var handler: Handler

    private var viewState = ViewState.IDLE
    private var otaCompleted = false
    private var initialReadDone = false
    private var awaitingPowerReboot = false
    private var powerRebootHandled = false
    private var otaRetryCount = 0
    private var antennaOtaCompleted = false
    private var otaInProgress = false
    private var firmwareReadRetries = 0
    private var modelReadRetries = 0
    private var operatorOverrideActive = false
    private var overrideDialogShown = false
    // True between antenna OTA completion and power OTA kickoff. If the BLE link
    // drops during that ~3s window, we use this to reconnect and resume instead
    // of finishing the activity or firing the power OTA on a dead GATT.
    private var pendingPowerOta = false
    private var modelMismatchDialogShown = false
    // Set when an OTA_DATA write returns status 133 (Silabs Apploader flash-buffer
    // stall on 2.x antennas). The subsequent auto-retry uses BALANCED connection
    // priority to give the flash controller time to commit pages.
    private var retryAtBalancedPriority = false
    // Fired once after a full BOTH (or single) OTA completes to force a fresh GATT
    // session — Android's attribute cache otherwise keeps feeding back the pre-OTA
    // FW version string over the same connection.
    private var postOtaColdReconnectPending = false
    private var postOtaColdReconnectDone = false
    // Persistent-log bookkeeping: otaWasRun is set when an OTA was actually
    // launched in this session (so a normal connect to a healthy device
    // doesn't trigger a "success" / "failed" mark). otaResultRecorded keeps
    // checkAllVersionsMatch from firing markOtaSuccess/Failed multiple times.
    private var otaWasRun = false
    private var otaResultRecorded = false
    private var discoveryPending = false
    private var mtuReadType = MtuReadType.VIEW_INITIALIZATION
    private var isLogFragmentOn = false

    private var menu: Menu? = null

    private var reliable = true
    private var doubleStepUpload = false

    private var boolFullOTA = false
    var isUiCreated = false
    private var otaDataCharPresent = false

    private var mtuRequestDialog: MtuRequestDialog? = null
    private var otaConfigDialog: OtaConfigDialog? = null
    private var otaProgressDialog: OtaProgressDialog? = null
    private var otaLoadingDialog: OtaLoadingDialog? = null
    private var errorDialog: ErrorDialog? = null

    private var MTU = 247
    private var connectionPriority = BluetoothGatt.CONNECTION_PRIORITY_BALANCED
    private var mtuDivisible = 0
    private var otatime: Long = 0
    private var pack = 0
    private var otafile: ByteArray? = null


    // Per-packet pacing for non-reliable (WRITE_NO_RESPONSE) OTA uploads. 1 ms was
    // way too fast — packets overflowed Android's LL queue and the loop "finished"
    // in ~10s while the actual bytes were still in-flight, then END fired before
    // the device had received the full image. 10 ms matches realistic BLE
    // throughput at HIGH priority (~200 kbps, ~244 B per interval).
    private var delayNoResponse = 10
    private var currentOtaFileType: OtaFileType = OtaFileType.APPLICATION

    // OTA file paths
    private var appPath = ""
    private var stackPath = ""


    private var bluetoothBinding: BluetoothService.Binding? = null
    var bluetoothService: BluetoothService? = null
        private set

    private var bluetoothDevice: BluetoothDevice? = null
    private var devicesAdapterRecyclerViewItemPos :Int = -1
    var bluetoothGatt: BluetoothGatt? = null

    private var retryAttempts = 0
    private var firmwareVersion: String? = null
    private var firmwareVersionAntenna: String? = null
    private var firmwareVersionPower: String? = null
    private var modelNumber: String? = null

    // Firmware version refresh functionality
    private var firmwareRefreshRunnable: Runnable? = null
    private val firmwareRefreshInterval = 10000L // Refresh every 10 seconds

    // Wake lock to keep device awake during OTA
    private var wakeLock: PowerManager.WakeLock? = null

    private lateinit var binding:ActivityDeviceServicesBinding

    lateinit var scanFragmentViewModel: ScanFragmentViewModel

    private val hideFabOnScrollChangeListener =
        OnScrollChangeListener { _, _, _, _, _ -> }

    private val remoteServicesFragment = RemoteServicesFragment(hideFabOnScrollChangeListener)
    private val localServicesFragment = LocalServicesFragment()
    private var activeFragment: Fragment = remoteServicesFragment

    private var pendingPowerOtaFallbackRunnable: Runnable? = null

    private val bondStateChangeListener = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val newState =
                    intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                displayBondState(newState)
                // If bonding is starting and we're waiting to fire the power OTA,
                // cancel the short fallback timer — bonding can take 10–30+ s
                // when it involves physical-button + pairing-dialog confirmation.
                // We'll fire on BOND_BONDED below instead.
                if (newState == BluetoothDevice.BOND_BONDING && pendingPowerOta) {
                    Log.d("OTA_DEBUG", "[BOTH 2/2] Bonding started mid-handoff — cancelling fallback timer, waiting for BOND_BONDED")
                    pendingPowerOtaFallbackRunnable?.let { handler.removeCallbacks(it) }
                    pendingPowerOtaFallbackRunnable = null
                }
                if (newState == BluetoothDevice.BOND_BONDED && !operatorOverrideActive) {
                    showMessage(getString(R.string.device_bonded_successfully))
                    // Bonding just completed — some devices only return DIS values after bonding.
                    // Reset retry counters and restart the firmware/model read chain.
                    Log.d("OTA_DEBUG", "Bond completed, restarting DIS read chain")
                    firmwareReadRetries = 0
                    modelReadRetries = 0
                    overrideDialogShown = false
                    getFirmwareVersionCharacteristic()?.let {
                        bluetoothGatt?.readCharacteristic(it)
                    }

                    // If we were waiting to start the second (power) OTA, bonding just
                    // unlocked writes — fire it now instead of racing with the handoff
                    // timer. This covers the case where new antenna FW (post-antenna-OTA)
                    // requires bonding before the power OTA writes can be accepted.
                    if (pendingPowerOta) {
                        Log.d("OTA_DEBUG", "[BOTH 2/2] Bond completed — firing second OTA now")
                        pendingPowerOtaFallbackRunnable?.let { handler.removeCallbacks(it) }
                        pendingPowerOtaFallbackRunnable = null
                        pendingPowerOta = false
                        handler.postDelayed({ checkForOtaCharacteristic() }, 500)
                    }
                }
            }
        }
    }

    private val bluetoothReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_OFF -> finish()
                }
            }
        }
    }

    private val gattCallback: TimeoutGattCallback = object : TimeoutGattCallback() {
        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) {
            if (viewState == ViewState.IDLE) {
                super.onReadRemoteRssi(gatt, rssi, status)
                runOnUiThread { binding.tvRssi.text = resources.getString(R.string.n_dBm, rssi) }
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            super.onMtuChanged(gatt, mtu, status)

            when (mtuReadType) {
                MtuReadType.VIEW_INITIALIZATION -> {
                    MTU = if (status == BluetoothGatt.GATT_SUCCESS) mtu
                    else DEFAULT_MTU_VALUE
                    gatt.requestConnectionPriority(connectionPriority)

                    // Read firmware version now that MTU is set
                    val fwChar = getFirmwareVersionCharacteristic()
                    if (fwChar != null) {
                        Log.d("OTA_DEBUG", "Reading firmware version after MTU init")
                        gatt.readCharacteristic(fwChar)
                    } else {
                        // FW Version characteristic absent on this device. Silently activate
                        // override so the OTA flow continues, and read Model directly so the
                        // operator still sees the model validated.
                        Log.w("OTA_DEBUG", "Firmware version characteristic absent — auto-override, reading model")
                        operatorOverrideActive = true
                        firmwareVersion = "?"
                        firmwareVersionAntenna = "?"
                        firmwareVersionPower = "?"
                        runOnUiThread { updateFirmwareVersionDisplay() }
                        getModelNumberCharacteristic()?.let { modelChar ->
                            gatt.readCharacteristic(modelChar)
                        }
                    }
                }

                MtuReadType.UPLOAD_INITIALIZATION -> {
                    MTU = if (status == BluetoothGatt.GATT_SUCCESS) mtu
                    else DEFAULT_MTU_VALUE
                    val priority = if (retryAtBalancedPriority) {
                        Log.d("OTA_DEBUG", "Retry after flash-stall: using CONNECTION_PRIORITY_BALANCED")
                        BluetoothGatt.CONNECTION_PRIORITY_BALANCED
                    } else {
                        BluetoothGatt.CONNECTION_PRIORITY_HIGH
                    }
                    gatt.requestConnectionPriority(priority)
                    if (operatorOverrideActive) {
                        // Non-standard firmware (e.g. antenna 2.22 / PRO Rework): writing 0x00
                        // a second time in bootloader causes another reboot, not start-upload.
                        // Skip the second OTA_CONTROL write and go straight to UPLOADING.
                        Log.d("OTA_DEBUG", "Override mode: skipping second OTA_CONTROL write, going directly to UPLOADING")
                        viewState = ViewState.UPLOADING
                        startOtaUpload()
                    } else {
                        writeOtaControl(OTA_CONTROL_START_COMMAND)
                    }
                }

                MtuReadType.USER_REQUESTED -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        MTU = mtu
                        showMessage(getString(R.string.MTU_colon_n, MTU))
                    } else {
                        showMessage(getString(R.string.error_requesting_mtu, status))
                    }
                    mtuRequestDialog?.dismiss()
                    mtuRequestDialog = null
                }
            }
        }

        //CALLBACK ON CONNECTION STATUS CHANGES
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            super.onConnectionStateChange(gatt, status, newState)

            Log.d("OTA_DEBUG", "onConnectionStateChange: status=$status, newState=$newState, viewState=$viewState, device=${gatt.device.address}")

            // Use bluetoothDevice?.address here because bluetoothGatt is set to null by
            // reconnect() (after close()) before connectGatt is invoked, so checking
            // bluetoothGatt?.device?.address would incorrectly reject the reconnect.
            val expectedAddress = bluetoothGatt?.device?.address ?: bluetoothDevice?.address
            if (expectedAddress != null && expectedAddress != gatt.device.address) {
                Log.w("OTA_DEBUG", "Connection state change for different device, ignoring")
                return
            }

            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    Log.d("OTA_DEBUG", "Device connected")
                    if ((viewState == ViewState.REBOOTING ||
                        viewState == ViewState.REBOOTING_NEW_FIRMWARE) && !discoveryPending
                    ) {
                        discoveryPending = true
                        Log.d("OTA_DEBUG", "Device reconnected during OTA process, discovering services")
                        handler.postDelayed({
                            bluetoothGatt = null
                            gatt.discoverServices()
                        }, 250)
                    } else {
                        Log.d("OTA_DEBUG", "Ignoring duplicate STATE_CONNECTED (discoveryPending=$discoveryPending, viewState=$viewState)")
                    }
                }

                BluetoothGatt.STATE_DISCONNECTED -> {
                    discoveryPending = false
                    Log.d("OTA_DEBUG", "Device disconnected with status $status during state $viewState")
                    when (viewState) {
                        ViewState.IDLE -> {
                            if (awaitingPowerReboot) {
                                // Power PCB finished updating and device kicked us — auto-reconnect
                                Log.d("OTA_DEBUG", "Power reboot kick detected (status=$status), auto-reconnecting")
                                handler.removeCallbacks(powerTransferTimeoutRunnable)
                                val strings = com.siliconlabs.bledemo.features.firmware_browser.domain.UiStrings
                                showOtaWaitingOverlay(strings.statusReconnecting)
                                awaitingPowerReboot = false
                                powerRebootHandled = true
                                viewState = ViewState.REBOOTING_NEW_FIRMWARE
                                reconnect(requestRssiUpdates = true)
                                handler.postDelayed(reconnectTimeoutRunnable, RECONNECT_TIMEOUT_MS)
                            } else if (pendingPowerOta) {
                                // Link dropped during the antenna→power handoff window.
                                // Don't finish; reconnect and let onServicesDiscovered
                                // re-arm the power OTA kickoff.
                                Log.w("OTA_DEBUG", "Handoff link drop (status=$status), reconnecting to resume second OTA")
                                val strings = com.siliconlabs.bledemo.features.firmware_browser.domain.UiStrings
                                runOnUiThread { showOtaWaitingOverlay(strings.statusReconnecting) }
                                viewState = ViewState.REBOOTING_NEW_FIRMWARE
                                reconnect(requestRssiUpdates = true)
                                handler.postDelayed(reconnectTimeoutRunnable, RECONNECT_TIMEOUT_MS)
                            } else if (otaCompleted) {
                                Log.d("OTA_DEBUG", "Device disconnected in IDLE after OTA completed, staying on result screen")
                            } else when (status) {
                                0 -> {
                                    Log.d("OTA_DEBUG", "Normal disconnection in IDLE state, finishing activity")
                                    finish()
                                }
                                else -> {
                                    Log.e("OTA_DEBUG", "Unexpected disconnection in IDLE state: status=$status")
                                    if (otaInProgress) {
                                        com.siliconlabs.bledemo.features.firmware_browser.domain.OtaFileLogger
                                            .markOtaFailed(
                                                "Unexpected disconnection in IDLE state: status=$status",
                                                bluetoothGatt?.device?.address ?: bluetoothDevice?.address
                                            )
                                    }
                                    showLongMessage(
                                        ErrorCodes.getDeviceDisconnectedMessage(
                                            getDeviceName(),
                                            status
                                        )
                                    )
                                    finish()
                                }
                            }
                        }

                        ViewState.REBOOTING -> when (status) {
                            // 19 = peer-terminated (Silabs stock OTA), 22 = peer-terminated
                            // (antenna 2.22 / PRO Rework pre-image). Both are clean
                            // disconnects for OTA mode reboot.
                            19, 22 -> {
                                Log.d("OTA_DEBUG", "Expected disconnection for OTA mode reboot (status $status) — reconnecting")
                                showInitializationInfo()
                                reconnect(requestRssiUpdates = true)
                                handler.postDelayed(reconnectTimeoutRunnable, RECONNECT_TIMEOUT_MS)
                            }

                            0 -> {
                                Log.d("OTA_DEBUG", "Normal disconnection during REBOOTING, device will reconnect")
                            }
                            else -> {
                                Log.e("OTA_DEBUG", "Unexpected disconnection during REBOOTING: status=$status")
                                if (otaInProgress) {
                                    attemptOtaRetry()
                                } else {
                                    showErrorDialog(status)
                                }
                            }
                        }

                        ViewState.UPLOADING -> {
                            Log.e("OTA_DEBUG", "Device disconnected during UPLOADING! status=$status")
                            attemptOtaRetry()
                        }

                        ViewState.REBOOTING_NEW_FIRMWARE -> {
                            if (awaitingPowerReboot) {
                                Log.d("OTA_DEBUG", "Power reboot kick during REBOOTING_NEW_FIRMWARE (status=$status), auto-reconnecting")
                                handler.removeCallbacks(powerTransferTimeoutRunnable)
                                val strings = com.siliconlabs.bledemo.features.firmware_browser.domain.UiStrings
                                showOtaWaitingOverlay(strings.statusReconnecting)
                                awaitingPowerReboot = false
                                powerRebootHandled = true
                                reconnect(requestRssiUpdates = true)
                                handler.postDelayed(reconnectTimeoutRunnable, RECONNECT_TIMEOUT_MS)
                            } else {
                                Log.d("OTA_DEBUG", "Device disconnected during firmware reboot, reconnecting to new firmware")
                                val strings = com.siliconlabs.bledemo.features.firmware_browser.domain.UiStrings
                                runOnUiThread {
                                    showOtaWaitingOverlay(strings.statusReconnecting)
                                }
                                reconnect(requestRssiUpdates = true)
                                handler.postDelayed(reconnectTimeoutRunnable, RECONNECT_TIMEOUT_MS)
                            }
                        }

                        else -> {
                            if (awaitingPowerReboot) {
                                Log.d("OTA_DEBUG", "Power reboot kick in state $viewState (status=$status), auto-reconnecting")
                                handler.removeCallbacks(powerTransferTimeoutRunnable)
                                val strings = com.siliconlabs.bledemo.features.firmware_browser.domain.UiStrings
                                showOtaWaitingOverlay(strings.statusReconnecting)
                                awaitingPowerReboot = false
                                powerRebootHandled = true
                                viewState = ViewState.REBOOTING_NEW_FIRMWARE
                                reconnect(requestRssiUpdates = true)
                                handler.postDelayed(reconnectTimeoutRunnable, RECONNECT_TIMEOUT_MS)
                            } else if (otaInProgress) {
                                Log.w("OTA_DEBUG", "Device disconnected in state $viewState during OTA, attempting retry")
                                attemptOtaRetry()
                            } else if (otaCompleted) {
                                Log.d("OTA_DEBUG", "Device disconnected after OTA completed, staying on result screen")
                            } else {
                                Log.w("OTA_DEBUG", "Device disconnected in unknown state: $viewState")
                                finish()
                            }
                        }
                    }
                }
            }
        }

        //CALLBACK ON CHARACTERISTIC READ
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            super.onCharacteristicRead(gatt, characteristic, status)
            remoteServicesFragment.updateCurrentCharacteristicView(characteristic.uuid)

            // Always log the raw value for diagnostic dumps. Keeps normal handling
            // below intact; the CHAR_DUMP tag is separate so it's easy to grep.
            if (status == BluetoothGatt.GATT_SUCCESS) {
                val bytes = characteristic.value
                val hex = bytes?.joinToString(" ") { "%02X".format(it) } ?: "null"
                val str = try { characteristic.getStringValue(0)?.replace("\n", "\\n") } catch (_: Exception) { null }
                Log.d("CHAR_DUMP", "READ ${characteristic.uuid} len=${bytes?.size ?: 0} hex=[$hex] str=[$str]")
            } else {
                Log.d("CHAR_DUMP", "READ ${characteristic.uuid} FAILED status=$status")
            }

            when (characteristic.uuid) {
                UuidConsts.FIRMWARE_VERSION -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        val fullVersion = characteristic.getStringValue(0) ?: ""
                        Log.d("OTA_DEBUG", "Firmware version raw: '$fullVersion'")

                        // Sanity check per-part: reject segments > 99 (typical bonding noise like 0.250.249).
                        fun isSaneVersion(part: String): Boolean =
                            part.isNotEmpty() && part.split(".").all { seg ->
                                val n = seg.toIntOrNull()
                                n != null && n in 0..99
                            }

                        // Parse "antenna-power" or just "antenna"
                        val versions = fullVersion.split("-", limit = 2)
                        val antennaRaw = versions.getOrNull(0) ?: ""
                        val powerRaw = versions.getOrNull(1) ?: ""
                        val antennaSane = isSaneVersion(antennaRaw)
                        val powerSane = powerRaw.isEmpty() || isSaneVersion(powerRaw)

                        if (!antennaSane) {
                            firmwareReadRetries++
                            Log.w("OTA_DEBUG", "Antenna version looks like garbage ('$antennaRaw' from '$fullVersion'), retry $firmwareReadRetries/$MAX_CHAR_READ_RETRIES")
                            if (firmwareReadRetries <= MAX_CHAR_READ_RETRIES) {
                                handler.postDelayed({
                                    getFirmwareVersionCharacteristic()?.let {
                                        bluetoothGatt?.readCharacteristic(it)
                                    }
                                }, CHAR_READ_RETRY_DELAY_MS)
                            } else {
                                showOperatorOverrideDialog()
                            }
                        } else {
                            firmwareReadRetries = 0
                            firmwareVersionAntenna = antennaRaw
                            // Power: only accept if it parses sanely; otherwise mark unknown (shown orange)
                            firmwareVersionPower = if (powerRaw.isNotEmpty() && powerSane) powerRaw else null
                            if (powerRaw.isNotEmpty() && !powerSane) {
                                Log.w("OTA_DEBUG", "Power version portion '$powerRaw' out of range — marking unknown")
                            }
                            // Keep old behavior for title (show antenna version)
                            firmwareVersion = firmwareVersionAntenna

                            // Start periodic refresh only after first successful read
                            if (!initialReadDone) {
                                initialReadDone = true
                                startFirmwareVersionRefresh()
                            }

                            runOnUiThread {
                                supportActionBar?.title = getDeviceName()
                                updateFirmwareVersionDisplay()
                            }

                            // Read model number after a delay to allow bonding to complete
                            handler.postDelayed({
                                getModelNumberCharacteristic()?.let { modelCharacteristic ->
                                    Log.d("OTA_DEBUG", "Reading model number after firmware version")
                                    bluetoothGatt?.readCharacteristic(modelCharacteristic)
                                }
                            }, 1500)
                        }
                    } else {
                        firmwareReadRetries++
                        Log.w("OTA_DEBUG", "Firmware version read failed (status=$status), retry $firmwareReadRetries/$MAX_CHAR_READ_RETRIES")
                        if (firmwareReadRetries <= MAX_CHAR_READ_RETRIES) {
                            handler.postDelayed({
                                getFirmwareVersionCharacteristic()?.let {
                                    bluetoothGatt?.readCharacteristic(it)
                                }
                            }, CHAR_READ_RETRY_DELAY_MS)
                        } else {
                            showOperatorOverrideDialog()
                        }
                    }
                }
                UuidConsts.MODEL_NUMBER -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        val rawModel = characteristic.getStringValue(0) ?: ""
                        Log.d("OTA_DEBUG", "Model number raw: '$rawModel'")

                        // Sanity check: model should contain letters (e.g. SIN-4-2-20)
                        // Reject garbage from reads during bonding
                        if (rawModel.isEmpty() || !rawModel.any { it.isLetter() }) {
                            modelReadRetries++
                            Log.w("OTA_DEBUG", "Model number looks like garbage ('$rawModel'), retry $modelReadRetries/$MAX_CHAR_READ_RETRIES")
                            if (modelReadRetries <= MAX_CHAR_READ_RETRIES) {
                                handler.postDelayed({
                                    getModelNumberCharacteristic()?.let {
                                        bluetoothGatt?.readCharacteristic(it)
                                    }
                                }, CHAR_READ_RETRY_DELAY_MS)
                            } else {
                                showOperatorOverrideDialog()
                            }
                        } else {
                            modelReadRetries = 0
                            modelNumber = rawModel
                            Log.d("OTA_DEBUG", "Model number accepted: $modelNumber")
                            runOnUiThread {
                                updateModelNumberDisplay()
                            }
                        }
                    } else {
                        modelReadRetries++
                        Log.w("OTA_DEBUG", "Model number read failed (status=$status), retry $modelReadRetries/$MAX_CHAR_READ_RETRIES")
                        if (modelReadRetries <= MAX_CHAR_READ_RETRIES) {
                            handler.postDelayed({
                                getModelNumberCharacteristic()?.let {
                                    bluetoothGatt?.readCharacteristic(it)
                                }
                            }, CHAR_READ_RETRY_DELAY_MS)
                        } else {
                            showOperatorOverrideDialog()
                        }
                    }
                }
            }

            if (viewState == ViewState.REBOOTING_NEW_FIRMWARE) {
                runOnUiThread { supportActionBar?.title = characteristic.getStringValue(0) }
                viewState = ViewState.IDLE
                bluetoothService?.isNotificationEnabled = true

                handler.postDelayed({
                    initServicesFragments(bluetoothGatt?.services.orEmpty())
                    mtuReadType = MtuReadType.VIEW_INITIALIZATION
                    gatt.requestMtu(INITIALIZATION_MTU_VALUE)

                    // Post-OTA: new firmware may now populate DIS. Clear any operator
                    // override so the fresh readings are properly validated against postModel.
                    if (operatorOverrideActive) {
                        Log.d("OTA_DEBUG", "Post-OTA: clearing operator override, re-validating DIS")
                        operatorOverrideActive = false
                        overrideDialogShown = false
                        modelNumber = null
                        firmwareVersion = null
                        firmwareVersionAntenna = null
                        firmwareVersionPower = null
                    }
                    firmwareReadRetries = 0
                    modelReadRetries = 0

                    // Force GATT cache refresh before re-reading — Android caches
                    // characteristic values across reconnects, so without this the
                    // post-OTA reads return the OLD firmware version even though
                    // the device actually has the new one. (Users used to have to
                    // manually disconnect+reconnect to see the correct version.)
                    val cacheCleared = refreshDeviceCache()
                    Log.d("OTA_DEBUG", "Post-OTA GATT cache refresh: $cacheCleared")

                    // Re-read FW version + model after new firmware is loaded. Use a
                    // slightly longer delay when we just cleared the cache — the stack
                    // needs a moment to re-discover before reads return fresh values.
                    handler.postDelayed({
                        getModelNumberCharacteristic()?.let {
                            bluetoothGatt?.readCharacteristic(it)
                        }
                        refreshFirmwareVersion()

                        // refreshDeviceCache() via reflection often fails to actually
                        // invalidate the attribute cache on modern Android. The only
                        // reliable way is a full close() + connectGatt() cycle. Fire
                        // once after the OTA-complete state, ~4s later, so the first
                        // refresh read can log its stale value (for comparison) then
                        // the cold reconnect forces fresh reads on the next session.
                        if (otaCompleted && !postOtaColdReconnectDone && !postOtaColdReconnectPending) {
                            handler.postDelayed({
                                if (!postOtaColdReconnectDone && !postOtaColdReconnectPending) {
                                    Log.d("OTA_DEBUG", "Post-OTA: triggering cold close+reconnect to flush cached DIS")
                                    postOtaColdReconnectPending = true
                                    bluetoothDevice = bluetoothGatt?.device
                                    try { bluetoothGatt?.disconnect() } catch (_: Exception) {}
                                    handler.postDelayed({
                                        try { bluetoothGatt?.close() } catch (_: Exception) {}
                                        bluetoothGatt = null
                                        viewState = ViewState.REBOOTING_NEW_FIRMWARE
                                        bluetoothDevice?.let { device ->
                                            bluetoothService?.connectGatt(device, true, gattCallback)
                                        }
                                    }, 800)
                                }
                            }, 4000)
                        }
                    }, if (cacheCleared) 3000 else 2000)
                }, GATT_FETCH_ON_SERVICE_DISCOVERED_DELAY)
            }
        }

        //CALLBACK ON CHARACTERISTIC WRITE (PROPERTY: WHITE)
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            super.onCharacteristicWrite(gatt, characteristic, status)
            remoteServicesFragment.updateCurrentCharacteristicView(characteristic.uuid, status)

            Log.d("OTA_DEBUG", "onCharacteristicWrite: UUID=${characteristic.uuid}, status=$status, viewState=$viewState")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e("OTA_DEBUG", "Characteristic write failed: status=$status, UUID=${characteristic.uuid}")
                // Silabs Apploader flash-buffer stall on old antenna (2.x) surfaces
                // as status=133 on OTA_DATA, followed by supervision-timeout disconnect.
                // Keep viewState=UPLOADING so the disconnect handler routes to
                // attemptOtaRetry, and arm the slow-priority retry.
                val isOtaDataStall = status == 133 &&
                    characteristic.uuid == UuidConsts.OTA_DATA &&
                    viewState == ViewState.UPLOADING
                if (isOtaDataStall) {
                    Log.w("OTA_DEBUG", "OTA_DATA status=133 stall detected — will retry at BALANCED priority")
                    retryAtBalancedPriority = true
                    // Intentionally leave viewState=UPLOADING so the imminent disconnect
                    // triggers attemptOtaRetry instead of the IDLE "Unexpected" branch.
                } else {
                    showErrorDialog(status)
                    if (viewState == ViewState.UPLOADING) {
                        Log.w("OTA_DEBUG", "Setting viewState from UPLOADING to IDLE due to write failure")
                        viewState = ViewState.IDLE
                    }
                }
            } else {
                when (characteristic.uuid) {
                    UuidConsts.OTA_CONTROL -> {
                        val controlValue = characteristic.value[0]
                        Log.d("OTA_DEBUG", "OTA_CONTROL write successful: value=$controlValue, viewState=$viewState")

                        when (controlValue) {
                            0x00.toByte() -> {
                                Log.d("OTA_DEBUG", "OTA_CONTROL: START command (0x00)")
                                if (viewState == ViewState.REBOOTING) {
                                    Log.d("OTA_DEBUG", "Starting device reload into OTA mode")
                                    reloadDeviceIntoOtaMode()
                                } else if (viewState == ViewState.INITIALIZING_UPLOAD) {
                                    Log.d("OTA_DEBUG", "Transitioning to UPLOADING state")
                                    viewState = ViewState.UPLOADING
                                    startOtaUpload()
                                }
                            }

                            0x03.toByte() -> {
                                Log.d("OTA_DEBUG", "OTA_CONTROL: END command (0x03)")
                                if (viewState == ViewState.UPLOADING) {
                                    Log.d("OTA_DEBUG", "OTA upload completed, setting state to IDLE")
                                    viewState = ViewState.IDLE
                                    // Clear retry bookkeeping — upload succeeded. Otherwise
                                    // onServicesDiscovered REBOOTING_NEW_FIRMWARE sees
                                    // otaRetryCount > 0 after the device reboots and
                                    // treats the successful OTA as a failure to retry.
                                    otaRetryCount = 0
                                    retryAtBalancedPriority = false
                                    if (boolFullOTA) {
                                        Log.d("OTA_DEBUG", "Full OTA: preparing for next upload")
                                        prepareForNextUpload()
                                    } else {
                                        Log.d("OTA_DEBUG", "Single OTA: automatically completing")
                                        runOnUiThread {
                                            otaProgressDialog?.toggleEndButton(isEnabled = true)
                                            // Automatically complete OTA after a short delay to show 100% completion
                                            handler.postDelayed({
                                                Log.d("OTA_DEBUG", "Auto-completing OTA process - no manual intervention needed")
                                                otaProgressCallback.onEndButtonClicked()
                                            }, 1500) // 1.5 second delay to show completion
                                        }
                                    }
                                }
                            }

                            else -> {
                                Log.w("OTA_DEBUG", "Unknown OTA_CONTROL command: $controlValue")
                            }
                        }
                    }

                    UuidConsts.OTA_DATA -> {
                        Log.d("OTA_DEBUG", "OTA_DATA write successful, handling reliable upload response")
                        handleReliableUploadResponse()
                    }
                }
            }
            // Don't read-back OTA_DATA / OTA_CONTROL after each write — it clogs the
            // BLE operation queue with thousands of reads during upload, stalling the
            // transfer on older devices. Only do it for other characteristics where
            // the UI wants to refresh the displayed value.
            if (characteristic.uuid != UuidConsts.OTA_DATA &&
                characteristic.uuid != UuidConsts.OTA_CONTROL) {
                bluetoothGatt?.readCharacteristic(characteristic)
            }
        }

        //CALLBACK ON DESCRIPTOR WRITE
        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            super.onDescriptorWrite(gatt, descriptor, status)
            remoteServicesFragment.updateDescriptorView(descriptor)
        }

        //CALLBACK ON DESCRIPTOR READ
        override fun onDescriptorRead(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            super.onDescriptorRead(gatt, descriptor, status)
            remoteServicesFragment.updateDescriptorView(descriptor)
        }

        //CALLBACK ON CHARACTERISTIC CHANGED VALUE (READ - CHARACTERISTIC NOTIFICATION)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            super.onCharacteristicChanged(gatt, characteristic)
            remoteServicesFragment.updateCharacteristicView(characteristic)
        }

        //CALLBACK ON SERVICES DISCOVERED
        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            super.onServicesDiscovered(gatt, status)
            bluetoothGatt = gatt
            discoveryPending = false

            Log.d("OTA_DEBUG", "onServicesDiscovered: status=$status, viewState=$viewState, servicesCount=${gatt.services.size}")

            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e("OTA_DEBUG", "Services discovery failed: status=$status")
                showErrorDialog(status)
            } else {
                printServicesInfo(gatt)
                otaDataCharPresent = (gatt.getService(UuidConsts.OTA_SERVICE)
                    ?.getCharacteristic(UuidConsts.OTA_DATA) != null)

                Log.d("OTA_DEBUG", "OTA service present: ${gatt.getService(UuidConsts.OTA_SERVICE) != null}")
                Log.d("OTA_DEBUG", "OTA control characteristic present: ${gatt.getService(UuidConsts.OTA_SERVICE)?.getCharacteristic(UuidConsts.OTA_CONTROL) != null}")
                Log.d("OTA_DEBUG", "OTA data characteristic present: $otaDataCharPresent")

                when (viewState) {
                    ViewState.REFRESHING_SERVICES -> {
                        Log.d("OTA_DEBUG", "Services refreshed, updating UI")
                        handler.postDelayed({
                            runOnUiThread { initServicesFragments(bluetoothGatt?.services.orEmpty()) }
                        }, GATT_FETCH_ON_SERVICE_DISCOVERED_DELAY)
                        viewState = ViewState.IDLE
                    }

                    ViewState.IDLE -> {
                        Log.d("OTA_DEBUG", "Normal services discovery in IDLE state")
                        handler.postDelayed({
                            initServicesFragments(bluetoothGatt?.services.orEmpty())
                            dumpAllCharacteristics()
                            mtuReadType = MtuReadType.VIEW_INITIALIZATION
                            gatt.requestMtu(INITIALIZATION_MTU_VALUE)
                        }, GATT_FETCH_ON_SERVICE_DISCOVERED_DELAY)
                    }

                    ViewState.REBOOTING -> {
                        Log.d("OTA_DEBUG", "Device rebooted for OTA, initializing upload")
                        viewState = ViewState.INITIALIZING_UPLOAD
                        mtuReadType = MtuReadType.UPLOAD_INITIALIZATION
                        val result = bluetoothGatt?.requestMtu(INITIALIZATION_MTU_VALUE)
                        Log.d("OTA_DEBUG", "MTU request for upload initialization: $result")
                    }

                    ViewState.REBOOTING_NEW_FIRMWARE -> {
                        handler.removeCallbacks(reconnectTimeoutRunnable)

                        // Cold-reconnect completed (forced after full OTA to flush
                        // Android's attribute cache). Don't re-run the post-OTA
                        // branch — just do a fresh view-init like the IDLE path.
                        if (postOtaColdReconnectPending) {
                            Log.d("OTA_DEBUG", "Post-OTA cold reconnect: services discovered, running fresh view init")
                            postOtaColdReconnectPending = false
                            postOtaColdReconnectDone = true
                            viewState = ViewState.IDLE
                            handler.postDelayed({
                                initServicesFragments(bluetoothGatt?.services.orEmpty())
                                mtuReadType = MtuReadType.VIEW_INITIALIZATION
                                gatt.requestMtu(INITIALIZATION_MTU_VALUE)
                            }, GATT_FETCH_ON_SERVICE_DISCOVERED_DELAY)
                            return
                        }

                        // Resumed mid-handoff after a link drop. Let the device settle
                        // longer than the initial 3s since the BLE stack is still coming
                        // up on the newly-booted firmware.
                        if (pendingPowerOta) {
                            viewState = ViewState.IDLE
                            val alreadyBonded = bluetoothGatt?.device?.bondState ==
                                BluetoothDevice.BOND_BONDED
                            val delay = if (alreadyBonded) 5_000L else 60_000L
                            Log.d("OTA_DEBUG", "[BOTH 2/2] Reconnected after handoff drop — scheduling power OTA (alreadyBonded=$alreadyBonded, delay=${delay}ms)")
                            val runnable = Runnable {
                                if (pendingPowerOta) {
                                    Log.d("OTA_DEBUG", "[BOTH 2/2] Fallback timer fired")
                                    pendingPowerOta = false
                                    pendingPowerOtaFallbackRunnable = null
                                    checkForOtaCharacteristic()
                                }
                            }
                            pendingPowerOtaFallbackRunnable?.let { handler.removeCallbacks(it) }
                            pendingPowerOtaFallbackRunnable = runnable
                            handler.postDelayed(runnable, delay)
                            return
                        }

                        // If this is a retry after upload disconnect, restart the OTA
                        if (otaRetryCount > 0) {
                            Log.d("OTA_DEBUG", "Reconnected after upload failure, retrying OTA (attempt $otaRetryCount/$MAX_OTA_RETRIES)")
                            viewState = ViewState.IDLE
                            handler.postDelayed({
                                checkForOtaCharacteristic()
                            }, 3000)
                        } else {

                        Log.d("OTA_DEBUG", "Device rebooted with new firmware, reading device name")

                        val selection = com.siliconlabs.bledemo.features.firmware_browser.domain.FirmwareSelection
                        if (selection.pendingSecondOta) {
                            // First OTA done (antenna), now start second (power)
                            Log.d("OTA_DEBUG", "[BOTH 1/2 done] Antenna OTA complete. Switching to power file: name='${selection.secondFileName}' path='${selection.secondFilePath}' exists=${java.io.File(selection.secondFilePath).exists()}")
                            antennaOtaCompleted = true
                            otaRetryCount = 0
                            selection.pendingSecondOta = false
                            globalOtaFilePath = selection.secondFilePath
                            globalOtaFileName = selection.secondFileName
                            // Clear path to prevent resetOtaState from treating the OTA as
                            // still pending on a subsequent activity recreate. Keep the name
                            // so the summary bar keeps showing the power filename.
                            selection.secondFilePath = ""

                            val strings = com.siliconlabs.bledemo.features.firmware_browser.domain.UiStrings
                            runOnUiThread {
                                showOtaWaitingOverlay(strings.statusSecondOta)
                                binding.tvOperatorStatus.text = strings.statusSecondOta
                                binding.tvOperatorStatus.setTextColor(
                                    ContextCompat.getColor(this@DeviceServicesActivity, R.color.blue_primary)
                                )
                            }

                            // Wait for device to stabilize, then start second OTA.
                            // pendingPowerOta acts as a safety net: if the link drops
                            // during the wait, STATE_DISCONNECTED IDLE reconnects us
                            // instead of finishing the activity, and onServicesDiscovered
                            // re-arms the power OTA.
                            //
                            // Delay selection:
                            //  - Already bonded (bond persists through reboot) → 3s
                            //    settle, no bond event will fire.
                            //  - Not bonded → 60s fallback, cancelled by
                            //    BOND_BONDING (so we wait indefinitely for
                            //    BOND_BONDED); gives operator time to press the
                            //    physical confirm button + Android pair dialog.
                            pendingPowerOta = true
                            viewState = ViewState.IDLE
                            val alreadyBonded = bluetoothGatt?.device?.bondState ==
                                BluetoothDevice.BOND_BONDED
                            val delay = if (alreadyBonded) 3_000L else 60_000L
                            Log.d("OTA_DEBUG", "[BOTH 2/2] Arming fallback: alreadyBonded=$alreadyBonded, delay=${delay}ms")
                            val runnable = Runnable {
                                val gattOk = bluetoothGatt != null &&
                                    bluetoothGatt?.getService(UuidConsts.OTA_SERVICE) != null
                                if (!pendingPowerOta) {
                                    Log.d("OTA_DEBUG", "[BOTH 2/2] timer fired but pendingPowerOta already cleared")
                                } else if (!gattOk) {
                                    Log.w("OTA_DEBUG", "[BOTH 2/2] timer fired but GATT not connected, waiting for reconnect")
                                } else {
                                    Log.d("OTA_DEBUG", "[BOTH 2/2] Firing second OTA: globalOtaFilePath='$globalOtaFilePath' viewState=$viewState otaInProgress=$otaInProgress")
                                    pendingPowerOta = false
                                    pendingPowerOtaFallbackRunnable = null
                                    checkForOtaCharacteristic()
                                }
                            }
                            pendingPowerOtaFallbackRunnable?.let { handler.removeCallbacks(it) }
                            pendingPowerOtaFallbackRunnable = runnable
                            handler.postDelayed(runnable, delay)
                        } else {
                            // Check if this was a Power OTA — need to wait for serial transfer + reboot
                            val isPowerOta = isPowerCardOta()
                            if (isPowerOta && !powerRebootHandled) {
                                Log.d("OTA_DEBUG", "Power OTA: waiting for serial transfer to Power PCB and reboot")
                                val strings = com.siliconlabs.bledemo.features.firmware_browser.domain.UiStrings
                                runOnUiThread {
                                    showOtaWaitingOverlay(strings.statusPowerTransfer)
                                }
                                awaitingPowerReboot = true
                                viewState = ViewState.IDLE
                                // Timeout if power PCB never kicks BLE
                                handler.postDelayed(powerTransferTimeoutRunnable, POWER_TRANSFER_TIMEOUT_MS)
                            } else {
                                otaRetryCount = 0
                                otaInProgress = false
                                otaCompleted = true
                            }
                        }
                        bluetoothGatt?.readCharacteristic(getDeviceNameCharacteristic())
                        } // end else (not a retry)
                    }

                    else -> {
                        Log.w("OTA_DEBUG", "Services discovered in unexpected state: $viewState")
                    }
                }
            }
        }
    }

    private fun showInitializationInfo() {
        runOnUiThread { otaLoadingDialog?.updateMessage(getString(R.string.ota_rebooting_text)) }
        handler.postDelayed({
            runOnUiThread { otaLoadingDialog?.updateMessage(getString(R.string.ota_loading_text)) }
        }, 1500)
    }

    private fun startOtaUpload() {
        Log.d("OTA_DEBUG", "startOtaUpload: Starting OTA upload process")

        // Acquire wake lock to keep device awake during OTA
        acquireWakeLock()

        hideOtaLoadingDialog()
        otafile = readChosenFile()

        if (otafile == null) {
            Log.e("OTA_DEBUG", "OTA file is null! Cannot start upload.")
            releaseWakeLock()
            return
        }

        Log.d("OTA_DEBUG", "OTA file loaded: ${otafile!!.size} bytes, reliable=$reliable")

        pack = 0
        if (reliable) {
            setupMtuDivisible()
            Log.d("OTA_DEBUG", "Reliable mode: MTU=$MTU, mtuDivisible=$mtuDivisible")
        } else {
            Log.d("OTA_DEBUG", "Non-reliable mode: MTU=$MTU")
        }

        hideOtaProgressDialog()
        showOtaProgressDialog(
            OtaProgressDialog.OtaInfo(
                prepareFilename(),
                otafile?.size,
                if (reliable) mtuDivisible else MTU,
                doubleStepUpload,
                if (doubleStepUpload) stackPath != "" else true
            )
        )

        Log.d("OTA_DEBUG", "Starting upload thread")
        Thread {
            Thread.sleep(DIALOG_DELAY)
            Log.d("OTA_DEBUG", "Upload thread started, beginning data transfer")
            if (reliable) {
                Log.d("OTA_DEBUG", "Using reliable upload method")
                otaWriteDataReliable()
            } else {
                Log.d("OTA_DEBUG", "Using non-reliable upload method")
                writeOtaData(otafile)
            }
        }.start()
    }

    private fun reloadDeviceIntoOtaMode() {
        showOtaLoadingDialog(getString(R.string.ota_resetting_text))
        reconnect()
    }

    private fun setupMtuDivisible() {
        var minus = 0
        do {
            mtuDivisible = MTU - 3 - minus
            minus++
        } while (mtuDivisible % 4 != 0)
    }

    private fun handleReliableUploadResponse() {
        if (reliable) {
            pack += mtuDivisible
            Log.d("OTA_DEBUG", "handleReliableUploadResponse: pack=$pack, fileSize=${otafile?.size}, remaining=${(otafile?.size ?: 0) - pack}")

            if (pack <= otafile?.size!! - 1) {
                Log.d("OTA_DEBUG", "Continuing reliable upload, next packet")
                otaWriteDataReliable()
            } else if (pack > otafile?.size!! - 1) {
                Log.d("OTA_DEBUG", "Reliable upload complete, stopping progress and sending END command")
                handler.post {
                    runOnUiThread {
                        otaProgressDialog?.stopUploading()
                    }
                }
                retryAttempts = 0
                writeOtaControl(OTA_CONTROL_END_COMMAND)
            }
        } else {
            Log.w("OTA_DEBUG", "handleReliableUploadResponse called but reliable mode is disabled")
        }
    }

    private fun prepareForNextUpload() {
        viewState = ViewState.REBOOTING
        stackPath = ""

        hideOtaProgressDialog()
        showOtaLoadingDialog(getString(R.string.ota_loading_text))
        bluetoothGatt?.disconnect()
        handler.postDelayed({ reconnect() }, 500)
    }

    private fun showErrorDialog(status: Int) {
        releaseWakeLock()
        runOnUiThread {
            errorDialog = ErrorDialog(status, object : OtaErrorCallback {
                override fun onDismiss() {
                    bluetoothGatt?.disconnect() ?: finish()
                    errorDialog = null
                }
            })
            errorDialog?.show(supportFragmentManager, "ota_error_dialog")
        }
    }

    /**
     * ACTIVITY STATES MACHINE
     */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDeviceServicesBinding.inflate(LayoutInflater.from(this))
        setContentView(binding.root)

        bluetoothDevice = intent.getParcelableExtra(CONNECTED_DEVICE)
        devicesAdapterRecyclerViewItemPos = intent.getIntExtra(RECYCLERVIEW_POSITION,-1)
        setupBottomNavigation()
        setupActionBar()
        setupUiListeners()
        registerReceivers()
        handler = Handler(Looper.getMainLooper())

        // Reset OTA progress state for this new device session
        // (FirmwareSelection is a singleton — stale flags from a previous OTA persist)
        resetOtaState()

        showCharacteristicLoadingAnimation(getString(R.string.debug_mode_device_loading_gatt_info))
        updateDeviceFirmwareSelectionBar()
        setupOperatorView()
        bindBluetoothService()
        scanFragmentViewModel = ScanFragmentViewModel(this@DeviceServicesActivity)
    }

    private fun setupBottomNavigation() {
        supportFragmentManager.beginTransaction().apply {
            add(R.id.services_fragment_container, localServicesFragment)
            hide(localServicesFragment)
            add(R.id.services_fragment_container, remoteServicesFragment)
        }.commit()


        binding.servicesBottomNav.setOnNavigationItemSelectedListener { item ->
            (when (item.itemId) {
                R.id.services_nav_remote -> {
                    toggleRemoteActions(isRemoteFragmentOn = true)
                    supportActionBar?.title = getDeviceName()
                    remoteServicesFragment
                }

                R.id.services_nav_local -> {
                    toggleRemoteActions(isRemoteFragmentOn = false)
                    supportActionBar?.title = bluetoothService?.bluetoothAdapter?.name
                    localServicesFragment
                }

                else -> null
            })?.let { newFragment ->
                supportFragmentManager.beginTransaction().apply {
                    hide(activeFragment)
                    show(newFragment)
                }.commit()
                activeFragment = newFragment
                true
            } ?: false
        }
    }

    private fun toggleRemoteActions(isRemoteFragmentOn: Boolean) {
        val createBondMenu = menu?.findItem(R.id.menu_create_bond)

        createBondMenu?.isVisible = isRemoteFragmentOn

        binding.connectionInfo.visibility =
            if (isRemoteFragmentOn) View.VISIBLE
            else View.GONE
        toggleMenuItemsVisibility(isRemoteFragmentOn)
    }

    private fun toggleMenuItemsVisibility(areVisible: Boolean) {
        menu?.children?.forEach {
            it.isVisible = areVisible
        }
    }

    private fun setupActionBar() {
        setSupportActionBar(binding.toolbar)
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowHomeEnabled(true)
            title = getDeviceName()
        }
    }

    private fun setupUiListeners() {
        binding.tvOtaFirmware.setOnClickListener {
            if (isUiCreated) checkForOtaCharacteristic()
        }
        binding.btnDisconnect.setOnClickListener {
            bluetoothGatt?.disconnect()
            bluetoothService?.disconnectGatt(bluetoothDevice?.address ?: "")
            finish()
        }
    }

    private fun checkForOtaCharacteristic() {
        if (getOtaControlCharacteristic() != null) {
            // Check if we have a global OTA file selected
            if (globalOtaFilePath.isNotEmpty()) {
                // Skip config dialog and start OTA directly
                startDirectOta()
            } else {
                // No file selected, show file selection dialog
                showMessage(com.siliconlabs.bledemo.features.firmware_browser.domain.UiStrings.otaNoFileSelected)
            }
        } else {
            OtaCharacteristicMissingDialog().show(
                supportFragmentManager,
                "ota_characteristic_missing_dialog"
            )
        }
    }

    private fun startDirectOta() {
        Log.d("OTA_DEBUG", "Starting direct OTA with file: $globalOtaFileName")
        // Truncate persistent OTA log only at the start of a *new* upload.
        // The retry path also re-enters startDirectOta but we don't want to
        // wipe the log mid-OTA; gate on otaInProgress.
        if (!otaInProgress) {
            com.siliconlabs.bledemo.features.firmware_browser.domain.OtaFileLogger
                .markOtaStarted()
            otaWasRun = true
            otaResultRecorded = false
        }
        otaInProgress = true

        if (viewState == ViewState.IDLE) {
            // Set the app path to the global OTA file path
            appPath = globalOtaFilePath

            // Always reliable mode. Non-reliable (WRITE_NO_RESPONSE) streaming was
            // flaky — Android's LL queue filled up and packets were dropped, resulting
            // in truncated images the bootloader rolled back.
            reliable = true
            Log.d("OTA_DEBUG", "OTA mode: reliable=$reliable, otaDataCharPresent=$otaDataCharPresent")

            // Normal Silabs flow: if OTA_DATA is already visible, the device is either
            // already in OTA mode or always exposes the OTA service — either way, skip
            // the explicit reboot and go straight to upload initialization (single 0x00
            // write in INITIALIZING_UPLOAD). Adding an extra reboot here causes some
            // devices to discard the subsequent image.
            // Exception: operator-override devices (unknown pre-image e.g. antenna 2.22)
            // need the explicit reboot-via-0x00 because writing 0x00 a second time in
            // their bootloader causes another reboot, so we can't use the single-write
            // path — those get the explicit REBOOTING step with "skip second 0x00"
            // handling in onMtuChanged UPLOAD_INITIALIZATION.
            if (otaDataCharPresent && !operatorOverrideActive) {
                Log.d("OTA_DEBUG", "OTA data char already visible — going straight to upload initialization")
                viewState = ViewState.INITIALIZING_UPLOAD
                mtuReadType = MtuReadType.UPLOAD_INITIALIZATION
                val result = bluetoothGatt?.requestMtu(INITIALIZATION_MTU_VALUE)
                Log.d("OTA_DEBUG", "MTU request result: $result")
            } else {
                Log.d("OTA_DEBUG", "Writing START control to reboot into OTA mode")
                viewState = ViewState.REBOOTING
                writeOtaControl(OTA_CONTROL_START_COMMAND)
            }
        } else {
            Log.w("OTA_DEBUG", "Cannot start OTA: device not in IDLE state (current: $viewState)")
            showMessage(com.siliconlabs.bledemo.features.firmware_browser.domain.UiStrings.otaDeviceNotReady)
        }
    }

    private fun registerReceivers() {
        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        registerReceiver(
            bondStateChangeListener,
            IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        )
    }

    private fun displayBondState(newState: Int? = null) {

        val state = newState ?: bluetoothGatt?.device?.bondState ?: BluetoothDevice.BOND_NONE
        val createBondMenu = menu?.findItem(R.id.menu_create_bond)
        when (state) {
            BluetoothDevice.BOND_BONDED -> {

                binding.tvBondState.text = getString(R.string.bonded)

                createBondMenu?.isEnabled = true
                createBondMenu?.title = getString(R.string.delete_bond)

            }

            BluetoothDevice.BOND_BONDING -> {
                binding.tvBondState.text = getString(R.string.bonding)

                createBondMenu?.isEnabled = false
                createBondMenu?.title = getString(R.string.bonding)
            }

            BluetoothDevice.BOND_NONE -> {
                binding.tvBondState.text = getString(R.string.not_bonded)

                createBondMenu?.isEnabled = true
                createBondMenu?.title = getString(R.string.create_bond)

            }

            else -> {}
        }
    }

    private fun getOtaControlCharacteristic(): BluetoothGattCharacteristic? {
        return bluetoothGatt?.getService(UuidConsts.OTA_SERVICE)
            ?.getCharacteristic(UuidConsts.OTA_CONTROL)
    }

    private fun getDeviceNameCharacteristic(): BluetoothGattCharacteristic? {
        return bluetoothGatt?.getService(UuidConsts.GENERIC_ACCESS)
            ?.getCharacteristic(UuidConsts.DEVICE_NAME)
    }

    private var characteristicDumpDone = false

    private fun dumpAllCharacteristics() {
        if (characteristicDumpDone) return
        characteristicDumpDone = true
        val services = bluetoothGatt?.services.orEmpty()
        Log.d("CHAR_DUMP", "=== Dumping ${services.size} services on ${bluetoothGatt?.device?.address} ===")
        var delay = 500L
        for (service in services) {
            Log.d("CHAR_DUMP", "Service: ${service.uuid}")
            for (char in service.characteristics) {
                val props = char.properties
                val propStr = buildString {
                    if ((props and BluetoothGattCharacteristic.PROPERTY_READ) != 0) append("R ")
                    if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0) append("W ")
                    if ((props and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) append("WNR ")
                    if ((props and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) append("N ")
                    if ((props and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) append("I ")
                }
                Log.d("CHAR_DUMP", "  Char ${char.uuid} props=$propStr")
                if ((props and BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                    val charRef = char
                    handler.postDelayed({
                        try {
                            bluetoothGatt?.readCharacteristic(charRef)
                        } catch (e: Exception) {
                            Log.w("CHAR_DUMP", "read failed for ${charRef.uuid}: ${e.message}")
                        }
                    }, delay)
                    delay += 350
                }
            }
        }
        Log.d("CHAR_DUMP", "=== Queued ${delay / 350} reads; values will appear over next ${delay}ms ===")
    }

    private fun getFirmwareVersionCharacteristic(): BluetoothGattCharacteristic? {
        return bluetoothGatt?.getService(UuidConsts.DEVICE_INFORMATION)
            ?.getCharacteristic(UuidConsts.FIRMWARE_VERSION)
    }

    private fun getModelNumberCharacteristic(): BluetoothGattCharacteristic? {
        return bluetoothGatt?.getService(UuidConsts.DEVICE_INFORMATION)
            ?.getCharacteristic(UuidConsts.MODEL_NUMBER)
    }

    private fun setActivityResult() {
        setResult(REFRESH_INFO_RESULT_CODE, Intent().apply {
            putExtra(CONNECTED_DEVICE, bluetoothDevice)
            putExtra(
                CONNECTION_STATE,
                if (bluetoothService?.isGattConnected(bluetoothDevice?.address) == true) BluetoothGatt.STATE_CONNECTED
                else BluetoothGatt.STATE_DISCONNECTED
            )
        })
    }

    override fun onResume() {
        super.onResume()
        bluetoothService?.apply {
            registerGattCallback(gattCallback)
            if (!isGattConnected()) {
                showMessage(R.string.toast_debug_connection_failed)
                finish()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        bluetoothService?.unregisterGattCallback()
        // Stop periodic firmware version refresh when paused
        stopFirmwareVersionRefresh()
    }

    override fun onDestroy() {
        super.onDestroy()

        mtuRequestDialog?.dismiss()
        otaConfigDialog?.dismiss()
        hideOtaProgressDialog()
        hideOtaLoadingDialog()
        errorDialog?.dismiss()

        // Stop firmware version refresh
        stopFirmwareVersionRefresh()

        unregisterReceivers()
        bluetoothService?.isNotificationEnabled = true
        bluetoothBinding?.unbind()
    }

    override fun finish() {
        releaseWakeLock()
        hideOtaLoadingDialog()
        hideOtaProgressDialog()
        setActivityResult()
        super.finish()
    }

    private fun unregisterReceivers() {
        unregisterReceiver(bluetoothReceiver)
        unregisterReceiver(bondStateChangeListener)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_device_services, menu)
        this.menu = menu
        return true
    }

    override fun onPrepareOptionsMenu(menu: Menu?): Boolean {
        // Now it's safe to call displayBondState because the menu is ready
        displayBondState()
        return super.onPrepareOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.show_logs -> showLogFragment()
            R.id.request_priority -> {
                ConnectionRequestDialog(connectionPriority, connectionRequestCallback)
                    .show(supportFragmentManager, CONNECTION_REQUEST_DIALOG_FRAGMENT)
            }

            R.id.request_mtu -> {
                mtuRequestDialog = MtuRequestDialog(MTU, mtuRequestCallback).also {
                    it.show(supportFragmentManager, MTU_REQUEST_DIALOG_FRAGMENT)
                }
            }

            R.id.menu_create_bond -> {
                bluetoothGatt?.device?.let {
                    when (it.bondState) {
                        BluetoothDevice.BOND_BONDED -> askUnbondDevice(it)
                        BluetoothDevice.BOND_NONE -> it.createBond()
                        else -> {}
                    }
                }
            }

            android.R.id.home -> {
                onBackPressed()
                return true
            }

            else -> {}
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onBackPressed() {
        super.onBackPressed()

        if (isLogFragmentOn) {

            binding.fragmentContainer.visibility = View.GONE
            binding.servicesContainer.visibility = View.VISIBLE
            isLogFragmentOn = false
            supportActionBar?.title = getDeviceName()
            toggleMenuItemsVisibility(areVisible = true)
        }
    }

    private val connectionRequestCallback = object : ConnectionRequestDialog.Callback {
        override fun onConnectionPriorityRequested(priority: Int) {
            bluetoothGatt?.requestConnectionPriority(priority)
            connectionPriority = priority
            showMessage(
                getString(
                    when (priority) {
                        BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER -> R.string.connection_priority_low
                        BluetoothGatt.CONNECTION_PRIORITY_BALANCED -> R.string.connection_priority_balanced
                        BluetoothGatt.CONNECTION_PRIORITY_HIGH -> R.string.connection_priority_high
                        else -> R.string.connection_priority_low
                    }
                )
            )
        }
    }

    private val mtuRequestCallback = object : MtuRequestDialog.Callback {
        override fun onMtuRequested(requestedMtu: Int) {
            mtuReadType = MtuReadType.USER_REQUESTED
            bluetoothGatt?.requestMtu(requestedMtu)
        }
    }

    private fun showLogFragment() {
        supportFragmentManager.beginTransaction().apply {
            add(R.id.fragment_container, LogFragment())
            addToBackStack(null)
        }.commit()

        binding.fragmentContainer.visibility = View.VISIBLE
        binding.servicesContainer
        binding.servicesContainer.visibility = View.GONE
        toggleMenuItemsVisibility(areVisible = false)
        isLogFragmentOn = true
    }

    private fun askUnbondDevice(device: BluetoothDevice) {
        if (SharedPrefUtils(this@DeviceServicesActivity).shouldDisplayUnbondDeviceDialog()) {
            val dialog = UnbondDeviceDialog(object : UnbondDeviceDialog.Callback {
                override fun onOkClicked() {
                    unbondDevice(device)
                }
            })
            dialog.show(supportFragmentManager, "dialog_unbond_device")
        } else {
            unbondDevice(device)
        }
    }

    private fun unbondDevice(device: BluetoothDevice) {
        if (!removeBond(device)) {
            if (SharedPrefUtils(this@DeviceServicesActivity).shouldDisplayManualUnbondDeviceDialog()) {
                val dialog = ManualUnbondDeviceDialog(object : ManualUnbondDeviceDialog.Callback {
                    override fun onOkClicked() {
                        try {
                            startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                        } catch (e: ActivityNotFoundException) {
                        }
                    }
                })
                dialog.show(supportFragmentManager, "dialog_unbond_device")
            } else {
                try {
                    startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
                } catch (e: ActivityNotFoundException) {
                }
            }
        }
    }

    private fun removeBond(device: BluetoothDevice): Boolean {
        return try {
            return device::class.java.getMethod("removeBond").invoke(device) as Boolean
        } catch (e: Exception) {
            false
        }
    }

    private fun acquireWakeLock() {
        try {
            if (wakeLock == null) {
                val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = powerManager.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "SilabsOTA:OtaUploadWakeLock"
                )
                wakeLock?.acquire(10 * 60 * 1000L) // 10 minutes max, should be enough for OTA
                Log.d("OTA_DEBUG", "Wake lock acquired")
            }
        } catch (e: Exception) {
            Log.e("OTA_DEBUG", "Error acquiring wake lock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d("OTA_DEBUG", "Wake lock released")
                }
                wakeLock = null
            }
        } catch (e: Exception) {
            Log.e("OTA_DEBUG", "Error releasing wake lock: ${e.message}")
        }
    }

    fun refreshServices() {
        bluetoothGatt?.let {
            remoteServicesFragment.clear()
            localServicesFragment.clear()
            showCharacteristicLoadingAnimation(getString(R.string.debug_mode_device_refreshing_services))

            if (refreshDeviceCache()) {
                handler.postDelayed({
                    viewState = ViewState.REFRESHING_SERVICES
                    it.discoverServices()
                }, CACHE_REFRESH_DELAY)
            } else {
                hideCharacteristicLoadingAnimation()
                showMessage(getString(R.string.refreshing_not_possible))
            }
        }
    }

    private val otaConfigCallback = object : OtaConfigDialog.Callback {
        override fun onOtaPartialFullChanged(doubleStepUpload: Boolean) {
            this@DeviceServicesActivity.doubleStepUpload = doubleStepUpload
        }

        override fun onFileChooserClicked(type: OtaFileType) {
            currentOtaFileType = type
            sendFileChooserIntent()
        }

        override fun onOtaClicked(isReliableMode: Boolean) {
            /* OTA process after clicking button:
            * 0. Request MTU before writing to OTA_CONTROL characteristic (done during view initialization)
            * 1. Write 0x00 to OTA_CONTROL characteristic
            * 2. Device will disconnect on its own (with status 19)
            * 3. Reconnect with device (it will have OTA_DATA characteristic this time)
            * 4. REQUEST MTU AGAIN! (apparently it's super important)
            * 5. Write 0x00 to OTA_CONTROL again
            * 6. Start writing to OTA_DATA characteristic
            * 7. Write 0x03 to OTA_CONTROL to end upload    */

            Log.d("OTA_DEBUG", "OTA clicked: reliableMode=$isReliableMode, viewState=$viewState, otaDataCharPresent=$otaDataCharPresent")

            if (viewState == ViewState.IDLE) {
                Log.d("OTA_DEBUG", "Starting OTA process")
                otaConfigDialog = null
                reliable = isReliableMode

                if (otaDataCharPresent) {
                    Log.d("OTA_DEBUG", "OTA data characteristic already present, starting upload initialization")
                    viewState = ViewState.INITIALIZING_UPLOAD
                    mtuReadType = MtuReadType.UPLOAD_INITIALIZATION
                    val result = bluetoothGatt?.requestMtu(INITIALIZATION_MTU_VALUE)
                    Log.d("OTA_DEBUG", "MTU request result: $result")
                } else {
                    Log.d("OTA_DEBUG", "OTA data characteristic not present, rebooting device to OTA mode")
                    viewState = ViewState.REBOOTING
                    writeOtaControl(OTA_CONTROL_START_COMMAND)
                }
            } else {
                Log.w("OTA_DEBUG", "Cannot start OTA: device not in IDLE state (current: $viewState)")
            }
        }

        override fun onDialogCancelled() {
            otaConfigDialog = null
        }
    }

    private fun sendFileChooserIntent() {
        Intent(Intent.ACTION_GET_CONTENT)
            .apply { type = "*/*" }
            .also {
                startActivityForResult(
                    Intent.createChooser(
                        it,
                        getString(R.string.ota_choose_file)
                    ), FILE_CHOOSER_REQUEST_CODE
                )
            }
    }

    private fun hideOtaProgressDialog() {
        runOnUiThread {
            otaProgressDialog?.dismiss()
            otaProgressDialog = null
        }
    }

    private fun showOtaProgressDialog(info: OtaProgressDialog.OtaInfo) {
        runOnUiThread {
            otaProgressDialog = OtaProgressDialog(otaProgressCallback, info).also {
                it.show(supportFragmentManager, OTA_PROGRESS_DIALOG_FRAGMENT)
            }
        }
    }

    private val otaProgressCallback = object : OtaProgressDialog.Callback {
        override fun onEndButtonClicked() {
            hideOtaProgressDialog()
            releaseWakeLock()

            val strings = com.siliconlabs.bledemo.features.firmware_browser.domain.UiStrings
            showOtaWaitingOverlay(strings.statusRebooting)

            remoteServicesFragment.clear()
            localServicesFragment.clear()

            viewState = ViewState.REBOOTING_NEW_FIRMWARE
            bluetoothGatt?.disconnect()

            showOtaWaitingOverlay(strings.statusReconnecting)
            reconnect(requestRssiUpdates = true)

            // Timeout if device never reconnects
            handler.postDelayed(reconnectTimeoutRunnable, RECONNECT_TIMEOUT_MS)

            // Schedule firmware version refresh after OTA completion
            handler.postDelayed({
                refreshFirmwareVersion()
            }, 5000)
        }
    }

    private fun showOtaConfigDialog() {
        otaConfigDialog = OtaConfigDialog(otaConfigCallback, doubleStepUpload).also {
            it.show(supportFragmentManager, OTA_CONFIG_DIALOG_FRAGMENT)
        }
    }

    private fun hideOtaLoadingDialog() {
        runOnUiThread {
            otaLoadingDialog?.dismiss()
            otaLoadingDialog = null
        }
    }

    private fun showOtaLoadingDialog(message: String, header: String? = null) {
        if (otaLoadingDialog == null) {
            runOnUiThread {
                otaLoadingDialog = OtaLoadingDialog(message, header).also {
                    it.show(supportFragmentManager, OTA_LOADING_DIALOG_FRAGMENT)
                }
            }
        }
    }

    private fun initServicesFragments(services: List<BluetoothGattService>) {
        remoteServicesFragment.init(services)
        localServicesFragment.init(services)
        hideCharacteristicLoadingAnimation()
    }

    private fun printServicesInfo(gatt: BluetoothGatt) {
        Timber.i("onServicesDiscovered(): services count = ${gatt.services.size}")
        gatt.services.forEach { service ->
            Timber.i("onServicesDiscovered(): service UUID = ${service.uuid}, char count = ${service.characteristics.size}")
            service.characteristics.forEach {
                Timber.i("onServicesDiscovered(): characteristic UUID = ${it.uuid}, properties = ${it.properties}")
            }
        }
    }

    private fun writeOtaControl(ctrl: Byte) {
        Log.d("OTA_DEBUG", "writeOtaControl: command=0x${ctrl.toString(16)}, viewState=$viewState")

        if (ctrl == OTA_CONTROL_START_COMMAND) {
            Log.d("OTA_DEBUG", "Disabling notifications for OTA start")
            bluetoothService?.isNotificationEnabled = false
        }

        val controlDelay = when (ctrl) {
            OTA_CONTROL_START_COMMAND -> OTA_CONTROL_START_DELAY
            OTA_CONTROL_END_COMMAND -> OTA_CONTROL_END_DELAY
            else -> 0
        }.toLong()

        Log.d("OTA_DEBUG", "Control command delay: ${controlDelay}ms")

        getOtaControlCharacteristic()?.apply {
            writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            value = byteArrayOf(ctrl)
            Log.d("OTA_DEBUG", "OTA control characteristic prepared, value=[${ctrl.toString(16)}]")
        }.also { characteristic ->
            if (characteristic == null) {
                Log.e("OTA_DEBUG", "OTA control characteristic is null!")
                return
            }

            handler.postDelayed({
                Log.d("OTA_DEBUG", "Writing OTA control characteristic: ctrl = 0x${ctrl.toString(16)}")
                val result = bluetoothGatt?.writeCharacteristic(characteristic)
                Log.d("OTA_DEBUG", "writeCharacteristic result: $result")
                if (result != true) {
                    Log.e("OTA_DEBUG", "Failed to write OTA control characteristic!")
                }
            }, controlDelay)
        }
    }

    @Synchronized
    fun writeOtaData(datathread: ByteArray?) {
        try {
            val value = ByteArray(MTU - 3)
            val start = System.nanoTime()
            var j = 0
            for (i in datathread?.indices!!) {
                value[j] = datathread[i]
                j++
                if (j >= MTU - 3 || i >= (datathread.size - 1)) {
                    var wait = System.nanoTime()
                    val charac =
                        bluetoothGatt?.getService(UuidConsts.OTA_SERVICE)?.getCharacteristic(
                            UuidConsts.OTA_DATA
                        )
                    charac?.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                    val progress = ((i + 1).toFloat() / datathread.size) * 100
                    val bitrate =
                        (((i + 1) * (8.0)).toFloat() / (((wait - start) / 1000000.0).toFloat()))
                    if (j < MTU - 3) {
                        val end = ByteArray(j)
                        System.arraycopy(value, 0, end, 0, j)
                        Log.d(
                            "Progress",
                            "sent " + (i + 1) + " / " + datathread.size + " - " + String.format(
                                "%.1f",
                                progress
                            ) + " % - " + String.format(
                                "%.2fkbit/s",
                                bitrate
                            ) + " - " + Converters.bytesToHexWhitespaceDelimited(end)
                        )
                        runOnUiThread {
                            otaProgressDialog?.updateDataProgress(progress.toInt())
                        }
                        charac?.value = end
                    } else {
                        j = 0
                        Log.d(
                            "Progress",
                            "sent " + (i + 1) + " / " + datathread.size + " - " + String.format(
                                "%.1f",
                                progress
                            ) + " % - " + String.format(
                                "%.2fkbit/s",
                                bitrate
                            ) + " - " + Converters.bytesToHexWhitespaceDelimited(value)
                        )
                        runOnUiThread {
                            otaProgressDialog?.updateDataProgress(progress.toInt())
                        }
                        charac?.value = value
                    }
                    if (bluetoothGatt?.writeCharacteristic(charac)!!) {
                        runOnUiThread { otaProgressDialog?.updateDataRate(bitrate) }
                        while ((System.nanoTime() - wait) / 1000000.0 < delayNoResponse);
                    } else {
                        do {
                            while ((System.nanoTime() - wait) / 1000000.0 < delayNoResponse);
                            wait = System.nanoTime()
                            runOnUiThread {
                                runOnUiThread { otaProgressDialog?.updateDataRate(bitrate) }
                            }
                        } while (!bluetoothGatt?.writeCharacteristic(charac)!!)
                    }
                }
            }
            val end = System.nanoTime()
            val time = (end - start) / 1_000_000.toFloat()
            Log.d("OTA Time - ", "" + time + "s")
            // Let any packets still queued in Android's LL buffer actually transmit
            // before we tell the bootloader "upload done". Without this, END can fire
            // while the last several KB are still in-flight, and the device verifies
            // a truncated image and rolls back to the old firmware.
            Log.d("OTA_DEBUG", "Non-reliable upload submitted, waiting 1s for queue drain before END")
            Thread.sleep(1000)
            runOnUiThread {
                otaProgressDialog?.stopUploading()
            }
            writeOtaControl(OTA_CONTROL_END_COMMAND)
        } catch (e: NullPointerException) {
            e.printStackTrace()
        }
    }

    /**
     * WRITES EBL/GBL FILES TO OTA_DATA CHARACTERISTIC
     */
    @Synchronized
    fun otaWriteDataReliable() {
        Log.d("OTA_DEBUG", "otaWriteDataReliable: pack=$pack, mtuDivisible=$mtuDivisible, fileSize=${otafile?.size}")

        val writearray: ByteArray
        val pgss: Float

        if (pack + mtuDivisible > otafile?.size!! - 1) {
            Log.d("OTA_DEBUG", "Writing final packet (last chunk)")
            /**SET last by 4 */
            var plus = 0
            var last = otafile?.size!! - pack
            do {
                last += plus
                plus++
            } while (last % 4 != 0)

            Log.d("OTA_DEBUG", "Final packet size: $last bytes (padded for 4-byte alignment)")
            writearray = ByteArray(last)
            for ((j, i) in (pack until pack + last).withIndex()) {
                if (otafile?.size!! - 1 < i) {
                    writearray[j] = 0xFF.toByte() // Padding
                } else writearray[j] = otafile!![i]
            }
            pgss = ((pack + last).toFloat() / (otafile?.size!! - 1)) * 100
            Log.d("OTA_DEBUG", "Final packet: bytes $pack to ${pack + last} (${writearray.size} bytes), progress=$pgss%")
        } else {
            Log.d("OTA_DEBUG", "Writing regular packet")
            var j = 0
            writearray = ByteArray(mtuDivisible)
            for (i in pack until pack + mtuDivisible) {
                writearray[j] = otafile!![i]
                j++
            }
            pgss = ((pack + mtuDivisible).toFloat() / (otafile?.size!! - 1)) * 100
            Log.d("OTA_DEBUG", "Regular packet: bytes $pack to ${pack + mtuDivisible} (${writearray.size} bytes), progress=$pgss%")
        }

        val charac = bluetoothGatt?.getService(UuidConsts.OTA_SERVICE)
            ?.getCharacteristic(UuidConsts.OTA_DATA)

        if (charac == null) {
            Log.e("OTA_DEBUG", "OTA_DATA characteristic is null! Cannot continue upload.")
            return
        }

        charac.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT // Reliable mode needs ACK
        charac.value = writearray

        Log.d("OTA_DEBUG", "Writing OTA data packet (${writearray.size} bytes) - reliable mode")
        val result = bluetoothGatt?.writeCharacteristic(charac)
        Log.d("OTA_DEBUG", "writeCharacteristic result: $result")

        if (result != true) {
            Log.e("OTA_DEBUG", "Failed to write OTA data characteristic!")
            return
        }

        val waiting_time = (System.currentTimeMillis() - otatime)
        val bitrate = if (waiting_time > 0) 8 * pack.toFloat() / waiting_time else 0f

        Log.d("OTA_DEBUG", "Data rate: $bitrate kbps, progress: $pgss%")

        if (pack > 0) {
            handler.post {
                runOnUiThread {
                    otaProgressDialog?.let {
                        it.updateDataRate(bitrate)
                        it.updateDataProgress(pgss.toInt())
                    }
                }
            }
        } else {
            Log.d("OTA_DEBUG", "Starting OTA timer")
            otatime = System.currentTimeMillis()
        }
    }

    private fun readChosenFile(): ByteArray? {
        return try {
            val file: File
            if (stackPath != "" && doubleStepUpload) {
                file = File(stackPath)
                boolFullOTA = true
            } else {
                // Use global OTA file path if appPath is not set but global path is available
                val filePath = if (appPath.isNotEmpty()) appPath else globalOtaFilePath
                file = File(filePath)
                boolFullOTA = false
                Log.d("OTA_DEBUG", "Reading OTA file: $filePath (${file.length()} bytes)")
            }
            val fileInputStream = FileInputStream(file)
            val size = fileInputStream.available()
            val temp = ByteArray(size)
            fileInputStream.read(temp)
            fileInputStream.close()
            temp
        } catch (e: Exception) {
            Log.e("OTA_DEBUG", "Couldn't open OTA file: $e")
            null
        }
    }

    private fun prepareFilename(): String {
        return if (stackPath != "" && doubleStepUpload) {
            val last = stackPath.lastIndexOf(File.separator)
            getString(R.string.ota_filename_s, stackPath.substring(last).removePrefix("/"))
        } else if (appPath.isNotEmpty()) {
            val last = appPath.lastIndexOf(File.separator)
            getString(R.string.ota_filename_s, appPath.substring(last).removePrefix("/"))
        } else {
            // Use global filename
            getString(R.string.ota_filename_s, globalOtaFileName)
        }
    }

    private fun refreshDeviceCache(): Boolean {
        return try {
            bluetoothGatt?.javaClass?.getMethod("refresh")?.let {
                val success: Boolean = (it.invoke(bluetoothGatt, *arrayOfNulls(0)) as Boolean)
                Timber.d("refreshDeviceCache(): success: $success")
                success
            } ?: false
        } catch (localException: Exception) {
            Timber.e("refreshDeviceCache(): an exception occurred while refreshing device")
            false
        }
    }

    private fun reconnect(requestRssiUpdates: Boolean = false) {
        bluetoothDevice = bluetoothGatt?.device

        handler.postDelayed({
            runOnUiThread { otaLoadingDialog?.updateMessage(getString(R.string.attempting_connection)) }
            bluetoothDevice?.let { device ->
                bluetoothService?.connectGatt(device, requestRssiUpdates, gattCallback)
            }
        }, RECONNECTION_DELAY)
    }

    private fun showCharacteristicLoadingAnimation(barLabel: String) {
        runOnUiThread {
            binding.btnDisconnect.visibility = View.GONE
            binding.tvBondStateWithRssi.visibility = View.GONE
            binding.flyInBar.apply {
                setOnClickListener { /* this onclicklistener prevents services and characteristics from user interaction before ui is loaded*/ }
                visibility = View.VISIBLE
                startFlyInAnimation(barLabel)
            }
        }
    }

    private fun hideCharacteristicLoadingAnimation() {
        runOnUiThread {

            binding.flyInBar.startFlyOutAnimation(object : FlyInBar.Callback {
                override fun onFlyOutAnimationEnded() {
                    binding.flyInBar.visibility = View.GONE
                    binding.btnDisconnect.visibility = View.VISIBLE
                    binding.tvBondStateWithRssi.visibility = View.VISIBLE
                }
            })
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == WRITE_EXTERNAL_STORAGE_REQUEST_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                showMessage(resources.getString(R.string.permissions_granted_successfully))
                checkForOtaCharacteristic()
            } else {
                showMessage(R.string.permissions_not_granted)
            }
        }
    }

    private fun bindBluetoothService() {
        bluetoothBinding = object : BluetoothService.Binding(this) {
            override fun onBound(service: BluetoothService?) {
                this@DeviceServicesActivity.bluetoothService = service

                bluetoothService?.apply {
                    bluetoothDevice?.address?.let {
                        bluetoothGatt = getActiveConnection(it)?.connection?.gatt
                    }
                    bluetoothGatt?.let {
                        registerGattCallback(true, gattCallback)
                        displayBondState()
                        it.discoverServices()
                    } ?: run {
                        showMessage(R.string.toast_debug_connection_failed)
                        finish()
                    }
                }
            }
        }
        bluetoothBinding?.bind()
    }

    public override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode == Activity.RESULT_OK) {
            when (requestCode) {
                FILE_CHOOSER_REQUEST_CODE -> {
                    val uri = data?.data

                    val filename = try {
                        getFileName(uri)
                    } catch (e: Exception) {
                        ""
                    }

                    filename?.let {
                        if (!hasOtaFileCorrectExtension(filename)) showMessage(getString(R.string.incorrect_file))
                        else prepareOtaFile(uri, currentOtaFileType, it)
                    } ?: showMessage(getString(R.string.incorrect_file))
                }
            }
        }
    }

    @SuppressLint("Range")
    private fun getFileName(uri: Uri?): String? {
        var result: String? = null
        if ((uri?.scheme == "content")) {
            val cursor = contentResolver.query(uri, null, null, null, null)
            cursor.use { c ->
                if (c != null && c.moveToFirst()) {
                    result = c.getString(c.getColumnIndex(OpenableColumns.DISPLAY_NAME))
                }
            }
        }
        if (result == null) {
            result = uri?.path
            val cut = result?.lastIndexOf('/')
            if (cut != -1) {
                result = result?.substring(cut!! + 1)
            }
        }
        return result
    }

    private fun hasOtaFileCorrectExtension(filename: String?): Boolean {
        return filename?.uppercase(Locale.getDefault())?.contains(".GBL")!!
    }

    private fun getDeviceName(): String {
        return bluetoothDevice?.let {
            if (TextUtils.isEmpty(it.name)) getString(R.string.not_advertising_shortcut)
            else it.name
        } ?: getString(R.string.not_advertising_shortcut)
    }

    private fun getDeviceNameWithFirmware(): String {
        val deviceName = getDeviceName()
        return firmwareVersion?.let { version ->
            "$deviceName ($version)"
        } ?: deviceName
    }

    private fun updateFirmwareVersionDisplay() {
        runOnUiThread {
            val validation = selectedValidation

            // Antenna version display — always show; "?" orange when unknown
            val versionAntenna = firmwareVersionAntenna?.takeIf { it.isNotEmpty() } ?: "?"
            binding.tvFirmwareVersionAntenna.apply {
                text = versionAntenna
                visibility = View.VISIBLE
                val expectedVersion = validation?.antennaVersion
                val isUnknown = operatorOverrideActive || versionAntenna == "?"
                val isCorrect = expectedVersion != null && versionAntenna == expectedVersion
                val backgroundColor = when {
                    isUnknown -> ContextCompat.getColor(this@DeviceServicesActivity, R.color.silabs_orange)
                    isCorrect -> ContextCompat.getColor(this@DeviceServicesActivity, R.color.silabs_green)
                    else      -> ContextCompat.getColor(this@DeviceServicesActivity, R.color.silabs_red)
                }
                setBackgroundColor(backgroundColor)
            }

            // Power version display — always show; "?" orange when unknown
            val versionPower = firmwareVersionPower?.takeIf { it.isNotEmpty() } ?: "?"
            binding.tvFirmwareVersionPower.apply {
                text = versionPower
                visibility = View.VISIBLE
                val expectedVersion = validation?.powerVersion
                val isUnknown = operatorOverrideActive || versionPower == "?"
                val isCorrect = expectedVersion != null && versionPower == expectedVersion
                val backgroundColor = when {
                    isUnknown -> ContextCompat.getColor(this@DeviceServicesActivity, R.color.silabs_orange)
                    isCorrect -> ContextCompat.getColor(this@DeviceServicesActivity, R.color.silabs_green)
                    else      -> ContextCompat.getColor(this@DeviceServicesActivity, R.color.silabs_red)
                }
                setBackgroundColor(backgroundColor)
            }

            binding.firmwareVersionsContainer.visibility = View.VISIBLE

            updateOperatorFirmware()
            checkAllVersionsMatch()
        }
    }

    private fun updateModelNumberDisplay() {
        runOnUiThread {
            modelNumber?.let { model ->
                val validation = selectedValidation
                val expectedModel = if (otaCompleted) validation?.postModel else validation?.preModel
                val isValidModel = operatorOverrideActive ||
                    (expectedModel != null && model == expectedModel)

                binding.tvModelNumber.apply {
                    text = model
                    visibility = View.VISIBLE
                    val backgroundColor = if (isValidModel) {
                        ContextCompat.getColor(this@DeviceServicesActivity, R.color.silabs_green)
                    } else {
                        ContextCompat.getColor(this@DeviceServicesActivity, R.color.silabs_red)
                    }
                    setBackgroundColor(backgroundColor)
                }

                if (!isValidModel && validation != null && !otaCompleted && !operatorOverrideActive) {
                    showModelMismatchDialog(model, listOf(expectedModel ?: ""))
                }

                updateOperatorModel()

                // Update firmware display in case it was already read
                if (firmwareVersionAntenna != null || firmwareVersionPower != null) {
                    updateFirmwareVersionDisplay()
                }
            }
        }
    }

    private fun showOperatorOverrideDialog() {
        if (overrideDialogShown || operatorOverrideActive) return
        overrideDialogShown = true
        val strings = com.siliconlabs.bledemo.features.firmware_browser.domain.UiStrings
        runOnUiThread {
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(strings.overridePromptTitle)
                .setMessage(strings.overridePromptMessage)
                .setCancelable(false)
                .setPositiveButton(strings.overrideYes) { dialog, _ ->
                    dialog.dismiss()
                    promptForOverrideCode()
                }
                .setNegativeButton(strings.disconnect) { dialog, _ ->
                    dialog.dismiss()
                    bluetoothGatt?.disconnect()
                    finish()
                }
                .show()
        }
    }

    private fun promptForOverrideCode(errorMessage: String? = null) {
        val strings = com.siliconlabs.bledemo.features.firmware_browser.domain.UiStrings
        runOnUiThread {
            val input = android.widget.EditText(this).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                    android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
                hint = strings.overrideCodeHint
            }
            val container = android.widget.FrameLayout(this).apply {
                val pad = (16 * resources.displayMetrics.density).toInt()
                setPadding(pad, pad / 2, pad, 0)
                addView(input)
            }
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(strings.overrideCodeTitle)
                .apply { if (errorMessage != null) setMessage(errorMessage) }
                .setView(container)
                .setCancelable(false)
                .setPositiveButton(strings.overrideConfirm) { dialog, _ ->
                    if (input.text.toString() == OPERATOR_OVERRIDE_CODE) {
                        Log.d("OTA_DEBUG", "Operator override accepted")
                        operatorOverrideActive = true
                        if (modelNumber.isNullOrEmpty()) modelNumber = "OVERRIDE"
                        if (firmwareVersion.isNullOrEmpty()) firmwareVersion = "?"
                        if (firmwareVersionAntenna.isNullOrEmpty()) firmwareVersionAntenna = "?"
                        if (firmwareVersionPower.isNullOrEmpty()) firmwareVersionPower = "?"
                        runOnUiThread {
                            updateFirmwareVersionDisplay()
                            updateModelNumberDisplay()
                        }
                        dialog.dismiss()
                    } else {
                        Log.w("OTA_DEBUG", "Operator override code incorrect")
                        dialog.dismiss()
                        promptForOverrideCode(strings.overrideIncorrectCode)
                    }
                }
                .setNegativeButton(strings.overrideCancel) { dialog, _ ->
                    dialog.dismiss()
                    bluetoothGatt?.disconnect()
                    finish()
                }
                .show()
        }
    }

    private fun showModelMismatchDialog(deviceModel: String, expectedModels: List<String>) {
        // Guard against the periodic firmware-version refresh (every 10 s) re-firing
        // a model read → updateModelNumberDisplay → this dialog while the previous
        // dialog is still waiting for the operator. Stacking dismisses them all at
        // once if the operator finally taps a button.
        if (modelMismatchDialogShown) return
        modelMismatchDialogShown = true

        val expected = expectedModels.firstOrNull() ?: "NULL"
        val strings = com.siliconlabs.bledemo.features.firmware_browser.domain.UiStrings
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle(strings.modelMismatchTitle)
            .setMessage(String.format(strings.modelMismatchMessage, deviceModel, expected))
            .setCancelable(false)
            .setPositiveButton(strings.modelMismatchOverride) { dialog, _ ->
                dialog.dismiss()
                modelMismatchDialogShown = false
                promptForOverrideCode()
            }
            .setNegativeButton(strings.disconnect) { dialog, _ ->
                dialog.dismiss()
                modelMismatchDialogShown = false
                bluetoothGatt?.disconnect()
                finish()
            }
            .show()
    }

    private fun resetOtaState() {
        val selection = com.siliconlabs.bledemo.features.firmware_browser.domain.FirmwareSelection
        // For a BOTH OTA, restore both antenna and power file paths from the cache.
        // secondFilePath gets cleared at the antenna→power handoff (to stop stale
        // pendingSecondOta restoration on mid-OTA activity recreation), but a
        // *new* device session — operator swapped parts — must start fresh from
        // the antenna, so we rehydrate both paths from the cached files.
        if (selection.cardType == com.siliconlabs.bledemo.features.firmware_browser.domain.CardType.BOTH) {
            val cacheDir = cacheDir
            if (selection.fileName.isNotEmpty()) {
                val antennaFile = java.io.File(cacheDir, selection.fileName)
                if (antennaFile.exists()) {
                    globalOtaFilePath = antennaFile.absolutePath
                    globalOtaFileName = selection.fileName
                }
            }
            if (selection.secondFilePath.isEmpty() && selection.secondFileName.isNotEmpty()) {
                val powerFile = java.io.File(cacheDir, selection.secondFileName)
                if (powerFile.exists()) {
                    selection.secondFilePath = powerFile.absolutePath
                    Log.d("OTA_DEBUG", "resetOtaState: rehydrated secondFilePath from cache (${selection.secondFileName})")
                }
            }
            selection.pendingSecondOta = selection.secondFilePath.isNotEmpty()
        }
        otaCompleted = false
        otaInProgress = false
        otaRetryCount = 0
        antennaOtaCompleted = false
        awaitingPowerReboot = false
        powerRebootHandled = false
        firmwareReadRetries = 0
        modelReadRetries = 0
        operatorOverrideActive = false
        overrideDialogShown = false
        pendingPowerOta = false
        pendingPowerOtaFallbackRunnable?.let { handler.removeCallbacks(it) }
        pendingPowerOtaFallbackRunnable = null
        modelMismatchDialogShown = false
        retryAtBalancedPriority = false
        characteristicDumpDone = false
        postOtaColdReconnectPending = false
        postOtaColdReconnectDone = false
        otaWasRun = false
        otaResultRecorded = false
        Log.d("OTA_DEBUG", "OTA state reset for new device session (cardType=${selection.cardType}, pendingSecondOta=${selection.pendingSecondOta})")
    }

    private fun setupOperatorView() {
        binding.btnStartOta.isEnabled = false
        binding.btnStartOta.alpha = 0.4f
        binding.btnStartOta.setOnClickListener {
            if (isUiCreated) checkForOtaCharacteristic()
        }
        binding.btnOtaRetry.setOnClickListener {
            retryFailedOta()
        }
    }

    private fun retryFailedOta() {
        val selection = com.siliconlabs.bledemo.features.firmware_browser.domain.FirmwareSelection
        val validation = selectedValidation
        val cardType = selection.cardType

        // Determine which card(s) failed and need retrying
        val antennaFailed = validation != null && when (cardType) {
            com.siliconlabs.bledemo.features.firmware_browser.domain.CardType.ANTENNA,
            com.siliconlabs.bledemo.features.firmware_browser.domain.CardType.BOTH ->
                firmwareVersionAntenna == null || firmwareVersionAntenna != validation.antennaVersion
            else -> false
        }
        val powerFailed = validation != null && when (cardType) {
            com.siliconlabs.bledemo.features.firmware_browser.domain.CardType.POWER,
            com.siliconlabs.bledemo.features.firmware_browser.domain.CardType.BOTH ->
                validation.powerVersion.isNotEmpty() &&
                    (firmwareVersionPower == null || firmwareVersionPower != validation.powerVersion)
            else -> false
        }

        Log.d("OTA_DEBUG", "Retry OTA: antennaFailed=$antennaFailed, powerFailed=$powerFailed, cardType=$cardType")

        // Reset OTA state
        otaCompleted = false
        otaInProgress = false
        otaRetryCount = 0
        powerRebootHandled = false
        awaitingPowerReboot = false
        antennaOtaCompleted = false

        // Restore the correct firmware file for the failed card
        if (antennaFailed && powerFailed) {
            // Both failed — need to re-download both (file paths may still be in cache)
            // For now, start with antenna file
            Log.d("OTA_DEBUG", "Both cards failed, restarting full BOTH OTA")
            selection.pendingSecondOta = true
        } else if (powerFailed) {
            // Only power failed — globalOtaFilePath should already be the power file
            Log.d("OTA_DEBUG", "Only power failed, retrying power OTA")
            selection.pendingSecondOta = false
        } else if (antennaFailed) {
            Log.d("OTA_DEBUG", "Only antenna failed, retrying antenna OTA")
            selection.pendingSecondOta = false
        }

        // Hide result overlay and reset colours
        binding.btnOtaRetry.visibility = View.GONE
        binding.tvOtaHeading.text = "Mise à jour OTA en cours..."
        binding.tvOtaHeading.setTextColor(
            ContextCompat.getColor(this, R.color.blue_primary)
        )
        binding.tvOtaWaitingStatus.setTextColor(
            ContextCompat.getColor(this, android.R.color.darker_gray)
        )
        binding.ivNodonSpinning.setImageResource(R.drawable.nodon_logo)

        checkForOtaCharacteristic()
    }

    private fun updateOperatorModel() {
        runOnUiThread {
            val model = modelNumber ?: "—"
            binding.tvOperatorModel.text = model
            val validation = selectedValidation
            val expectedModel = if (otaCompleted) validation?.postModel else validation?.preModel
            val isValid = operatorOverrideActive ||
                (expectedModel != null && model == expectedModel)
            val drawable = if (isValid) R.drawable.ic_circle_green else R.drawable.ic_circle_red
            binding.tvOperatorModel.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0, 0, drawable, 0
            )
            binding.tvOperatorModel.compoundDrawablePadding = 16

            binding.btnStartOta.isEnabled = isValid
            binding.btnStartOta.alpha = if (isValid) 1.0f else 0.4f
            updateOperatorStatus()
            checkAllVersionsMatch()
        }
    }

    private fun isPowerCardOta(): Boolean {
        val selection = com.siliconlabs.bledemo.features.firmware_browser.domain.FirmwareSelection
        val cardType = selection.cardType
        // Power OTA if we selected Power, or if we selected BOTH and antenna is already done (pendingSecondOta was false)
        return cardType == com.siliconlabs.bledemo.features.firmware_browser.domain.CardType.POWER ||
            (cardType == com.siliconlabs.bledemo.features.firmware_browser.domain.CardType.BOTH && !selection.pendingSecondOta)
    }

    private fun checkAllVersionsMatch() {
        runOnUiThread {
            val validation = selectedValidation ?: return@runOnUiThread
            val model = modelNumber ?: return@runOnUiThread
            val antenna = firmwareVersionAntenna
            val power = firmwareVersionPower

            val cardType = com.siliconlabs.bledemo.features.firmware_browser.domain.FirmwareSelection.cardType
            val checks = validation.fieldsToCheck(cardType)

            val expectedModel = if (otaCompleted) validation.postModel else validation.preModel
            // Pre-OTA: gate on pre_model (device must be in starting state).
            // Post-OTA: only require post_model match if this scenario validates it.
            val modelMatch = if (otaCompleted) "post_model" !in checks || model == expectedModel
                             else model == expectedModel
            val antennaMatch = "antenna_version" !in checks ||
                (antenna != null && antenna == validation.antennaVersion)
            val powerMatch = "power_version" !in checks ||
                validation.powerVersion.isEmpty() ||
                (power != null && power == validation.powerVersion)

            if (modelMatch && antennaMatch && powerMatch && !otaCompleted) {
                // All versions already match — no OTA needed
                binding.btnStartOta.isEnabled = false
                binding.btnStartOta.alpha = 0.4f
                binding.btnStartOta.text = com.siliconlabs.bledemo.features.firmware_browser.domain.UiStrings.versionsAlreadyMatch
                binding.btnStartOta.visibility = View.GONE

                val strings = com.siliconlabs.bledemo.features.firmware_browser.domain.UiStrings
                binding.tvOperatorStatus.text = strings.statusAlreadyUpToDate
                binding.tvOperatorStatus.setTextColor(
                    ContextCompat.getColor(this, R.color.silabs_green)
                )
            }

            // Persistent-log lifecycle: when an OTA we just ran is verified
            // good (post-OTA reads match expected versions), discard the log.
            // If we ran an OTA but the post-OTA reads don't match (silent
            // Apploader rollback), preserve it for diagnostics.
            if (otaCompleted && !otaResultRecorded && otaWasRun) {
                if (modelMatch && antennaMatch && powerMatch) {
                    com.siliconlabs.bledemo.features.firmware_browser.domain.OtaFileLogger
                        .markOtaSuccess()
                } else {
                    com.siliconlabs.bledemo.features.firmware_browser.domain.OtaFileLogger
                        .markOtaFailed(
                            "Post-OTA verify mismatch: model=$model/$expectedModel " +
                            "antenna=$antenna/${validation.antennaVersion} " +
                            "power=$power/${validation.powerVersion}",
                            bluetoothGatt?.device?.address ?: bluetoothDevice?.address
                        )
                }
                otaResultRecorded = true
            }
        }
    }

    private var spinAnimation: android.view.animation.Animation? = null

    private val reconnectTimeoutRunnable = Runnable {
        if (viewState == ViewState.REBOOTING_NEW_FIRMWARE) {
            runOnUiThread {
                val strings = com.siliconlabs.bledemo.features.firmware_browser.domain.UiStrings
                binding.ivNodonSpinning.clearAnimation()
                binding.tvOtaWaitingStatus.text = strings.statusReconnectFailed
                binding.tvOtaWaitingStatus.setTextColor(
                    ContextCompat.getColor(this, R.color.silabs_red)
                )
                // Show disconnect button so operator can go back
                binding.btnDisconnect.visibility = View.VISIBLE
            }
        }
    }

    private val powerTransferTimeoutRunnable = Runnable {
        if (awaitingPowerReboot) {
            Log.d("OTA_DEBUG", "Power transfer timeout — device never kicked BLE, disconnecting to force re-read")
            awaitingPowerReboot = false
            powerRebootHandled = true
            // Disconnect and reconnect to get fresh firmware version reads
            viewState = ViewState.REBOOTING_NEW_FIRMWARE
            val strings = com.siliconlabs.bledemo.features.firmware_browser.domain.UiStrings
            runOnUiThread {
                showOtaWaitingOverlay(strings.statusReconnecting)
            }
            bluetoothGatt?.disconnect()
            handler.postDelayed({
                reconnect(requestRssiUpdates = true)
                handler.postDelayed(reconnectTimeoutRunnable, RECONNECT_TIMEOUT_MS)
            }, 1000)
        }
    }

    private fun attemptOtaRetry() {
        otaRetryCount++
        if (otaRetryCount <= MAX_OTA_RETRIES) {
            Log.d("OTA_DEBUG", "Auto-retrying OTA (attempt $otaRetryCount/$MAX_OTA_RETRIES)")
            val strings = com.siliconlabs.bledemo.features.firmware_browser.domain.UiStrings
            runOnUiThread {
                showOtaWaitingOverlay(
                    String.format(strings.statusOtaRetrying, otaRetryCount, MAX_OTA_RETRIES)
                )
            }
            viewState = ViewState.REBOOTING_NEW_FIRMWARE
            reconnect(requestRssiUpdates = true)
            handler.postDelayed(reconnectTimeoutRunnable, RECONNECT_TIMEOUT_MS)
        } else {
            Log.e("OTA_DEBUG", "Max OTA retries ($MAX_OTA_RETRIES) exceeded")
            otaInProgress = false
            com.siliconlabs.bledemo.features.firmware_browser.domain.OtaFileLogger
                .markOtaFailed(
                    "Max OTA retries ($MAX_OTA_RETRIES) exceeded",
                    bluetoothGatt?.device?.address ?: bluetoothDevice?.address
                )
            val strings = com.siliconlabs.bledemo.features.firmware_browser.domain.UiStrings
            runOnUiThread {
                showOtaWaitingOverlay(
                    String.format(strings.statusOtaRetryFailed, MAX_OTA_RETRIES)
                )
                binding.ivNodonSpinning.clearAnimation()
                binding.tvOtaWaitingStatus.setTextColor(
                    ContextCompat.getColor(this@DeviceServicesActivity, R.color.silabs_red)
                )
                binding.btnDisconnect.visibility = View.VISIBLE
            }
        }
    }

    private fun updateOperatorStatus() {
        runOnUiThread {
            val strings = com.siliconlabs.bledemo.features.firmware_browser.domain.UiStrings
            val statusText: String
            val statusColor: Int
            val selection = com.siliconlabs.bledemo.features.firmware_browser.domain.FirmwareSelection
            if (otaCompleted) {
                statusText = strings.statusPostOta
                statusColor = ContextCompat.getColor(this, R.color.silabs_green)
                showOtaResult()
                binding.btnStartOta.isEnabled = false
                binding.btnStartOta.visibility = View.GONE
                binding.btnDisconnect.visibility = View.VISIBLE
            } else if (selection.cardType == com.siliconlabs.bledemo.features.firmware_browser.domain.CardType.BOTH) {
                statusText = strings.statusPreOtaBoth
                statusColor = ContextCompat.getColor(this, R.color.blue_primary)
            } else {
                statusText = strings.statusPreOta
                statusColor = ContextCompat.getColor(this, R.color.blue_primary)
            }
            binding.tvOperatorStatus.text = statusText
            binding.tvOperatorStatus.setTextColor(statusColor)
        }
    }

    private var elapsedTimerRunnable: Runnable? = null
    private var elapsedSeconds = 0

    private fun showOtaWaitingOverlay(message: String) {
        runOnUiThread {
            binding.otaWaitingOverlay.visibility = View.VISIBLE
            binding.spacer.visibility = View.GONE
            binding.btnStartOta.visibility = View.GONE
            binding.btnDisconnect.visibility = View.GONE
            binding.btnOtaRetry.visibility = View.GONE
            binding.tvOtaWaitingStatus.text = message

            if (spinAnimation == null) {
                spinAnimation = android.view.animation.RotateAnimation(
                    0f, 360f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f,
                    android.view.animation.Animation.RELATIVE_TO_SELF, 0.5f
                ).apply {
                    duration = 2000
                    repeatCount = android.view.animation.Animation.INFINITE
                    interpolator = android.view.animation.LinearInterpolator()
                }
            }
            binding.ivNodonSpinning.startAnimation(spinAnimation)
            startElapsedTimer()
        }
    }

    private fun showOtaResult() {
        runOnUiThread {
            stopElapsedTimer()
            binding.ivNodonSpinning.clearAnimation()

            // Only check fields specified by config.ini's [after_X] section for this OTA scenario
            val validation = selectedValidation
            val model = modelNumber ?: ""
            val selection = com.siliconlabs.bledemo.features.firmware_browser.domain.FirmwareSelection
            val cardType = selection.cardType
            val allMatch = if (validation != null) {
                val checks = validation.fieldsToCheck(cardType)
                val modelOk = "post_model" !in checks || model == validation.postModel
                val antennaOk = "antenna_version" !in checks ||
                    (firmwareVersionAntenna != null && firmwareVersionAntenna == validation.antennaVersion)
                val powerOk = "power_version" !in checks ||
                    validation.powerVersion.isEmpty() ||
                    (firmwareVersionPower != null && firmwareVersionPower == validation.powerVersion)
                modelOk && antennaOk && powerOk
            } else true

            if (allMatch) {
                binding.ivNodonSpinning.setImageResource(R.drawable.ic_ota_success)
                binding.tvOtaHeading.text = "Mise à jour réussie"
                binding.tvOtaHeading.setTextColor(
                    ContextCompat.getColor(this, R.color.silabs_green)
                )
                binding.tvOtaWaitingStatus.text = com.siliconlabs.bledemo.features.firmware_browser.domain.UiStrings.statusPostOta
                binding.tvOtaWaitingStatus.setTextColor(
                    ContextCompat.getColor(this, R.color.silabs_green)
                )
            } else {
                binding.ivNodonSpinning.setImageResource(R.drawable.ic_ota_failure)
                binding.tvOtaHeading.text = "Mise à jour échouée"
                binding.tvOtaHeading.setTextColor(
                    ContextCompat.getColor(this, R.color.silabs_red)
                )
                binding.tvOtaWaitingStatus.text = "Vérification post-OTA échouée"
                binding.tvOtaWaitingStatus.setTextColor(
                    ContextCompat.getColor(this, R.color.silabs_red)
                )
                binding.btnOtaRetry.visibility = View.VISIBLE
            }
            binding.tvOtaElapsedTime.visibility = View.GONE
            binding.otaWaitingOverlay.visibility = View.VISIBLE
            binding.spacer.visibility = View.GONE
            binding.btnStartOta.visibility = View.GONE
            binding.btnDisconnect.visibility = View.VISIBLE
        }
    }

    private fun hideOtaWaitingOverlay() {
        runOnUiThread {
            stopElapsedTimer()
            binding.ivNodonSpinning.clearAnimation()
            binding.otaWaitingOverlay.visibility = View.GONE
            binding.spacer.visibility = View.VISIBLE
            binding.btnStartOta.visibility = View.VISIBLE
            binding.btnDisconnect.visibility = View.VISIBLE
        }
    }

    private fun startElapsedTimer() {
        stopElapsedTimer()
        elapsedSeconds = 0
        binding.tvOtaElapsedTime.visibility = View.VISIBLE
        binding.tvOtaElapsedTime.text = "0:00"
        elapsedTimerRunnable = object : Runnable {
            override fun run() {
                elapsedSeconds++
                val mins = elapsedSeconds / 60
                val secs = elapsedSeconds % 60
                binding.tvOtaElapsedTime.text = String.format("%d:%02d", mins, secs)
                handler.postDelayed(this, 1000)
            }
        }
        handler.postDelayed(elapsedTimerRunnable!!, 1000)
    }

    private fun stopElapsedTimer() {
        elapsedTimerRunnable?.let { handler.removeCallbacks(it) }
        elapsedTimerRunnable = null
        binding.tvOtaElapsedTime.visibility = View.GONE
    }

    private fun updateOperatorFirmware() {
        runOnUiThread {
            val validation = selectedValidation

            val antennaVersion = firmwareVersionAntenna?.takeIf { it.isNotEmpty() } ?: "?"
            binding.tvOperatorAntenna.text = antennaVersion
            val antennaExpected = validation?.antennaVersion
            val antennaUnknown = operatorOverrideActive || antennaVersion == "?"
            val antennaCorrect = antennaExpected != null && antennaVersion == antennaExpected
            val antennaDrawable = when {
                antennaUnknown -> R.drawable.ic_circle_orange
                antennaCorrect -> R.drawable.ic_circle_green
                else           -> R.drawable.ic_circle_red
            }
            binding.tvOperatorAntenna.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0, 0, antennaDrawable, 0
            )
            binding.tvOperatorAntenna.compoundDrawablePadding = 16

            val powerVersion = firmwareVersionPower?.takeIf { it.isNotEmpty() } ?: "?"
            binding.tvOperatorPower.text = powerVersion
            val powerExpected = validation?.powerVersion
            val powerUnknown = operatorOverrideActive || powerVersion == "?"
            val powerCorrect = powerExpected != null && powerVersion == powerExpected
            val powerDrawable = when {
                powerUnknown -> R.drawable.ic_circle_orange
                powerCorrect -> R.drawable.ic_circle_green
                else         -> R.drawable.ic_circle_red
            }
            binding.tvOperatorPower.setCompoundDrawablesRelativeWithIntrinsicBounds(
                0, 0, powerDrawable, 0
            )
            binding.tvOperatorPower.compoundDrawablePadding = 16
        }
    }

    private fun updateDeviceFirmwareSelectionBar() {
        val selection = com.siliconlabs.bledemo.features.firmware_browser.domain.FirmwareSelection
        val strings = com.siliconlabs.bledemo.features.firmware_browser.domain.UiStrings
        if (selection.isSelected()) {
            binding.deviceFirmwareSelectionBar.visibility = View.VISIBLE
            val cardLabel = when (selection.cardType) {
                com.siliconlabs.bledemo.features.firmware_browser.domain.CardType.ANTENNA -> strings.antenna
                com.siliconlabs.bledemo.features.firmware_browser.domain.CardType.POWER -> strings.power
                com.siliconlabs.bledemo.features.firmware_browser.domain.CardType.BOTH -> strings.both
                else -> ""
            }
            binding.tvDeviceSelectedProduct.text = listOf(selection.productName, selection.pnName, cardLabel)
                .filter { it.isNotBlank() }
                .joinToString(" — ")
            if (selection.cardType == com.siliconlabs.bledemo.features.firmware_browser.domain.CardType.BOTH) {
                binding.tvDeviceSelectedFirmware.text = "${strings.antenna}: ${selection.fileName}\n${strings.power}: ${selection.secondFileName}"
            } else {
                binding.tvDeviceSelectedFirmware.text = selection.fileName
            }
        }
    }

    private fun refreshFirmwareVersion() {
        // Don't interfere with OTA operations
        if (viewState == ViewState.REBOOTING ||
            viewState == ViewState.INITIALIZING_UPLOAD ||
            viewState == ViewState.UPLOADING ||
            viewState == ViewState.REBOOTING_NEW_FIRMWARE) {
            Log.d("FirmwareRefresh", "Skipping firmware version refresh during OTA operation: $viewState")
            return
        }

        getFirmwareVersionCharacteristic()?.let { characteristic ->
            Log.d("FirmwareRefresh", "Reading firmware version characteristic")
            bluetoothGatt?.readCharacteristic(characteristic)
        } ?: Log.w("FirmwareRefresh", "Firmware version characteristic not available")
    }

    private fun startFirmwareVersionRefresh() {
        stopFirmwareVersionRefresh() // Stop any existing refresh

        firmwareRefreshRunnable = Runnable {
            refreshFirmwareVersion()
            // Schedule next refresh
            handler.postDelayed(firmwareRefreshRunnable!!, firmwareRefreshInterval)
        }

        // Start the periodic refresh
        handler.postDelayed(firmwareRefreshRunnable!!, firmwareRefreshInterval)
    }

    private fun stopFirmwareVersionRefresh() {
        firmwareRefreshRunnable?.let { runnable ->
            handler.removeCallbacks(runnable)
            firmwareRefreshRunnable = null
        }
    }

    private fun prepareOtaFile(uri: Uri?, type: OtaFileType, filename: String) {
        try {
            val inStream = contentResolver.openInputStream(uri!!)
            if (inStream == null) {
                showMessage(resources.getString(R.string.problem_while_preparing_the_file))
                return
            }
            val file = File(cacheDir, filename)
            val output: OutputStream = FileOutputStream(file)
            val buffer = ByteArray(4 * 1024)
            var read: Int
            while ((inStream.read(buffer).also { read = it }) != -1) {
                output.write(buffer, 0, read)
            }
            if ((type == OtaFileType.APPLICATION)) appPath = file.absolutePath
            else stackPath = file.absolutePath

            otaConfigDialog?.changeFilename(type, filename)

            output.flush()
        } catch (e: IOException) {
            e.printStackTrace()
            showMessage(resources.getString(R.string.incorrect_file))
        }
    }

    enum class ViewState {
        IDLE,
        REFRESHING_SERVICES,
        REBOOTING, //rebooting to bootloader or rebooting for second stage upload in full OTA scenario
        INITIALIZING_UPLOAD, //setting MTU and connection priority
        UPLOADING,
        REBOOTING_NEW_FIRMWARE
    }

    enum class MtuReadType {
        VIEW_INITIALIZATION,
        UPLOAD_INITIALIZATION,
        USER_REQUESTED
    }

    companion object {
        private const val GATT_FETCH_ON_SERVICE_DISCOVERED_DELAY = 875L
        private const val WRITE_EXTERNAL_STORAGE_REQUEST_PERMISSION = 300
        private const val FILE_CHOOSER_REQUEST_CODE = 9999

        private const val DEFAULT_MTU_VALUE = 200
        private const val INITIALIZATION_MTU_VALUE = 247

        private const val OTA_CONTROL_START_COMMAND: Byte = 0x00
        private const val OTA_CONTROL_END_COMMAND: Byte = 0x03
        private const val RECONNECTION_DELAY = 4000L // device needs to reboot
        private const val RECONNECT_TIMEOUT_MS = 100_000L // 100 seconds to reconnect after OTA
        private const val MAX_OTA_RETRIES = 3
        private const val POWER_TRANSFER_TIMEOUT_MS = 120_000L // 2 minutes for power serial transfer
        private const val MAX_CHAR_READ_RETRIES = 10
        private const val CHAR_READ_RETRY_DELAY_MS = 2000L
        private const val OPERATOR_OVERRIDE_CODE = "1900"
        private const val OTA_CONTROL_START_DELAY = 200L // needed to avoid error status 135
        private const val OTA_CONTROL_END_DELAY = 500L
        private const val CACHE_REFRESH_DELAY =
            500L // no callback available, so give some time to refresh
        private const val DIALOG_DELAY = 500L // give time for progress dialog to bind layout

        private const val CONNECTION_REQUEST_DIALOG_FRAGMENT = "connection_request_dialog_fragment"
        private const val MTU_REQUEST_DIALOG_FRAGMENT = "mtu_request_dialog_fragment"
        private const val OTA_CONFIG_DIALOG_FRAGMENT = "ota_config_dialog_fragment"
        private const val OTA_PROGRESS_DIALOG_FRAGMENT = "ota_progress_dialog_fragment"
        private const val OTA_LOADING_DIALOG_FRAGMENT = "ota_loading_dialog_fragment"

        const val CONNECTED_DEVICE = "connected_device"
        const val CONNECTION_STATE = "connection_state"
        const val REFRESH_INFO_RESULT_CODE = 279

        // Global OTA file path that persists for the app session
        var globalOtaFilePath: String = ""
        var globalOtaFileName: String = ""

        // Validation config from SFTP server config.ini
        var selectedValidation: com.siliconlabs.bledemo.features.firmware_browser.domain.FirmwareValidation? = null

        fun startActivity(context: Context, device: BluetoothDevice) {
            Intent(context, DeviceServicesActivity::class.java).apply {
                putExtra(CONNECTED_DEVICE, device)
                putExtra(ORIGIN,"ConnectionsFragment")
            }.also {
                startActivity(context, it, null)
            }
        }
    }
}
