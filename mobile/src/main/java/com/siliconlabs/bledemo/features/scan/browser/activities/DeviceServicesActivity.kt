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


    private var delayNoResponse = 1
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
        OnScrollChangeListener { v, scrollX, scrollY, oldScrollX, oldScrollY ->
            if (scrollY > oldScrollY) {
                binding.btnDisconnect.hide()
            } else {
                binding.btnDisconnect.show()
            }
        }

    private val remoteServicesFragment = RemoteServicesFragment(hideFabOnScrollChangeListener)
    private val localServicesFragment = LocalServicesFragment()
    private var activeFragment: Fragment = remoteServicesFragment

    private val bondStateChangeListener = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothDevice.ACTION_BOND_STATE_CHANGED) {
                val newState =
                    intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
                displayBondState(newState)
                if (newState == BluetoothDevice.BOND_BONDED) {
                    showMessage(getString(R.string.device_bonded_successfully))
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
                }

                MtuReadType.UPLOAD_INITIALIZATION -> {
                    MTU = if (status == BluetoothGatt.GATT_SUCCESS) mtu
                    else DEFAULT_MTU_VALUE
                    gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH) // optimize upload speed
                    writeOtaControl(OTA_CONTROL_START_COMMAND)
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

            if (bluetoothGatt?.device?.address != gatt.device.address) {
                Log.w("OTA_DEBUG", "Connection state change for different device, ignoring")
                return
            }

            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    Log.d("OTA_DEBUG", "Device connected")
                    if (viewState == ViewState.REBOOTING ||
                        viewState == ViewState.REBOOTING_NEW_FIRMWARE
                    ) {
                        Log.d("OTA_DEBUG", "Device reconnected during OTA process, discovering services")
                        handler.postDelayed({
                            bluetoothGatt = null
                            gatt.discoverServices()
                        }, 250)
                    }
                }

                BluetoothGatt.STATE_DISCONNECTED -> {
                    Log.d("OTA_DEBUG", "Device disconnected with status $status during state $viewState")
                    when (viewState) {
                        ViewState.IDLE -> when (status) {
                            0 -> {
                                Log.d("OTA_DEBUG", "Normal disconnection in IDLE state, finishing activity")
                                finish()
                            }
                            else -> {
                                Log.e("OTA_DEBUG", "Unexpected disconnection in IDLE state: status=$status")
                                showLongMessage(
                                    ErrorCodes.getDeviceDisconnectedMessage(
                                        getDeviceName(),
                                        status
                                    )
                                )
                                finish()
                            }
                        }

                        ViewState.REBOOTING -> when (status) {
                            19 -> {
                                Log.d("OTA_DEBUG", "Expected disconnection for OTA mode reboot (status 19)")
                                showInitializationInfo()
                            }

                            0 -> {
                                Log.d("OTA_DEBUG", "Normal disconnection during REBOOTING, device will reconnect")
                            }
                            else -> {
                                Log.e("OTA_DEBUG", "Unexpected disconnection during REBOOTING: status=$status")
                                showErrorDialog(status)
                            }
                        }

                        ViewState.UPLOADING -> {
                            Log.e("OTA_DEBUG", "Device disconnected during UPLOADING! status=$status")
                            showErrorDialog(status)
                        }

                        ViewState.REBOOTING_NEW_FIRMWARE -> {
                            Log.d("OTA_DEBUG", "Device disconnected during firmware reboot, this is expected")
                        }

                        else -> {
                            Log.w("OTA_DEBUG", "Device disconnected in unknown state: $viewState")
                            finish()
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

            when (characteristic.uuid) {
                UuidConsts.FIRMWARE_VERSION -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        val fullVersion = characteristic.getStringValue(0) ?: ""
                        // Parse version string format: "3.13.0-3.1.5" (Antenna-Power)
                        val versions = fullVersion.split("-")
                        firmwareVersionAntenna = versions.getOrNull(0)
                        firmwareVersionPower = versions.getOrNull(1)
                        // Keep old behavior for title (show antenna version)
                        firmwareVersion = firmwareVersionAntenna

                        runOnUiThread {
                            supportActionBar?.title = getDeviceName()
                            updateFirmwareVersionDisplay()
                        }

                        // After reading firmware version, read model number
                        getModelNumberCharacteristic()?.let { modelCharacteristic ->
                            Log.d("OTA_DEBUG", "Reading model number after firmware version")
                            gatt.readCharacteristic(modelCharacteristic)
                        }
                    }
                }
                UuidConsts.MODEL_NUMBER -> {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        modelNumber = characteristic.getStringValue(0) ?: ""
                        Log.d("OTA_DEBUG", "Model number read: $modelNumber")
                        runOnUiThread {
                            updateModelNumberDisplay()
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

                    // Refresh firmware version after new firmware is loaded
                    handler.postDelayed({
                        refreshFirmwareVersion()
                    }, 2000)
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
                showErrorDialog(status)
                if (viewState == ViewState.UPLOADING) {
                    Log.w("OTA_DEBUG", "Setting viewState from UPLOADING to IDLE due to write failure")
                    viewState = ViewState.IDLE
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
            bluetoothGatt?.readCharacteristic(characteristic)
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
                            mtuReadType = MtuReadType.VIEW_INITIALIZATION
                            gatt.requestMtu(INITIALIZATION_MTU_VALUE)

                            // Try to read firmware version
                            getFirmwareVersionCharacteristic()?.let { characteristic ->
                                Log.d("OTA_DEBUG", "Reading firmware version during normal init")
                                gatt.readCharacteristic(characteristic)
                            }
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
                        Log.d("OTA_DEBUG", "Device rebooted with new firmware, reading device name")
                        bluetoothGatt?.readCharacteristic(getDeviceNameCharacteristic())
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

        showCharacteristicLoadingAnimation(getString(R.string.debug_mode_device_loading_gatt_info))
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

            if(null != intent && intent.getStringExtra(ORIGIN).equals("BrowserFragment",true)){
                if(devicesAdapterRecyclerViewItemPos >= 0){
                    if(null != scanFragmentViewModel){
                        scanFragmentViewModel.setDeviceConnectionState(devicesAdapterRecyclerViewItemPos, connectionState = BluetoothDeviceInfo.ConnectionState.DISCONNECTED)
                    }
                    if(null != bluetoothDevice){
                        bluetoothService?.disconnectGatt(bluetoothDevice!!.address)
                    }
                }else{
                    println("Error: Invalid devicesAdapterRecyclerViewItemPos in setupUiListeners")
                    Log.e("Error: ", "Invalid devicesAdapterRecyclerViewItemPos in setupUiListeners")

                }
            }else{
                bluetoothService?.disconnectGatt(bluetoothDevice!!.address)
            }

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
                showMessage("No OTA file selected. Please restart the app and select an OTA file.")
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

        if (viewState == ViewState.IDLE) {
            // Set the app path to the global OTA file path
            appPath = globalOtaFilePath

            // Use reliable mode as default (like the original config dialog)
            reliable = true

            // Check if OTA data characteristic is present
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
            showMessage("Device is not ready for OTA. Please wait or disconnect and reconnect.")
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
            } else {
                // Start periodic firmware version refresh when connected
                startFirmwareVersionRefresh()
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
            showMessage(getString(R.string.ota_uploading_successful))

            remoteServicesFragment.clear()
            localServicesFragment.clear()
            showCharacteristicLoadingAnimation(getString(R.string.debug_mode_device_rebooting_firmware))

            viewState = ViewState.REBOOTING_NEW_FIRMWARE
            bluetoothGatt?.disconnect()
            reconnect(requestRssiUpdates = true)

            // Schedule firmware version refresh after OTA completion
            handler.postDelayed({
                refreshFirmwareVersion()
            }, 5000) // Give device time to reboot and stabilize
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
            // Antenna version display
            firmwareVersionAntenna?.let { versionAntenna ->
                binding.tvFirmwareVersionAntenna.apply {
                    text = versionAntenna
                    visibility = View.VISIBLE
                    // Check antenna version based on model number (including _PRO variants)
                    val isCorrectAntennaVersion = when (modelNumber) {
                        "SIN-4-2-20", "SIN-4-2-20_PRO" -> versionAntenna == "3.13.0"
                        "SIN-4-RS-20", "SIN-4-RS-20_PRO" -> versionAntenna == "3.12.0"
                        else -> false
                    }
                    val backgroundColor = if (isCorrectAntennaVersion) {
                        ContextCompat.getColor(this@DeviceServicesActivity, R.color.silabs_green)
                    } else {
                        ContextCompat.getColor(this@DeviceServicesActivity, R.color.silabs_red)
                    }
                    setBackgroundColor(backgroundColor)
                }
            }

            // Power version display (same for both models)
            firmwareVersionPower?.let { versionPower ->
                binding.tvFirmwareVersionPower.apply {
                    text = versionPower
                    visibility = View.VISIBLE
                    val backgroundColor = if (versionPower == "3.1.5") {
                        ContextCompat.getColor(this@DeviceServicesActivity, R.color.silabs_green)
                    } else {
                        ContextCompat.getColor(this@DeviceServicesActivity, R.color.silabs_red)
                    }
                    setBackgroundColor(backgroundColor)
                }
            }

            // Show container if at least one version is available
            if (firmwareVersionAntenna != null || firmwareVersionPower != null) {
                binding.firmwareVersionsContainer.visibility = View.VISIBLE
            }
        }
    }

    private fun updateModelNumberDisplay() {
        runOnUiThread {
            modelNumber?.let { model ->
                binding.tvModelNumber.apply {
                    text = model
                    visibility = View.VISIBLE
                    // Accept only SIN-4-2-20 and SIN-4-RS-20 as valid models
                    val isValidModel = (model == "SIN-4-2-20" || model == "SIN-4-RS-20")
                    val backgroundColor = if (isValidModel) {
                        ContextCompat.getColor(this@DeviceServicesActivity, R.color.silabs_green)
                    } else {
                        ContextCompat.getColor(this@DeviceServicesActivity, R.color.silabs_red)
                    }
                    setBackgroundColor(backgroundColor)
                }

                // Update firmware display in case it was already read
                // (to apply correct color based on model)
                if (firmwareVersionAntenna != null || firmwareVersionPower != null) {
                    updateFirmwareVersionDisplay()
                }
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
