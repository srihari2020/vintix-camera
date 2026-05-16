package com.srihari.vintix.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.srihari.vintix.camera.CameraManager
import com.srihari.vintix.camera.PhotoCaptureManager
import com.srihari.vintix.rendering.CameraProfile
import com.srihari.vintix.rendering.CameraProfiles
import com.srihari.vintix.rendering.VintixGLSurfaceView
import com.srihari.vintix.settings.SettingsViewModel
import com.srihari.vintix.settings.timestampStyle
import com.srihari.vintix.settings.withVintixSettings
import com.srihari.vintix.telemetry.PerformanceTelemetry
import com.srihari.vintix.BuildConfig
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TAG = "CameraScreen"

/** Timeout for camera initialization before showing error/retry state. */
private const val CAMERA_INIT_TIMEOUT_MS = 20_000L

@Composable
fun CameraScreen(
    viewModel: CameraViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel(),
    onNavigateToGallery: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val isCameraPermissionGranted by viewModel.isCameraPermissionGranted.collectAsState()
    val captureState by viewModel.captureState.collectAsState()
    val cameraAvailability by viewModel.cameraAvailability.collectAsState()
    val safeModeActive by viewModel.safeModeActive.collectAsState()
    val isFrontCamera by viewModel.isFrontCamera.collectAsState()
    val retryKey by viewModel.retryKey.collectAsState()
    val appSettings by settingsViewModel.settings.collectAsState()

    var cameraManager by remember { mutableStateOf<CameraManager?>(null) }
    var glViewRef by remember { mutableStateOf<VintixGLSurfaceView?>(null) }
    val photoCaptureManager = remember { PhotoCaptureManager(context) }
    
    // 90s/2000s camera sounds
    val toneGenerator = remember {
        android.media.ToneGenerator(android.media.AudioManager.STREAM_SYSTEM, 70)
    }
    val shutterSound = remember {
        android.media.MediaActionSound().apply {
            load(android.media.MediaActionSound.SHUTTER_CLICK)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            toneGenerator.release()
            shutterSound.release()
            photoCaptureManager.shutdown()
        }
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        viewModel.onPermissionResult(isGranted)
        if (
            isGranted &&
            android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
    }

    val profiles = mapOf(
        "Early Digital" to CameraProfiles.EarlyDigitalConsumer,
        "Daylight" to CameraProfiles.DaylightNeutral,
        "CyberShot '03" to CameraProfiles.CyberShot2003,
        "Disposable" to CameraProfiles.DisposableFilm
    )
    var selectedProfileName by remember { mutableStateOf(profiles.keys.first()) }
    val baseProfile = profiles[selectedProfileName] ?: CameraProfile.Default
    val currentProfile = baseProfile.withVintixSettings(appSettings)

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            viewModel.onPermissionResult(true)
            if (
                android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            }
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Initialization timeout: if camera stays in Initializing too long, show error with retry.
    LaunchedEffect(cameraAvailability, retryKey) {
        if (cameraAvailability is CameraAvailability.Initializing) {
            delay(CAMERA_INIT_TIMEOUT_MS)
            // Recheck after delay — may have resolved
            if (viewModel.cameraAvailability.value is CameraAvailability.Initializing) {
                Log.e(TAG, "Camera initialization timed out after ${CAMERA_INIT_TIMEOUT_MS}ms")
                viewModel.onCameraError("Camera timed out — tap RETRY")
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (isCameraPermissionGranted) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                val portraitRatio = 1f / currentProfile.aspectRatio.ratio
                Box(modifier = Modifier.aspectRatio(portraitRatio)) {
                    if (!safeModeActive) {
                        // --- GL PIPELINE PATH ---
                        GLPreview(
                            cameraProfile = currentProfile,
                            isFrontCamera = isFrontCamera,
                            onCameraReady = { manager ->
                                Log.d(TAG, "GLPreview camera ready")
                                cameraManager = manager
                                viewModel.onCameraReady()
                            },
                            onGlViewReady = { glViewRef = it },
                            onCameraError = { throwable ->
                                Log.e(TAG, "GLPreview error — falling back to standard preview", throwable)
                                glViewRef = null
                                cameraManager = null
                                viewModel.activateSafeMode("GL Pipeline Failed: ${throwable.message}")
                            }
                        )
                    } else {
                        // --- FALLBACK: Standard CameraPreview ---
                        androidx.compose.runtime.key(retryKey) {
                            CameraPreview(
                                onCameraReady = { manager ->
                                    cameraManager = manager
                                    viewModel.onCameraReady()
                                },
                                onCameraError = { throwable ->
                                    Log.e(TAG, "Fallback CameraPreview error", throwable)
                                    viewModel.onCameraError("Camera Failed: ${throwable.message}")
                                }
                            )
                        }
                    }

                    val capturingState = captureState as? CaptureState.Capturing
                    CaptureFlashOverlay(
                        trigger = capturingState != null,
                        durationMs = capturingState?.feedback?.flashFadeDurationMs ?: 0,
                        onAnimationComplete = {
                            // State resets when CaptureState.Success is emitted.
                        }
                    )
                }
            }

            // Top Status Overlay (Sony Cyber-shot style)
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                StatusIndicator(text = if (isFrontCamera) "CAM: SELFIE" else "CAM: MAIN")
                StatusIndicator(text = "ISO: AUTO")
                StatusIndicator(text = "RES: ${if(appSettings.exportResolution == com.srihari.vintix.settings.ExportResolutionPreset.NATIVE) "FULL" else appSettings.exportResolution.label}")
                StatusIndicator(text = "BAT: 98%")
            }

            // Initialization status (only shown during wakeup)
            if (cameraAvailability is CameraAvailability.Initializing) {
                CameraStatusOverlay(
                    text = "WAKING CAMERA",
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // Error state (only for real camera failures)
            if (cameraAvailability is CameraAvailability.Error) {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val error = cameraAvailability as CameraAvailability.Error
                    CameraStatusOverlay(text = error.message)
                    androidx.compose.material3.TextButton(
                        onClick = { viewModel.retryCamera() }
                    ) {
                        Text(
                            text = "RETRY",
                            color = Color.White,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }
            }

            val captureError = captureState as? CaptureState.Error
            if (captureError != null) {
                CameraStatusOverlay(
                    text = captureError.message,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 24.dp)
                )
            }

            ProfileSelector(
                profiles = profiles.keys.toList(),
                selectedProfile = selectedProfileName,
                onProfileSelected = { selectedProfileName = it },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 140.dp)
                    .fillMaxWidth()
            )

            val view = androidx.compose.ui.platform.LocalView.current
            val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

            // Bottom controls
            Row(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 24.dp, end = 24.dp, bottom = 32.dp)
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Settings Button (Minimalist)
                CameraIconButton(onClick = onNavigateToSettings) {
                    Text(
                        text = "SET",
                        color = Color.White,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }

                // Shutter
                ShutterButton(
                    onClick = {
                        val imageCapture = cameraManager?.imageCapture ?: return@ShutterButton
                        if (
                            android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P &&
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            viewModel.onCaptureError("Storage permission required to save photos")
                            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                            coroutineScope.launch {
                                kotlinx.coroutines.delay(2200)
                                viewModel.resetCaptureState()
                            }
                            return@ShutterButton
                        }
                        val feedback = currentProfile.feedbackBehavior

                        if (feedback.hapticFeedback) {
                            view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        }
                        if (feedback.soundEnabled) {
                            if (feedback.useDigitalBeep) {
                                toneGenerator.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 50)
                            } else {
                                shutterSound.play(android.media.MediaActionSound.SHUTTER_CLICK)
                            }
                        }

                        // Freeze preview only when GL pipeline is active
                        if (!safeModeActive && feedback.captureFreezeMs > 0) {
                            glViewRef?.vintixRenderer?.freezePreview = true
                            glViewRef?.postDelayed({
                                glViewRef?.vintixRenderer?.freezePreview = false
                            }, feedback.captureFreezeMs)
                        }

                        viewModel.onCaptureStarted(feedback)

                        photoCaptureManager.capturePhoto(
                            imageCapture = imageCapture,
                            cameraProfile = currentProfile,
                            profileName = selectedProfileName,
                            noisePhase = glViewRef?.vintixRenderer?.noisePhaseSnapshot ?: 0.5f,
                            timestampStyle = appSettings.timestampStyle(),
                            onSuccess = { uri ->
                                Log.d(TAG, "Photo saved: $uri")
                                viewModel.onPhotoCaptured(uri)
                                coroutineScope.launch {
                                    kotlinx.coroutines.delay(100)
                                    viewModel.resetCaptureState()
                                }
                            },
                            onError = { exception ->
                                Log.e(TAG, "Capture failed", exception)
                                viewModel.onCaptureError(
                                    exception.message ?: "Unknown capture error"
                                )
                                coroutineScope.launch {
                                    kotlinx.coroutines.delay(2200)
                                    viewModel.resetCaptureState()
                                }
                            }
                        )
                    },
                    enabled = cameraManager?.imageCapture != null &&
                        captureState !is CaptureState.Capturing &&
                        cameraAvailability is CameraAvailability.Ready
                )

                // Gallery/Switch Column
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CameraIconButton(onClick = onNavigateToGallery) {
                        Text(
                            text = "GAL",
                            color = Color.White,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    CameraIconButton(onClick = { viewModel.toggleCamera() }) {
                        Text(
                            text = "SWP",
                            color = Color(0xFFFFC107), // Amber for camera switch
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.align(Alignment.Center),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Camera permission required",
                    color = Color.White,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontSize = 14.sp
                )
                androidx.compose.material3.TextButton(
                    onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }
                ) {
                    Text(
                        text = "ALLOW CAMERA",
                        color = Color.White,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusIndicator(text: String) {
    Text(
        text = text.uppercase(),
        color = Color(0xFF00FF00).copy(alpha = 0.8f), // Retro green matrix style
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        fontSize = 10.sp,
        letterSpacing = 0.5.sp,
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.4f))
            .padding(horizontal = 4.dp, vertical = 2.dp)
    )
}

@Composable
private fun CameraIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun CameraStatusOverlay(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        color = Color.White,
        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
        fontSize = 12.sp,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.62f))
            .padding(horizontal = 14.dp, vertical = 8.dp)
    )
}
