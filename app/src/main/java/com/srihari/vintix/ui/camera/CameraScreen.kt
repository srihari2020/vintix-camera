package com.srihari.vintix.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.srihari.vintix.camera.CameraManager
import com.srihari.vintix.camera.PhotoCaptureManager
import com.srihari.vintix.rendering.CameraProfile
import com.srihari.vintix.rendering.CameraProfiles
import com.srihari.vintix.rendering.VintixGLSurfaceView

private const val TAG = "CameraScreen"

@Composable
fun CameraScreen(
    viewModel: CameraViewModel = viewModel()
) {
    val context = LocalContext.current
    val isCameraPermissionGranted by viewModel.isCameraPermissionGranted.collectAsState()
    val captureState by viewModel.captureState.collectAsState()

    var cameraManager by remember { mutableStateOf<CameraManager?>(null) }
    var glViewRef by remember { mutableStateOf<VintixGLSurfaceView?>(null) }
    val photoCaptureManager = remember { PhotoCaptureManager(context) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.onPermissionResult(isGranted)
    }

    // Available profiles
    val profiles = mapOf(
        "Early Digital" to CameraProfiles.EarlyDigitalConsumer,
        "Daylight" to CameraProfiles.DaylightNeutral,
        "CyberShot '03" to CameraProfiles.CyberShot2003,
        "Disposable" to CameraProfiles.DisposableFilm
    )
    var selectedProfileName by remember { mutableStateOf(profiles.keys.first()) }
    val currentProfile = profiles[selectedProfileName] ?: CameraProfile.Default

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.onPermissionResult(true)
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isCameraPermissionGranted) {
            // Camera preview via OpenGL pipeline — fills entire screen
            GLPreview(
                cameraProfile = currentProfile,
                onCameraReady = { manager ->
                    cameraManager = manager
                },
                onGlViewReady = { glViewRef = it }
            )

            // Capture flash overlay
            CaptureFlashOverlay(
                trigger = captureState is CaptureState.Success,
                onAnimationComplete = {
                    viewModel.resetCaptureState()
                }
            )

            // Profile Selector — above shutter button
            ProfileSelector(
                profiles = profiles.keys.toList(),
                selectedProfile = selectedProfileName,
                onProfileSelected = { selectedProfileName = it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 120.dp)
                    .fillMaxWidth()
            )

            // Shutter button — bottom center
            ShutterButton(
                onClick = {
                    val imageCapture = cameraManager?.imageCapture ?: return@ShutterButton
                    viewModel.onCaptureStarted()
                    photoCaptureManager.capturePhoto(
                        imageCapture = imageCapture,
                        cameraProfile = currentProfile,
                        noisePhase = glViewRef?.vintixRenderer?.noisePhaseSnapshot ?: 0.5f,
                        onSuccess = { uri ->
                            Log.d(TAG, "Photo saved: $uri")
                            viewModel.onPhotoCaptured(uri)
                        },
                        onError = { exception ->
                            Log.e(TAG, "Capture failed", exception)
                            viewModel.onCaptureError(
                                exception.message ?: "Unknown capture error"
                            )
                        }
                    )
                },
                enabled = cameraManager?.imageCapture != null
                        && captureState !is CaptureState.Capturing,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp)
            )
        } else {
            Text(
                text = "Camera permission required",
                modifier = Modifier.align(Alignment.Center)
            )
        }
    }
}

