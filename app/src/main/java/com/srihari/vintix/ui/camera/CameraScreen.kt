package com.srihari.vintix.ui.camera

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.media.AudioManager
import android.media.MediaActionSound
import android.media.ToneGenerator
import android.view.HapticFeedbackConstants
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.AspectRatio as CameraXAspectRatio
import androidx.camera.core.ImageCapture
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.srihari.vintix.camera.CameraManager
import com.srihari.vintix.camera.DeviceOrientationManager
import com.srihari.vintix.camera.PhotoCaptureManager
import com.srihari.vintix.effects.AspectRatio
import com.srihari.vintix.effects.CameraProfile
import com.srihari.vintix.effects.CameraProfiles
import com.srihari.vintix.settings.SettingsViewModel
import com.srihari.vintix.settings.timestampStyle
import com.srihari.vintix.settings.withVintixSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

private const val CAMERA_INIT_TIMEOUT_MS = 20_000L

private val MatteBlack = Color(0xFF050505)
private val PanelBlack = Color(0xCC080808)
private val SoftBlack = Color(0xFF111111)
private val DigicamGreen = Color(0xFF6DFF8F)
private val Amber = Color(0xFFFF8A1F)
private val MutedWhite = Color(0xB8FFFFFF)

@Composable
fun CameraScreen(
    viewModel: CameraViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel(),
    onNavigateToGallery: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
) {
    val context = LocalContext.current
    val view = LocalView.current
    val scope = rememberCoroutineScope()

    val isCameraPermissionGranted by viewModel.isCameraPermissionGranted.collectAsState()
    val captureState by viewModel.captureState.collectAsState()
    val cameraAvailability by viewModel.cameraAvailability.collectAsState()
    val isFrontCamera by viewModel.isFrontCamera.collectAsState()
    val flashMode by viewModel.flashMode.collectAsState()
    val zoomRatio by viewModel.zoomRatio.collectAsState()
    val exposureIndex by viewModel.exposureIndex.collectAsState()
    val retryKey by viewModel.retryKey.collectAsState()
    val currentAspectRatio by viewModel.aspectRatio.collectAsState()
    val timerSeconds by viewModel.timerSeconds.collectAsState()
    val gridEnabled by viewModel.gridEnabled.collectAsState()
    val hdrEnabled by viewModel.hdrPlaceholderEnabled.collectAsState()
    val appSettings by settingsViewModel.settings.collectAsState()

    val profiles = remember { CameraProfiles.all }
    val profileNames = remember(profiles) { profiles.map { it.displayName } }
    var selectedProfileName by rememberSaveable { mutableStateOf(CameraProfiles.CyberShot2003.displayName) }
    val selectedBaseProfile = profiles.firstOrNull { it.displayName == selectedProfileName } ?: CameraProfiles.CyberShot2003
    val activeProfile = selectedBaseProfile
        .copy(aspectRatio = currentAspectRatio)
        .withVintixSettings(appSettings)

    var cameraManager by remember { mutableStateOf<CameraManager?>(null) }
    var previewSize by remember { mutableStateOf(IntSize.Zero) }
    var focusPoint by remember { mutableStateOf<Offset?>(null) }
    var lastSavedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    var countdownValue by remember { mutableIntStateOf(0) }
    val focusAlpha = remember { Animatable(0f) }

    val photoCaptureManager = remember { PhotoCaptureManager(context) }
    val orientationManager = remember { DeviceOrientationManager(context) }
    val toneGenerator = remember { ToneGenerator(AudioManager.STREAM_SYSTEM, 70) }
    val shutterSound = remember {
        MediaActionSound().apply { load(MediaActionSound.SHUTTER_CLICK) }
    }

    DisposableEffect(Unit) {
        orientationManager.start()
        onDispose {
            orientationManager.stop()
            shutterSound.release()
            toneGenerator.release()
            photoCaptureManager.shutdown()
        }
    }

    LaunchedEffect(Unit) {
        delay(220)
        if (appSettings.soundEnabled) {
            toneGenerator.startTone(ToneGenerator.TONE_PROP_ACK, 70)
        }
    }

    LaunchedEffect(selectedProfileName) {
        viewModel.setAspectRatio(selectedBaseProfile.aspectRatio)
    }

    LaunchedEffect(flashMode, cameraManager) {
        cameraManager?.setFlashMode(flashMode)
    }
    LaunchedEffect(zoomRatio, cameraManager) {
        cameraManager?.zoom(zoomRatio)
    }
    LaunchedEffect(exposureIndex, cameraManager) {
        cameraManager?.setExposure(exposureIndex)
    }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { }
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onPermissionResult(granted)
        if (granted) {
            requestLegacyStoragePermissionIfNeeded(context) { permission ->
                storagePermissionLauncher.launch(permission)
            }
        }
    }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            viewModel.onPermissionResult(true)
            requestLegacyStoragePermissionIfNeeded(context) { permission ->
                storagePermissionLauncher.launch(permission)
            }
        } else {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    LaunchedEffect(cameraAvailability, retryKey, isCameraPermissionGranted) {
        if (isCameraPermissionGranted && cameraAvailability is CameraAvailability.Initializing) {
            delay(CAMERA_INIT_TIMEOUT_MS)
            if (viewModel.cameraAvailability.value is CameraAvailability.Initializing) {
                viewModel.onCameraError("Camera timed out. Tap RETRY.")
            }
        }
    }

    val captureAction = {
        val capture = cameraManager?.imageCapture
        if (capture != null && cameraAvailability is CameraAvailability.Ready && countdownValue == 0) {
            val feedback = activeProfile.feedbackBehavior
            if (feedback.hapticFeedback) {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
            if (feedback.soundEnabled) {
                if (feedback.useDigitalBeep) {
                    toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP, 55)
                } else {
                    shutterSound.play(MediaActionSound.SHUTTER_CLICK)
                }
            }

            viewModel.onCaptureStarted(feedback)
            photoCaptureManager.capturePhoto(
                imageCapture = capture,
                cameraProfile = activeProfile,
                profileName = selectedProfileName,
                timestampStyle = appSettings.timestampStyle(),
                onSuccess = { uri ->
                    lastSavedUri = uri
                    viewModel.onPhotoCaptured(uri)
                    scope.launch {
                        delay(180)
                        viewModel.resetCaptureState()
                    }
                },
                onError = { exception ->
                    viewModel.onCaptureError(exception.message ?: "Capture failed")
                    scope.launch {
                        delay(500)
                        viewModel.resetCaptureState()
                    }
                },
            )
        }
    }

    LaunchedEffect(countdownValue) {
        if (countdownValue > 0) {
            delay(1000)
            countdownValue -= 1
            if (countdownValue == 0) captureAction()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MatteBlack),
    ) {
        if (isCameraPermissionGranted) {
            CameraBody(
                modifier = Modifier.fillMaxSize(),
                profile = activeProfile,
                isFrontCamera = isFrontCamera,
                retryKey = retryKey,
                previewSize = previewSize,
                onPreviewSizeChanged = { previewSize = it },
                zoomRatio = zoomRatio,
                onZoomChanged = viewModel::setZoomRatio,
                onFocus = { point ->
                    focusPoint = point
                    cameraManager?.focus(point.x, point.y, previewSize.width, previewSize.height)
                    scope.launch {
                        focusAlpha.snapTo(1f)
                        focusAlpha.animateTo(0f, animationSpec = tween(1100))
                        focusPoint = null
                    }
                },
                onDoubleTapSwitch = viewModel::toggleCamera,
                onSwipeFilter = { direction ->
                    selectedProfileName = profileNames.nextName(selectedProfileName, direction)
                },
                focusPoint = focusPoint,
                focusAlpha = focusAlpha.value,
                onCameraReady = { manager ->
                    cameraManager = manager
                    manager.setFlashMode(flashMode)
                    manager.zoom(zoomRatio)
                    manager.setExposure(exposureIndex)
                    viewModel.onCameraReady()
                },
                onCameraError = { throwable ->
                    viewModel.onCameraError(throwable.message ?: "Camera unavailable")
                },
            )

            if (gridEnabled) {
                CameraGrid(modifier = Modifier.fillMaxSize())
            }

            LevelIndicator(
                modifier = Modifier.align(Alignment.Center),
                rotationDegrees = orientationManager.rotationDegrees,
            )

            val capturing = captureState as? CaptureState.Capturing
            CaptureFlashOverlay(
                trigger = capturing?.sequence,
                durationMs = capturing?.feedback?.flashFadeDurationMs ?: 0,
                onAnimationComplete = {},
            )

            TopStatusBar(
                flashMode = flashMode,
                timerSeconds = timerSeconds,
                aspectRatio = currentAspectRatio,
                soundEnabled = appSettings.soundEnabled,
                hdrEnabled = hdrEnabled,
                isFrontCamera = isFrontCamera,
                onFlashClick = {
                    viewModel.setFlashMode(nextFlashMode(flashMode))
                },
                onTimerClick = {
                    viewModel.setTimerSeconds(nextTimer(timerSeconds))
                },
                onAspectClick = {
                    viewModel.setAspectRatio(nextAspectRatio(currentAspectRatio))
                },
                onSoundClick = {
                    settingsViewModel.setSoundEnabled(!appSettings.soundEnabled)
                },
                onHdrClick = viewModel::toggleHdrPlaceholder,
                onSettingsClick = onNavigateToSettings,
                modifier = Modifier.align(Alignment.TopCenter),
            )

            ExposureZoomRail(
                zoomRatio = zoomRatio,
                exposureIndex = exposureIndex,
                onZoomChanged = viewModel::setZoomRatio,
                onExposureChanged = viewModel::setExposureIndex,
                modifier = Modifier.align(Alignment.CenterEnd),
            )

            BottomControls(
                profiles = profiles,
                selectedProfile = selectedProfileName,
                onProfileSelected = { selectedProfileName = it },
                lastSavedUri = lastSavedUri,
                isShutterEnabled = cameraAvailability is CameraAvailability.Ready && countdownValue == 0,
                countdownValue = countdownValue,
                onGalleryClick = onNavigateToGallery,
                onShutterClick = {
                    if (timerSeconds > 0) countdownValue = timerSeconds else captureAction()
                },
                onSwitchCamera = viewModel::toggleCamera,
                gridEnabled = gridEnabled,
                onGridClick = viewModel::toggleGrid,
                modifier = Modifier.align(Alignment.BottomCenter),
            )

            CameraProblemBanner(
                availability = cameraAvailability,
                onRetry = viewModel::retryCamera,
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            PermissionPanel(
                onRequestPermission = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun CameraBody(
    profile: CameraProfile,
    isFrontCamera: Boolean,
    retryKey: Int,
    previewSize: IntSize,
    zoomRatio: Float,
    onPreviewSizeChanged: (IntSize) -> Unit,
    onZoomChanged: (Float) -> Unit,
    onFocus: (Offset) -> Unit,
    onDoubleTapSwitch: () -> Unit,
    onSwipeFilter: (Int) -> Unit,
    focusPoint: Offset?,
    focusAlpha: Float,
    onCameraReady: (CameraManager) -> Unit,
    onCameraError: (Throwable) -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    var dragAmount by remember { mutableFloatStateOf(0f) }
    val transformableState = rememberTransformableState { zoomChange, _, _ ->
        onZoomChanged((zoomRatio * zoomChange).coerceIn(1f, 8f))
    }
    val previewRatio = if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
        profile.aspectRatio.ratio
    } else {
        1f / profile.aspectRatio.ratio
    }

    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(previewRatio)
                .clip(RectangleShape)
                .background(Color.Black)
                .border(1.dp, Color.White.copy(alpha = 0.10f))
                .onGloballyPositioned { onPreviewSizeChanged(it.size) }
                .pointerInput(previewSize) {
                    detectTapGestures(
                        onTap = onFocus,
                        onDoubleTap = { onDoubleTapSwitch() },
                    )
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (abs(dragAmount) > 86f) {
                                onSwipeFilter(if (dragAmount < 0f) 1 else -1)
                            }
                            dragAmount = 0f
                        },
                        onDragCancel = { dragAmount = 0f },
                        onHorizontalDrag = { change, delta ->
                            dragAmount += delta
                            change.consume()
                        },
                    )
                }
                .transformable(transformableState),
        ) {
            CameraPreview(
                isFrontCamera = isFrontCamera,
                aspectRatio = profile.aspectRatio.toCameraXAspectRatio(),
                retryKey = retryKey,
                onCameraReady = onCameraReady,
                onCameraError = onCameraError,
            )
            FocusRing(
                point = focusPoint,
                alpha = focusAlpha,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun TopStatusBar(
    flashMode: Int,
    timerSeconds: Int,
    aspectRatio: AspectRatio,
    soundEnabled: Boolean,
    hdrEnabled: Boolean,
    isFrontCamera: Boolean,
    onFlashClick: () -> Unit,
    onTimerClick: () -> Unit,
    onAspectClick: () -> Unit,
    onSoundClick: () -> Unit,
    onHdrClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.82f), Color.Transparent),
                ),
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                BatteryIcon()
                StatusPill(text = "STBY", color = DigicamGreen)
                StatusPill(text = if (isFrontCamera) "FRONT" else "MAIN", color = MutedWhite)
            }

            TopChip(text = "SET", active = false, onClick = onSettingsClick)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopChip(text = flashLabel(flashMode), active = flashMode != ImageCapture.FLASH_MODE_OFF, onClick = onFlashClick)
            TopChip(text = if (timerSeconds == 0) "TMR" else "${timerSeconds}S", active = timerSeconds != 0, onClick = onTimerClick)
            TopChip(text = aspectRatio.label, active = true, onClick = onAspectClick)
            TopChip(text = if (soundEnabled) "SND" else "MUTE", active = soundEnabled, onClick = onSoundClick)
            TopChip(text = "HDR", active = hdrEnabled, onClick = onHdrClick)
        }
    }
}

