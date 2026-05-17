package com.srihari.vintix.ui.camera

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.srihari.vintix.camera.CameraManager
import com.srihari.vintix.camera.PhotoCaptureManager
import com.srihari.vintix.camera.DeviceOrientationManager
import com.srihari.vintix.rendering.CameraProfile
import com.srihari.vintix.rendering.CameraProfiles
import com.srihari.vintix.rendering.VintixGLSurfaceView
import com.srihari.vintix.settings.SettingsViewModel
import com.srihari.vintix.settings.timestampStyle
import com.srihari.vintix.settings.withVintixSettings
import androidx.camera.core.ImageCapture
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
    val scope = rememberCoroutineScope()
    val isCameraPermissionGranted by viewModel.isCameraPermissionGranted.collectAsState()
    val captureState by viewModel.captureState.collectAsState()
    val cameraAvailability by viewModel.cameraAvailability.collectAsState()
    val isFrontCamera by viewModel.isFrontCamera.collectAsState()
    val flashMode by viewModel.flashMode.collectAsState()
    val zoomRatio by viewModel.zoomRatio.collectAsState()
    val exposureIndex by viewModel.exposureIndex.collectAsState()
    val retryKey by viewModel.retryKey.collectAsState()
    val appSettings by settingsViewModel.settings.collectAsState()

    var cameraManager by remember { mutableStateOf<CameraManager?>(null) }
    var glViewRef by remember { mutableStateOf<VintixGLSurfaceView?>(null) }
    
    // Ensure freezePreview is reset even on errors
    LaunchedEffect(captureState) {
        if (captureState is CaptureState.Success || captureState is CaptureState.Error) {
            glViewRef?.vintixRenderer?.freezePreview = false
        }
    }
    val photoCaptureManager = remember { PhotoCaptureManager(context) }
    val orientationManager = remember { DeviceOrientationManager(context) }
    
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    var focusPoint by remember { mutableStateOf<androidx.compose.ui.geometry.Offset?>(null) }
    val focusAnimationAlpha = remember { Animatable(0f) }

    DisposableEffect(Unit) {
        orientationManager.start()
        onDispose { orientationManager.stop() }
    }

    // Update camera controls when ViewModel state changes
    LaunchedEffect(flashMode) {
        cameraManager?.setFlashMode(flashMode)
    }
    LaunchedEffect(zoomRatio) {
        cameraManager?.zoom(zoomRatio)
    }
    LaunchedEffect(exposureIndex) {
        cameraManager?.setExposure(exposureIndex)
    }

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
        "CyberShot" to CameraProfiles.CyberShot2003,
        "Coolpix" to CameraProfiles.Coolpix,
        "Handycam" to CameraProfiles.Handycam,
        "VGA 1999" to CameraProfiles.VGA1999,
        "Disposable" to CameraProfiles.DisposableFilm,
        "Early Dig" to CameraProfiles.EarlyDigitalConsumer,
        "Daylight" to CameraProfiles.DaylightNeutral
    )
    var selectedProfileName by remember { mutableStateOf(profiles.keys.first()) }
    val baseProfile = profiles[selectedProfileName] ?: CameraProfile.Default
    
    val currentAspectRatio by viewModel.aspectRatio.collectAsState()
    val currentProfile = baseProfile.withVintixSettings(appSettings).copy(
        aspectRatio = currentAspectRatio
    )

    val timerSeconds by viewModel.timerSeconds.collectAsState()
    var countdownValue by remember { mutableStateOf(0) }

    val captureAction = {
        val imageCapture = cameraManager?.imageCapture
        if (imageCapture != null && captureState !is CaptureState.Capturing) {
            val feedback = currentProfile.feedbackBehavior
            
            // 1. Immediate UI Feedback
            if (feedback.soundEnabled) {
                if (feedback.useDigitalBeep) {
                    toneGenerator.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, 50)
                } else {
                    shutterSound.play(android.media.MediaActionSound.SHUTTER_CLICK)
                }
            }

            glViewRef?.vintixRenderer?.freezePreview = true
            scope.launch {
                delay(feedback.captureFreezeMs)
                glViewRef?.vintixRenderer?.freezePreview = false
            }

            viewModel.onCaptureStarted(feedback)

            // 2. Capture Execution
            photoCaptureManager.capturePhoto(
                imageCapture = imageCapture,
                cameraProfile = currentProfile,
                profileName = selectedProfileName,
                noisePhase = glViewRef?.vintixRenderer?.noisePhaseSnapshot ?: 0.5f,
                isFrontCamera = isFrontCamera,
                timestampStyle = appSettings.timestampStyle(),
                onSuccess = { uri ->
                    Log.d(TAG, "Capture success: $uri")
                    viewModel.onPhotoCaptured(uri)
                    // Auto-reset state after a short delay to re-enable shutter
                    scope.launch {
                        delay(300) 
                        viewModel.resetCaptureState()
                    }
                },
                onError = { exception ->
                    Log.e(TAG, "Capture failed", exception)
                    viewModel.onCaptureError(exception.message ?: "Capture Failed")
                    // Always reset state even on error
                    scope.launch {
                        delay(1000)
                        viewModel.resetCaptureState()
                    }
                }
            )
        }
    }
    
    LaunchedEffect(countdownValue) {
        if (countdownValue > 0) {
            delay(1000)
            countdownValue -= 1
            if (countdownValue == 0) {
                captureAction()
            }
        }
    }

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

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF050505))) {
        if (isCameraPermissionGranted) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                val portraitRatio = 1f / currentProfile.aspectRatio.ratio
                
                // Pinch to Zoom
                val transformableState = rememberTransformableState { zoomDelta, _, _ ->
                    val newZoom = (zoomRatio * zoomDelta).coerceIn(1f, 8f)
                    viewModel.setZoomRatio(newZoom)
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { previewSize = it.size }
                        .pointerInput(Unit) {
                            detectTapGestures { offset ->
                                focusPoint = offset
                                cameraManager?.focus(
                                    offset.x, offset.y, 
                                    previewSize.width, previewSize.height
                                )
                                // Focus ring animation
                                scope.launch {
                                    focusAnimationAlpha.snapTo(1f)
                                    focusAnimationAlpha.animateTo(0f, animationSpec = tween(1500))
                                    focusPoint = null
                                }
                            }
                        }
                        .transformable(state = transformableState),
                    contentAlignment = Alignment.Center
                ) {
                    // Central Preview with Aspect Ratio
                    Box(
                        modifier = Modifier
                            .aspectRatio(portraitRatio)
                            .clip(RectangleShape)
                    ) {
                        // --- GL PIPELINE PATH ---
                        GLPreview(
                            cameraProfile = currentProfile,
                            isFrontCamera = isFrontCamera,
                            aspectRatio = when (currentAspectRatio) {
                                com.srihari.vintix.rendering.AspectRatio.RATIO_16_9 -> androidx.camera.core.AspectRatio.RATIO_16_9
                                else -> androidx.camera.core.AspectRatio.RATIO_4_3
                            },
                            onCameraReady = { manager ->
                                Log.d(TAG, "GLPreview camera ready")
                                cameraManager = manager
                                viewModel.onCameraReady()
                            },
                            onGlViewReady = { glViewRef = it },
                            onCameraError = { throwable ->
                                Log.e(TAG, "GLPreview error", throwable)
                                viewModel.activateSafeMode("GL Pipeline Failed")
                            }
                        )
                    }

                    val capturingState = captureState as? CaptureState.Capturing
                    CaptureFlashOverlay(
                        trigger = capturingState != null,
                        durationMs = capturingState?.feedback?.flashFadeDurationMs ?: 0,
                        onAnimationComplete = {
                            // State resets when CaptureState.Success is emitted.
                        }
                    )

                    // Focus Ring UI
                    focusPoint?.let { point ->
                        val infiniteTransition = rememberInfiniteTransition(label = "focusTransition")
                        val scale by infiniteTransition.animateFloat(
                            initialValue = 1f,
                            targetValue = 0.85f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(400, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "focusScale"
                        )
                        
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .offset(
                                    x = with(LocalDensity.current) { point.x.toDp() - 32.dp },
                                    y = with(LocalDensity.current) { point.y.toDp() - 32.dp }
                                )
                                .graphicsLayer { 
                                    alpha = focusAnimationAlpha.value
                                    scaleX = scale
                                    scaleY = scale
                                }
                        ) {
                            // Authentic focus bracket look
                            Canvas(modifier = Modifier.fillMaxSize()) {
                                val s = size.width
                                val l = s * 0.2f
                                val color = Color(0xFF00FF00)
                                val sw = 2.dp.toPx()
                                
                                // Top Left
                                drawLine(color, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(l, 0f), sw)
                                drawLine(color, androidx.compose.ui.geometry.Offset(0f, 0f), androidx.compose.ui.geometry.Offset(0f, l), sw)
                                
                                // Top Right
                                drawLine(color, androidx.compose.ui.geometry.Offset(s, 0f), androidx.compose.ui.geometry.Offset(s - l, 0f), sw)
                                drawLine(color, androidx.compose.ui.geometry.Offset(s, 0f), androidx.compose.ui.geometry.Offset(s, l), sw)
                                
                                // Bottom Left
                                drawLine(color, androidx.compose.ui.geometry.Offset(0f, s), androidx.compose.ui.geometry.Offset(l, s), sw)
                                drawLine(color, androidx.compose.ui.geometry.Offset(0f, s), androidx.compose.ui.geometry.Offset(0f, s - l), sw)
                                
                                // Bottom Right
                                drawLine(color, androidx.compose.ui.geometry.Offset(s, s), androidx.compose.ui.geometry.Offset(s - l, s), sw)
                                drawLine(color, androidx.compose.ui.geometry.Offset(s, s), androidx.compose.ui.geometry.Offset(s, s - l), sw)
                            }
                        }
                    }
                }
            }

            // --- REDESIGNED HUD (Sony Cyber-shot / Canon PowerShot Style) ---
            
            // Top Bar: Status & Quick Toggles
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(16.dp)
            ) {
                // Left side: Mode & Battery
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(width = 22.dp, height = 12.dp)
                                .border(1.dp, Color(0xFF00FF00).copy(alpha = 0.8f))
                                .padding(1.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxHeight()
                                    .fillMaxWidth(0.85f)
                                    .background(Color(0xFF00FF00).copy(alpha = 0.8f))
                            )
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "STBY",
                            color = Color(0xFF00FF00).copy(alpha = 0.8f),
                            fontFamily = FontFamily.Monospace,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    
                    StatusIndicator(text = if (isFrontCamera) "FRONT" else "MAIN")
                }

                // Right side indicators (Flash, Res, Ratio)
                Column(
                    modifier = Modifier.align(Alignment.TopEnd),
                    horizontalAlignment = Alignment.End,
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val flashLabel = when(flashMode) {
                        ImageCapture.FLASH_MODE_ON -> "⚡"
                        ImageCapture.FLASH_MODE_AUTO -> "⚡A"
                        else -> "⚡✕"
                    }
                    StatusIndicator(text = flashLabel, color = if(flashMode != ImageCapture.FLASH_MODE_OFF) Color(0xFFFFC107) else Color(0xFF00FF00).copy(alpha = 0.8f))
                    StatusIndicator(text = "${currentProfile.aspectRatio.label}")
                    StatusIndicator(text = if(appSettings.exportResolution == com.srihari.vintix.settings.ExportResolutionPreset.NATIVE) "FINE" else "STD")
                }
            }

            // Side Sliders (Compact & Matte)
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .padding(end = 12.dp)
                    .align(Alignment.CenterEnd)
            ) {
                Column(
                    modifier = Modifier
                        .width(44.dp)
                        .fillMaxHeight(0.6f)
                        .background(Color.Black.copy(alpha = 0.4f), RoundedCornerShape(22.dp))
                        .padding(vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Zoom Slider (Amber)
                    Column(
                        modifier = Modifier.weight(1f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("T", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Slider(
                            value = zoomRatio,
                            onValueChange = { viewModel.setZoomRatio(it) },
                            valueRange = 1f..8f,
                            modifier = Modifier.weight(1f).graphicsLayer { rotationZ = -90f },
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color(0xFFFF8A1F),
                                inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                            )
                        )
                        Text("W", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }

                    // Exposure (Muted White)
                    Column(
                        modifier = Modifier.weight(0.8f),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("+", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Slider(
                            value = exposureIndex.toFloat(),
                            onValueChange = { viewModel.setExposureIndex(it.toInt()) },
                            valueRange = -4f..4f,
                            steps = 8,
                            modifier = Modifier.weight(1f).graphicsLayer { rotationZ = -90f },
                            colors = SliderDefaults.colors(
                                thumbColor = Color.White,
                                activeTrackColor = Color.White.copy(alpha = 0.5f),
                                inactiveTrackColor = Color.White.copy(alpha = 0.1f)
                            )
                        )
                        Text("-", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

    // Central Grid & Level
            if (viewModel.gridEnabled.collectAsState().value) {
                CameraGrid(modifier = Modifier.fillMaxSize().align(Alignment.Center))
            }

            // Level Indicator (Authentic)
            LevelIndicator(
                modifier = Modifier.align(Alignment.Center),
                rotationDegrees = orientationManager.rotationDegrees
            )

            // Bottom Control Area
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))
                        )
                    )
                    .navigationBarsPadding()
                    .padding(bottom = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Profile Carousel
                ProfileSelector(
                    profiles = profiles.keys.toList(),
                    selectedProfile = selectedProfileName,
                    onProfileSelected = { selectedProfileName = it },
                    modifier = Modifier.padding(bottom = 20.dp)
                )

                // Main Shutter Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Gallery (Tactile Square)
                    CameraIconButton(
                        onClick = { onNavigateToGallery() },
                        modifier = Modifier.size(52.dp)
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(modifier = Modifier.size(28.dp).border(1.5.dp, Color.White, RoundedCornerShape(2.dp)))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("VIEW", color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                        }
                    }

                    // Shutter
                    Box(contentAlignment = Alignment.Center) {
                        ShutterButton(
                            onClick = {
                                if (timerSeconds > 0) countdownValue = timerSeconds
                                else captureAction()
                            },
                            enabled = cameraManager?.imageCapture != null &&
                                    captureState !is CaptureState.Capturing &&
                                    cameraAvailability is CameraAvailability.Ready &&
                                    countdownValue == 0,
                            modifier = Modifier.size(84.dp)
                        )
                        
                        if (countdownValue > 0) {
                            Text(
                                text = "$countdownValue",
                                color = Color.White,
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

                    // Settings & Quick Toggles
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        CameraIconButton(onClick = { viewModel.toggleCamera() }) {
                            Text("REV", color = Color(0xFFFFC107), fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                        }
                        CameraIconButton(onClick = { onNavigateToSettings() }) {
                            Text("SET", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace)
                        }
                    }
                }

                // Quick Mode Toggles (Timer, Grid, Ratio)
                Row(
                    modifier = Modifier.padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    QuickToggle(
                        label = if(timerSeconds > 0) "${timerSeconds}S" else "OFF",
                        icon = "⏲",
                        active = timerSeconds > 0,
                        onClick = {
                            val next = when(timerSeconds) { 0 -> 2; 2 -> 5; 5 -> 10; else -> 0 }
                            viewModel.setTimerSeconds(next)
                        }
                    )
                    QuickToggle(
                        label = currentProfile.aspectRatio.label,
                        icon = "▢",
                        active = true,
                        onClick = {
                            val next = when(currentProfile.aspectRatio) {
                                com.srihari.vintix.rendering.AspectRatio.RATIO_4_3 -> com.srihari.vintix.rendering.AspectRatio.RATIO_3_2
                                com.srihari.vintix.rendering.AspectRatio.RATIO_3_2 -> com.srihari.vintix.rendering.AspectRatio.RATIO_16_9
                                else -> com.srihari.vintix.rendering.AspectRatio.RATIO_4_3
                            }
                            viewModel.setAspectRatio(next)
                        }
                    )
                    QuickToggle(
                        label = if(viewModel.gridEnabled.collectAsState().value) "ON" else "OFF",
                        icon = "#",
                        active = viewModel.gridEnabled.collectAsState().value,
                        onClick = { viewModel.toggleGrid() }
                    )
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
private fun LevelIndicator(
    modifier: Modifier = Modifier,
    rotationDegrees: Float
) {
    val isLevel by remember(rotationDegrees) {
        derivedStateOf {
            val r = Math.abs(rotationDegrees % 90)
            r < 1.0f || r > 89.0f
        }
    }

    Box(
        modifier = modifier
            .size(280.dp)
            .graphicsLayer { rotationZ = rotationDegrees }
    ) {
        if (isLevel) {
            HorizontalDivider(
                color = Color(0xFF00FF00).copy(alpha = 0.7f),
                thickness = 1.5.dp,
                modifier = Modifier.width(32.dp).align(Alignment.CenterStart)
            )
            HorizontalDivider(
                color = Color(0xFF00FF00).copy(alpha = 0.7f),
                thickness = 1.5.dp,
                modifier = Modifier.width(32.dp).align(Alignment.CenterEnd)
            )
            // Center crosshair
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .border(1.dp, Color(0xFF00FF00).copy(alpha = 0.7f))
                    .align(Alignment.Center)
            )
        }
    }
}

@Composable
private fun QuickToggle(
    label: String,
    icon: String,
    active: Boolean,
    onClick: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Text(
            text = icon,
            color = if (active) Color(0xFF00FF00) else Color.White,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = label,
            color = if (active) Color(0xFF00FF00) else Color.White,
            fontSize = 8.sp,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun StatusIndicator(text: String, color: Color = Color(0xFF00FF00).copy(alpha = 0.85f)) {
    Text(
        text = text.uppercase(),
        color = color, // Retro green matrix style
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.5.sp,
        modifier = Modifier
            .background(Color.Black.copy(alpha = 0.5f))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    )
}

@Composable
private fun CameraGrid(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val strokeWidth = 1.dp.toPx()
        val color = Color.White.copy(alpha = 0.3f)
        
        // Vertical lines
        drawLine(color, start = androidx.compose.ui.geometry.Offset(size.width / 3, 0f), end = androidx.compose.ui.geometry.Offset(size.width / 3, size.height), strokeWidth = strokeWidth)
        drawLine(color, start = androidx.compose.ui.geometry.Offset(2 * size.width / 3, 0f), end = androidx.compose.ui.geometry.Offset(2 * size.width / 3, size.height), strokeWidth = strokeWidth)
        
        // Horizontal lines
        drawLine(color, start = androidx.compose.ui.geometry.Offset(0f, size.height / 3), end = androidx.compose.ui.geometry.Offset(size.width, size.height / 3), strokeWidth = strokeWidth)
        drawLine(color, start = androidx.compose.ui.geometry.Offset(0f, 2 * size.height / 3), end = androidx.compose.ui.geometry.Offset(size.width, 2 * size.height / 3), strokeWidth = strokeWidth)
    }
}

@Composable
private fun CameraIconButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(if (enabled) Color.Transparent else Color.Black.copy(alpha = 0.2f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}
