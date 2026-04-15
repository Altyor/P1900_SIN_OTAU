package com.siliconlabs.bledemo.features.firmware_browser.presentation

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import com.siliconlabs.bledemo.features.firmware_browser.ui.FirmwareBrowserScreen
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class FirmwareBrowserActivity : ComponentActivity() {

    private val viewModel: FirmwareBrowserViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                FirmwareBrowserScreen(
                    viewModel = viewModel,
                    onFinished = {
                        setResult(Activity.RESULT_OK)
                        finish()
                    }
                )
            }
        }
    }
}