@Composable
private fun BottomControls(
    profiles: List<CameraProfile>,
    selectedProfile: String,
    onProfileSelected: (String) -> Unit,
    lastSavedUri: android.net.Uri?,
    isShutterEnabled: Boolean,
    countdownValue: Int,
    onGalleryClick: () -> Unit,
    onShutterClick: () -> Unit,
    onSwitchCamera: () -> Unit,
    gridEnabled: Boolean,
    onGridClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.88f)),
                ),
            )
            .navigationBarsPadding()
            .padding(bottom = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ProfileSelector(
            profiles = profiles,
            selectedProfile = selectedProfile,
            onProfileSelected = onProfileSelected,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            GalleryThumbButton(
                uri = lastSavedUri,
                onClick = onGalleryClick,
                modifier = Modifier.size(58.dp),
            )

            Box(contentAlignment = Alignment.Center) {
                ShutterButton(
                    onClick = onShutterClick,
                    enabled = isShutterEnabled,
                    modifier = Modifier.size(86.dp),
                )
                if (countdownValue > 0) {
                    Text(
                        text = "$countdownValue",
                        color = Color.Black,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                RoundCommand(text = "REV", accent = Amber, onClick = onSwitchCamera)
                RoundCommand(text = if (gridEnabled) "GRID" else "GRID", accent = if (gridEnabled) DigicamGreen else MutedWhite, onClick = onGridClick)
            }
        }
    }
}

@Composable
private fun ExposureZoomRail(
    zoomRatio: Float,
    exposureIndex: Int,
    onZoomChanged: (Float) -> Unit,
    onExposureChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .padding(end = 10.dp)
            .width(46.dp)
            .fillMaxHeight(0.52f)
            .clip(RoundedCornerShape(23.dp))
            .background(PanelBlack)
            .padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        VerticalSliderBlock(
            topLabel = "T",
            bottomLabel = "W",
            value = zoomRatio,
            valueRange = 1f..8f,
            onValueChange = onZoomChanged,
            accent = Amber,
            modifier = Modifier.weight(1f),
        )
        HorizontalDivider(color = Color.White.copy(alpha = 0.10f), modifier = Modifier.padding(vertical = 8.dp))
        VerticalSliderBlock(
            topLabel = "+",
            bottomLabel = "-",
            value = exposureIndex.toFloat(),
            valueRange = -4f..4f,
            steps = 8,
            onValueChange = { onExposureChanged(it.toInt()) },
            accent = DigicamGreen,
            modifier = Modifier.weight(0.9f),
        )
    }
}

