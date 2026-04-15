package com.siliconlabs.bledemo.features.firmware_browser.presentation

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import com.siliconlabs.bledemo.features.firmware_browser.ui.FirmwareBrowserScreen
import dagger.hilt.android.AndroidEntryPoint

private val SiLabsColorScheme = lightColorScheme(
    primary = Color(0xFF0F62F3),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD6E3FF),
    onPrimaryContainer = Color(0xFF001B3D),
    secondary = Color(0xFFD91E2A),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFDAD6),
    onSecondaryContainer = Color(0xFF410002),
    background = Color(0xFFF1EFEF),
    onBackground = Color(0xFF1A1C1E),
    surface = Color.White,
    onSurface = Color(0xFF1A1C1E),
    surfaceVariant = Color(0xFFE0E2EC),
    onSurfaceVariant = Color(0xFF44474E),
    error = Color(0xFFD91E2A),
    onError = Color.White,
)

@AndroidEntryPoint
class FirmwareBrowserActivity : ComponentActivity() {

    private val viewModel: FirmwareBrowserViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = SiLabsColorScheme) {
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
