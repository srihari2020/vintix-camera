package com.srihari.vintix

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import com.srihari.vintix.ui.camera.CameraScreen
import com.srihari.vintix.ui.gallery.GalleryScreen
import com.srihari.vintix.ui.settings.SettingsScreen
import com.srihari.vintix.ui.theme.VintixTheme

enum class AppScreen { CAMERA, GALLERY, SETTINGS }

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VintixTheme {
                var currentScreen by remember { mutableStateOf(AppScreen.CAMERA) }

                BackHandler(enabled = currentScreen != AppScreen.CAMERA) {
                    currentScreen = AppScreen.CAMERA
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    when (currentScreen) {
                        AppScreen.CAMERA -> {
                            CameraScreen(
                                onNavigateToGallery = {
                                    currentScreen = AppScreen.GALLERY
                                },
                                onNavigateToSettings = {
                                    currentScreen = AppScreen.SETTINGS
                                }
                            )
                        }
                        AppScreen.GALLERY -> {
                            GalleryScreen(
                                onNavigateBack = {
                                    currentScreen = AppScreen.CAMERA
                                }
                            )
                        }
                        AppScreen.SETTINGS -> {
                            SettingsScreen(
                                onNavigateBack = {
                                    currentScreen = AppScreen.CAMERA
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
