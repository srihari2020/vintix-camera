package com.srihari.vintix.ui.camera

import android.net.Uri
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "CameraViewModel"

/** Represents the current state of photo capture. */
sealed interface CaptureState {
    data object Idle : CaptureState
    data class Capturing(val feedback: com.srihari.vintix.rendering.FeedbackBehavior) : CaptureState
    data class Success(val uri: Uri) : CaptureState
    data class Error(val message: String) : CaptureState
}

sealed interface CameraAvailability {
    data object Initializing : CameraAvailability
    data object Ready : CameraAvailability
    data class Error(val message: String) : CameraAvailability
}

class CameraViewModel : ViewModel() {

    private val _isCameraPermissionGranted = MutableStateFlow(false)
    val isCameraPermissionGranted: StateFlow<Boolean> = _isCameraPermissionGranted.asStateFlow()

    private val _captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

    private val _cameraAvailability = MutableStateFlow<CameraAvailability>(CameraAvailability.Initializing)
    val cameraAvailability: StateFlow<CameraAvailability> = _cameraAvailability.asStateFlow()

    /**
     * When true, the GL pipeline has failed and we should use CameraPreview (PreviewView).
     * Stored in ViewModel so the flag survives configuration changes (orientation, etc.).
     */
    private val _safeModeActive = MutableStateFlow(false)
    val safeModeActive: StateFlow<Boolean> = _safeModeActive.asStateFlow()

    /** Incremented on each retry to force CameraPreview recomposition. */
    private val _retryKey = MutableStateFlow(0)
    val retryKey: StateFlow<Int> = _retryKey.asStateFlow()

    private val _isFrontCamera = MutableStateFlow(false)
    val isFrontCamera: StateFlow<Boolean> = _isFrontCamera.asStateFlow()

    private val _flashMode = MutableStateFlow(ImageCapture.FLASH_MODE_OFF)
    val flashMode: StateFlow<Int> = _flashMode.asStateFlow()

    private val _zoomRatio = MutableStateFlow(1f)
    val zoomRatio: StateFlow<Float> = _zoomRatio.asStateFlow()

    private val _exposureIndex = MutableStateFlow(0)
    val exposureIndex: StateFlow<Int> = _exposureIndex.asStateFlow()

    private val _aspectRatio = MutableStateFlow(com.srihari.vintix.rendering.AspectRatio.RATIO_4_3)
    val aspectRatio: StateFlow<com.srihari.vintix.rendering.AspectRatio> = _aspectRatio.asStateFlow()

    private val _timerSeconds = MutableStateFlow(0)
    val timerSeconds: StateFlow<Int> = _timerSeconds.asStateFlow()

    private val _gridEnabled = MutableStateFlow(false)
    val gridEnabled: StateFlow<Boolean> = _gridEnabled.asStateFlow()

    fun onPermissionResult(isGranted: Boolean) {
        _isCameraPermissionGranted.value = isGranted
        if (isGranted) {
            _cameraAvailability.value = CameraAvailability.Initializing
        }
    }

    fun setFlashMode(mode: Int) {
        _flashMode.value = mode
    }

    fun setZoomRatio(ratio: Float) {
        _zoomRatio.value = ratio
    }

    fun setExposureIndex(index: Int) {
        _exposureIndex.value = index
    }

    fun setAspectRatio(ratio: com.srihari.vintix.rendering.AspectRatio) {
        _aspectRatio.value = ratio
    }

    fun setTimerSeconds(seconds: Int) {
        _timerSeconds.value = seconds
    }

    fun toggleGrid() {
        _gridEnabled.value = !_gridEnabled.value
    }

    fun onCaptureStarted(feedback: com.srihari.vintix.rendering.FeedbackBehavior) {
        _captureState.value = CaptureState.Capturing(feedback)
    }

    fun onPhotoCaptured(uri: Uri) {
        _captureState.value = CaptureState.Success(uri)
    }

    fun onCaptureError(message: String) {
        _captureState.value = CaptureState.Error(message)
    }

    fun onCameraReady() {
        Log.d(TAG, "Camera ready — availability=READY")
        _cameraAvailability.value = CameraAvailability.Ready
    }

    fun onCameraError(message: String) {
        Log.e(TAG, "Camera error: $message")
        _cameraAvailability.value = CameraAvailability.Error(message)
    }

    /**
     * Called when the GL pipeline fails. Activates safe mode silently.
     */
    fun activateSafeMode(reason: String) {
        Log.w(TAG, "Activating SILENT SAFE MODE: $reason")
        _safeModeActive.value = true
        // Keep camera availability as is, or reset if needed for PreviewView
        _cameraAvailability.value = CameraAvailability.Initializing
    }

    /**
     * Reset camera to Initializing so a fresh bind attempt can proceed.
     * Also increments retryKey to force CameraPreview recomposition.
     */
    fun retryCamera() {
        Log.d(TAG, "Retrying camera — resetting to Initializing")
        _cameraAvailability.value = CameraAvailability.Initializing
        _retryKey.value++
    }

    fun toggleCamera() {
        _isFrontCamera.value = !_isFrontCamera.value
        _cameraAvailability.value = CameraAvailability.Initializing
    }

    fun resetCaptureState() {
        _captureState.value = CaptureState.Idle
    }
}