@Composable
private fun VerticalSliderBlock(
    topLabel: String,
    bottomLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    onValueChange: (Float) -> Unit,
    accent: Color,
    modifier: Modifier = Modifier,
    steps: Int = 0,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(topLabel, color = MutedWhite, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier
                .weight(1f)
                .graphicsLayer { rotationZ = -90f },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = accent,
                inactiveTrackColor = Color.White.copy(alpha = 0.20f),
            ),
        )
        Text(bottomLabel, color = MutedWhite, fontFamily = FontFamily.Monospace, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CameraProblemBanner(
    availability: CameraAvailability,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (availability !is CameraAvailability.Error) return
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black.copy(alpha = 0.78f))
            .border(1.dp, Amber.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = availability.message.uppercase(),
            color = Amber,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
        TextButton(onClick = onRetry, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) {
            Text("RETRY", color = Color.White, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PermissionPanel(onRequestPermission: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "CAMERA ACCESS REQUIRED",
            color = MutedWhite,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
        )
        TextButton(onClick = onRequestPermission) {
            Text("ALLOW", color = Amber, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun GalleryThumbButton(uri: android.net.Uri?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SoftBlack)
            .border(1.dp, Color.White.copy(alpha = 0.32f), RoundedCornerShape(8.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (uri != null) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(uri).crossfade(true).build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                text = "VIEW",
                color = MutedWhite,
                fontFamily = FontFamily.Monospace,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun RoundCommand(text: String, accent: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 58.dp, height = 28.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .border(1.dp, accent.copy(alpha = 0.55f), RoundedCornerShape(14.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = accent,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Black,
        )
    }
}

@Composable
private fun TopChip(text: String, active: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .heightIn(min = 28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (active) Color(0x3329FF5A) else Color.Black.copy(alpha = 0.42f))
            .border(1.dp, if (active) DigicamGreen.copy(alpha = 0.65f) else Color.White.copy(alpha = 0.18f), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (active) DigicamGreen else MutedWhite,
            fontFamily = FontFamily.Monospace,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.sp,
        )
    }
}

@Composable
private fun StatusPill(text: String, color: Color) {
    Text(
        text = text,
        color = color,
        fontFamily = FontFamily.Monospace,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.48f))
            .padding(horizontal = 6.dp, vertical = 3.dp),
    )
}

@Composable
private fun BatteryIcon() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            modifier = Modifier
                .size(width = 24.dp, height = 12.dp)
                .border(1.dp, DigicamGreen.copy(alpha = 0.85f), RoundedCornerShape(2.dp))
                .padding(2.dp),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.82f)
                    .background(DigicamGreen.copy(alpha = 0.85f)),
            )
        }
        Box(
            modifier = Modifier
                .size(width = 2.dp, height = 6.dp)
                .background(DigicamGreen.copy(alpha = 0.85f)),
        )
    }
}

