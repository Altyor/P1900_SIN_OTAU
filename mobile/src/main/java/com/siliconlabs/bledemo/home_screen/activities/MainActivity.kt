package com.siliconlabs.bledemo.home_screen.activities

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.view.*
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import androidx.core.view.marginTop
import androidx.fragment.app.FragmentContainerView
import androidx.lifecycle.ViewModelProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.NavigationUI
import com.siliconlabs.bledemo.base.activities.BaseActivity
import com.siliconlabs.bledemo.bluetooth.services.BluetoothService
import com.siliconlabs.bledemo.R
import com.siliconlabs.bledemo.databinding.ActivityMainBinding
import com.siliconlabs.bledemo.home_screen.dialogs.PermissionsDialog
import com.siliconlabs.bledemo.home_screen.viewmodels.MainActivityViewModel
import com.siliconlabs.bledemo.home_screen.views.HidableBottomNavigationView
import com.siliconlabs.bledemo.utils.AppUtil
import com.siliconlabs.bledemo.utils.CustomToastManager
import com.siliconlabs.bledemo.features.scan.browser.activities.DeviceServicesActivity
import dagger.hilt.android.AndroidEntryPoint
import java.io.*


@AndroidEntryPoint
open class MainActivity : BaseActivity(),
        BluetoothService.ServicesStateListener
{
    private lateinit var _binding:ActivityMainBinding
    private lateinit var viewModel: MainActivityViewModel
    private lateinit var binding: BluetoothService.Binding
    var bluetoothService: BluetoothService? = null
        private set

    // Store OTA file URI in memory for current session only
    private var otaFileUri: Uri? = null

    private val neededPermissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
        )

    @RequiresApi(Build.VERSION_CODES.S)
    private val android12Permissions = listOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_ADVERTISE,
        Manifest.permission.BLUETOOTH_CONNECT
    )

    private val toastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_SHOW_CUSTOM_TOAST) {
                val message = intent.getStringExtra(EXTRA_TOAST_MESSAGE)
                message?.let {
                    CustomToastManager.show(this@MainActivity, it)
                }
            }
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.MainAppTheme)

        super.onCreate(savedInstanceState)
        _binding = ActivityMainBinding.inflate(LayoutInflater.from(this))
       // setContentView(R.layout.activity_main)
        setContentView(_binding.root)
        AppUtil.setEdgeToEdge(window,this)
        supportActionBar?.show()

        viewModel = ViewModelProvider(this).get(MainActivityViewModel::class.java)

        handlePermissions()
        setupMainNavigationListener()



        // Register the receiver
        val filter = IntentFilter(ACTION_SHOW_CUSTOM_TOAST)
        LocalBroadcastManager.getInstance(this).registerReceiver(toastReceiver, filter)
    }

    override fun onResume() {
        super.onResume()
        if (viewModel.getIsSetupFinished()) {
            viewModel.setIsLocationPermissionGranted(isPermissionGranted(neededPermissions[0]))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                viewModel.setAreBluetoothPermissionsGranted(areBluetoothPermissionsGranted())
            }
        }
    }

    private fun setupMainNavigationListener() {
        val navFragment = supportFragmentManager.findFragmentById(R.id.main_fragment) as NavHostFragment
        val navController = navFragment.navController

        NavigationUI.setupWithNavController(_binding.mainNavigation, navController)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                onBackPressed()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    fun toggleMainNavigation(isOn: Boolean) {
        if(isOn) {
            _binding.mainNavigation.show(instant = true)
        } else {
            _binding.mainNavigation.hide(instant = true)
        }
    }

    fun toggleHomeIcon(isOn: Boolean) {
        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(isOn)
            if (isOn) setHomeAsUpIndicator(R.drawable.redesign_ic_close)
        }
    }

    private fun bindBluetoothService() {
        binding = object : BluetoothService.Binding(this) {
            override fun onBound(service: BluetoothService?) {
                this@MainActivity.bluetoothService = service
                bluetoothService?.servicesStateListener = this@MainActivity
                setServicesInitialState()
            }
        }
        binding.bind()
    }

    private fun setServicesInitialState() {
        viewModel.setIsLocationPermissionGranted(isPermissionGranted(neededPermissions[0]))
        viewModel.setAreBluetoothPermissionsGranted(
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) areBluetoothPermissionsGranted()
            else true /* No runtime permissions needed for bluetooth operation in Android 11- */
        )
        bluetoothService?.let {
            viewModel.setIsBluetoothOn(it.isBluetoothOn())
            viewModel.setIsLocationOn(it.isLocationOn())
            viewModel.setIsNotificationOn(it.areNotificationOn())
        }
        observeChanges()
        viewModel.setIsSetupFinished(isSetupFinished = true)

        // Always request OTA file on app startup
        openOtaFilePicker()
    }

    private fun observeChanges() {
        viewModel.areBluetoothPermissionGranted.observe(this) {
            bluetoothService?.setAreBluetoothPermissionsGranted(
                viewModel.getAreBluetoothPermissionsGranted())
        }
    }

    fun getMainNavigation(): HidableBottomNavigationView? {
        return _binding.mainNavigation
    }

    fun getOtaFileUri(): Uri? {
        return otaFileUri
    }

    override fun onBluetoothStateChanged(isOn: Boolean) {
        viewModel.setIsBluetoothOn(isOn)
    }

    override fun onLocationStateChanged(isOn: Boolean) {
        viewModel.setIsLocationOn(isOn)
    }

    override fun onNotificationStateChanged(isOn: Boolean) {
        viewModel.setIsNotificationOn(isOn)
    }

    private fun handlePermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            neededPermissions.addAll(android12Permissions)
        }

        if (neededPermissions.any { !isPermissionGranted(it) }) askForPermissions()
        else bindBluetoothService()
    }

    private fun askForPermissions() {
        val rationalesToShow = neededPermissions.filter { shouldShowRequestPermissionRationale(it) }
        val permissionsToRequest = neededPermissions.toTypedArray()

        if (rationalesToShow.isNotEmpty()) {
            PermissionsDialog(rationalesToShow, object : PermissionsDialog.Callback {
                override fun onDismiss() {
                    requestPermissions(permissionsToRequest, PERMISSIONS_REQUEST_CODE)
                }
            }).show(supportFragmentManager, "permissions_dialog")
        } else {
            requestPermissions(permissionsToRequest, PERMISSIONS_REQUEST_CODE)
        }
    }

    private fun isPermissionGranted(permission: String) : Boolean {
        return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun areBluetoothPermissionsGranted() : Boolean {
        return android12Permissions.all { isPermissionGranted(it) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            PERMISSIONS_REQUEST_CODE -> bindBluetoothService()
        }
    }

    private fun openOtaFilePicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/octet-stream", "*/*"))

            // Set initial directory to "Production" folder in internal storage
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val productionFolderUri = DocumentsContract.buildDocumentUri(
                    "com.android.externalstorage.documents",
                    "primary:Production"
                )
                putExtra(DocumentsContract.EXTRA_INITIAL_URI, productionFolderUri)
            }
        }
        startActivityForResult(intent, OTA_FILE_PICKER_REQUEST_CODE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == OTA_FILE_PICKER_REQUEST_CODE) {
            when (resultCode) {
                Activity.RESULT_OK -> {
                    data?.data?.let { uri ->
                        // Check if it's a .gbl or .zigbee file
                        val fileName = getFileNameFromUri(uri)
                        android.util.Log.d("MainActivity", "File selected: $fileName, URI: $uri")

                        val isValidFile = fileName?.let { name ->
                            name.endsWith(".gbl", ignoreCase = true) ||
                            name.endsWith(".zigbee", ignoreCase = true)
                        } ?: false

                        if (isValidFile) {
                            // Store URI only in memory for current session
                            otaFileUri = uri

                            // Grant persistable URI permission
                            try {
                                contentResolver.takePersistableUriPermission(
                                    uri,
                                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                                )
                            } catch (e: Exception) {
                                android.util.Log.e("MainActivity", "Error granting URI permission", e)
                            }

                            // Prepare the OTA file and set global variables for DeviceServicesActivity
                            prepareOtaFileForGlobalUse(uri, fileName!!)

                            CustomToastManager.show(this, "Archivo OTA seleccionado: $fileName")
                        } else {
                            android.util.Log.w("MainActivity", "File is not .gbl or .zigbee: $fileName")
                            CustomToastManager.show(this, "Por favor seleccione un archivo .gbl o .zigbee. Archivo seleccionado: $fileName")
                            // Show file picker again
                            openOtaFilePicker()
                        }
                    } ?: run {
                        android.util.Log.e("MainActivity", "URI is null")
                        CustomToastManager.show(this, "Error: No se pudo obtener el archivo")
                        openOtaFilePicker()
                    }
                }
                Activity.RESULT_CANCELED -> {
                    android.util.Log.d("MainActivity", "File selection canceled")
                    CustomToastManager.show(this, "Selección cancelada. Debe seleccionar un archivo .gbl para continuar")
                    // Don't reopen picker, let user continue without file
                }
                else -> {
                    android.util.Log.w("MainActivity", "Unexpected result code: $resultCode")
                }
            }
        }
    }

    private fun prepareOtaFileForGlobalUse(uri: Uri, fileName: String) {
        try {
            val inStream = contentResolver.openInputStream(uri)
            if (inStream == null) {
                CustomToastManager.show(this, "Error al abrir el archivo")
                return
            }

            val file = File(cacheDir, fileName)
            val output: OutputStream = FileOutputStream(file)
            val buffer = ByteArray(4 * 1024)
            var read: Int

            while ((inStream.read(buffer).also { read = it }) != -1) {
                output.write(buffer, 0, read)
            }

            output.flush()
            inStream.close()
            output.close()

            // Set global variables in DeviceServicesActivity companion object
            DeviceServicesActivity.globalOtaFilePath = file.absolutePath
            DeviceServicesActivity.globalOtaFileName = fileName

        } catch (e: IOException) {
            e.printStackTrace()
            CustomToastManager.show(this, "Error al preparar archivo: ${e.message}")
        }
    }

    private fun getFileNameFromUri(uri: Uri): String? {
        var fileName: String? = null
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            fileName = cursor.getString(nameIndex)
        }
        return fileName
    }

    override fun onDestroy() {
        super.onDestroy()
        // Unregister the receiver
        LocalBroadcastManager.getInstance(this).unregisterReceiver(toastReceiver)
    }

    companion object {
        private const val PERMISSIONS_REQUEST_CODE = 400
        private const val OTA_FILE_PICKER_REQUEST_CODE = 401
        // private const val IMPORT_EXPORT_CODE_VERSION = 20
        const val ACTION_SHOW_CUSTOM_TOAST = "com.example.ACTION_SHOW_CUSTOM_TOAST"
        const val EXTRA_TOAST_MESSAGE = "com.example.EXTRA_TOAST_MESSAGE"
    }
//TODO: handle migration. See BTAPP-1285 for clarification.
/*
    private fun migrateGattDatabaseIfNeeded() {
        if (BuildConfig.VERSION_CODE <= IMPORT_EXPORT_CODE_VERSION - 1) {
            Migrator(this).migrate()
        }
    }
*/

}