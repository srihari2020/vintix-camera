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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.launch

private const val TAG = "CameraScreen"

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
    val appSettings by settingsViewModel.settings.collectAsState()

    var cameraManager by remember { mutableStateOf<CameraManager?>(null) }
    var glViewRef by remember { mutableStateOf<VintixGLSurfaceView?>(null) }
    val photoCaptureManager = remember { PhotoCaptureManager(context) }
    val toneGenerator = remember {
        android.media.ToneGenerator(android.media.AudioManager.STREAM_SYSTEM, 90)
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

    Box(modifier = Modifier.fillMaxSize()) {
        if (isCameraPermissionGranted) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                val portraitRatio = 1f / currentProfile.aspectRatio.ratio
                Box(modifier = Modifier.aspectRatio(portraitRatio)) {
                    GLPreview(
                        cameraProfile = currentProfile,
                        onCameraReady = { manager ->
                            cameraManager = manager
                            viewModel.onCameraReady()
                        },
                        onGlViewReady = { glViewRef = it },
                        onCameraError = { throwable ->
                            viewModel.onCameraError(throwable.message ?: "Camera unavailable")
                        }
                    )

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

            if (BuildConfig.TELEMETRY_ENABLED) {
                TelemetryOverlay(modifier = Modifier.align(Alignment.TopStart).statusBarsPadding())
            }

            when (val availability = cameraAvailability) {
                CameraAvailability.Initializing -> CameraStatusOverlay(
                    text = "WAKING CAMERA",
                    modifier = Modifier.align(Alignment.Center)
                )
                is CameraAvailability.Error -> CameraStatusOverlay(
                    text = availability.message,
                    modifier = Modifier.align(Alignment.Center)
                )
                CameraAvailability.Ready -> Unit
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
                    .padding(bottom = 120.dp)
                    .fillMaxWidth()
            )

            val view = androidx.compose.ui.platform.LocalView.current
            val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()

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
                            toneGenerator.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 42)
                        } else {
                            shutterSound.play(android.media.MediaActionSound.SHUTTER_CLICK)
                        }
                    }

                    if (feedback.captureFreezeMs > 0) {
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
                    cameraAvailability is CameraAvailability.Ready,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp)
            )

            androidx.compose.material3.TextButton(
                onClick = onNavigateToSettings,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp, start = 16.dp)
            ) {
                Text(
                    text = "SETTINGS",
                    color = androidx.compose.ui.graphics.Color.White,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    fontSize = 14.sp
                )
            }

            androidx.compose.material3.TextButton(
                onClick = onNavigateToGallery,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(bottom = 32.dp, end = 16.dp)
            ) {
                Text(
                    text = "GALLERY",
                    color = androidx.compose.ui.graphics.Color.White,
                    fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                    fontSize = 14.sp
                )
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

@Composable
fun TelemetryOverlay(modifier: Modifier = Modifier) {
    val fps by PerformanceTelemetry.fps.collectAsState()
    val glRenderTimeMs by PerformanceTelemetry.glRenderTimeMs.collectAsState()
    val exportTimeMs by PerformanceTelemetry.exportTimeMs.collectAsState()
    val processingTimeMs by PerformanceTelemetry.processingTimeMs.collectAsState()
    val memoryUsageMb by PerformanceTelemetry.memoryUsageMb.collectAsState()

    Column(
        modifier = modifier
            .padding(8.dp)
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(8.dp)
    ) {
        val textStyle = androidx.compose.ui.text.TextStyle(
            color = Color.Green,
            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
            fontSize = 10.sp
        )
        Text("FPS: $fps", style = textStyle)
        Text("GL: ${String.format("%.1f", glRenderTimeMs)} ms", style = textStyle)
        Text("Export: $exportTimeMs ms", style = textStyle)
        Text("Proc: $processingTimeMs ms", style = textStyle)
        Text("Mem: $memoryUsageMb MB", style = textStyle)
    }
}