@Composable
private fun FocusRing(point: Offset?, alpha: Float, modifier: Modifier = Modifier) {
    if (point == null || alpha <= 0f) return
    val density = LocalDensity.current
    val infiniteTransition = rememberInfiniteTransition(label = "focus-pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0.86f,
        animationSpec = infiniteRepeatable(tween(420, easing = LinearEasing), RepeatMode.Reverse),
        label = "focus-scale",
    )

    Box(modifier = modifier) {
        Canvas(
            modifier = Modifier
                .size(66.dp)
                .offset(
                    x = with(density) { point.x.toDp() - 33.dp },
                    y = with(density) { point.y.toDp() - 33.dp },
                )
                .graphicsLayer {
                    this.alpha = alpha
                    scaleX = scale
                    scaleY = scale
                },
        ) {
            val line = size.width * 0.24f
            val stroke = 2.dp.toPx()
            val color = DigicamGreen
            drawLine(color, Offset(0f, 0f), Offset(line, 0f), stroke)
            drawLine(color, Offset(0f, 0f), Offset(0f, line), stroke)
            drawLine(color, Offset(size.width, 0f), Offset(size.width - line, 0f), stroke)
            drawLine(color, Offset(size.width, 0f), Offset(size.width, line), stroke)
            drawLine(color, Offset(0f, size.height), Offset(line, size.height), stroke)
            drawLine(color, Offset(0f, size.height), Offset(0f, size.height - line), stroke)
            drawLine(color, Offset(size.width, size.height), Offset(size.width - line, size.height), stroke)
            drawLine(color, Offset(size.width, size.height), Offset(size.width, size.height - line), stroke)
        }
    }
}

