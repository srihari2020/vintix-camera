package com.srihari.vintix.ui.camera

import android.net.Uri
import android.util.Log
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

    fun onPermissionResult(isGranted: Boolean) {
        _isCameraPermissionGranted.value = isGranted
        if (isGranted) {
            _cameraAvailability.value = CameraAvailability.Initializing
        }
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
     * Called when the GL pipeline fails. Activates safe mode so subsequent
     * compositions (including after config changes) use CameraPreview.
     */
    fun activateSafeMode(reason: String) {
        Log.w(TAG, "Activating SAFE MODE: $reason")
        _safeModeActive.value = true
        // Reset to Initializing so CameraPreview gets a clean start
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

    fun resetCaptureState() {
        _captureState.value = CaptureState.Idle
    }
}