@Composable
private fun LevelIndicator(modifier: Modifier = Modifier, rotationDegrees: Float) {
    val normalized = abs(rotationDegrees % 90f)
    val isLevel = normalized < 1.2f || normalized > 88.8f
    if (!isLevel) return

    Box(
        modifier = modifier
            .size(260.dp)
            .graphicsLayer { rotationZ = rotationDegrees },
    ) {
        HorizontalDivider(
            color = DigicamGreen.copy(alpha = 0.62f),
            thickness = 1.dp,
            modifier = Modifier
                .width(34.dp)
                .align(Alignment.CenterStart),
        )
        HorizontalDivider(
            color = DigicamGreen.copy(alpha = 0.62f),
            thickness = 1.dp,
            modifier = Modifier
                .width(34.dp)
                .align(Alignment.CenterEnd),
        )
        Box(
            modifier = Modifier
                .size(8.dp)
                .border(1.dp, DigicamGreen.copy(alpha = 0.62f))
                .align(Alignment.Center),
        )
    }
}

@Composable
private fun CameraGrid(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier) {
        val stroke = 1.dp.toPx()
        val color = Color.White.copy(alpha = 0.24f)
        drawLine(color, Offset(size.width / 3f, 0f), Offset(size.width / 3f, size.height), stroke)
        drawLine(color, Offset(size.width * 2f / 3f, 0f), Offset(size.width * 2f / 3f, size.height), stroke)
        drawLine(color, Offset(0f, size.height / 3f), Offset(size.width, size.height / 3f), stroke)
        drawLine(color, Offset(0f, size.height * 2f / 3f), Offset(size.width, size.height * 2f / 3f), stroke)
    }
}

private fun List<String>.nextName(current: String, direction: Int): String {
    if (isEmpty()) return current
    val currentIndex = indexOf(current).takeIf { it >= 0 } ?: 0
    val nextIndex = (currentIndex + direction).floorMod(size)
    return this[nextIndex]
}

private fun Int.floorMod(modulus: Int): Int {
    val result = this % modulus
    return if (result < 0) result + modulus else result
}

private fun AspectRatio.toCameraXAspectRatio(): Int {
    return when (this) {
        AspectRatio.RATIO_16_9 -> CameraXAspectRatio.RATIO_16_9
        AspectRatio.RATIO_4_3,
        AspectRatio.RATIO_3_2 -> CameraXAspectRatio.RATIO_4_3
    }
}

private fun nextFlashMode(current: Int): Int {
    return when (current) {
        ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO
        ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON
        else -> ImageCapture.FLASH_MODE_OFF
    }
}

private fun nextTimer(current: Int): Int {
    return when (current) {
        0 -> 2
        2 -> 5
        5 -> 10
        else -> 0
    }
}

private fun nextAspectRatio(current: AspectRatio): AspectRatio {
    return when (current) {
        AspectRatio.RATIO_4_3 -> AspectRatio.RATIO_3_2
        AspectRatio.RATIO_3_2 -> AspectRatio.RATIO_16_9
        AspectRatio.RATIO_16_9 -> AspectRatio.RATIO_4_3
    }
}

private fun flashLabel(mode: Int): String {
    return when (mode) {
        ImageCapture.FLASH_MODE_AUTO -> "F A"
        ImageCapture.FLASH_MODE_ON -> "F ON"
        else -> "F OFF"
    }
}

private fun requestLegacyStoragePermissionIfNeeded(
    context: android.content.Context,
    launch: (String) -> Unit,
) {
    if (
        android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.P &&
        ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED
    ) {
        launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}
