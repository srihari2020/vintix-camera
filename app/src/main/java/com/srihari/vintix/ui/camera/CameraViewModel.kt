package com.srihari.vintix.ui.camera

import android.net.Uri
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.srihari.vintix.effects.AspectRatio
import com.srihari.vintix.effects.FeedbackBehavior
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private const val TAG = "CameraViewModel"

sealed interface CaptureState {
    data object Idle : CaptureState
    data class Capturing(val feedback: FeedbackBehavior, val sequence: Long) : CaptureState
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

    private val _aspectRatio = MutableStateFlow(AspectRatio.RATIO_4_3)
    val aspectRatio: StateFlow<AspectRatio> = _aspectRatio.asStateFlow()

    private val _timerSeconds = MutableStateFlow(0)
    val timerSeconds: StateFlow<Int> = _timerSeconds.asStateFlow()

    private val _gridEnabled = MutableStateFlow(false)
    val gridEnabled: StateFlow<Boolean> = _gridEnabled.asStateFlow()

    private val _hdrPlaceholderEnabled = MutableStateFlow(false)
    val hdrPlaceholderEnabled: StateFlow<Boolean> = _hdrPlaceholderEnabled.asStateFlow()

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

    fun setAspectRatio(ratio: AspectRatio) {
        if (_aspectRatio.value != ratio) {
            _aspectRatio.value = ratio
            _cameraAvailability.value = CameraAvailability.Initializing
        }
    }

    fun setTimerSeconds(seconds: Int) {
        _timerSeconds.value = seconds
    }

    fun toggleGrid() {
        _gridEnabled.value = !_gridEnabled.value
    }

    fun toggleHdrPlaceholder() {
        _hdrPlaceholderEnabled.value = !_hdrPlaceholderEnabled.value
    }

    fun onCaptureStarted(feedback: FeedbackBehavior) {
        val sequence = System.nanoTime()
        _captureState.value = CaptureState.Capturing(feedback, sequence)

        viewModelScope.launch {
            kotlinx.coroutines.delay(5_000)
            val currentCapture = _captureState.value as? CaptureState.Capturing
            if (currentCapture?.sequence == sequence) {
                Log.w(TAG, "Capture safety timeout reached - resetting state")
                resetCaptureState()
            }
        }
    }

    fun onPhotoCaptured(uri: Uri) {
        _captureState.value = CaptureState.Success(uri)
    }

    fun onCaptureError(message: String) {
        _captureState.value = CaptureState.Error(message)
    }

    fun onCameraReady() {
        _cameraAvailability.value = CameraAvailability.Ready
    }

    fun onCameraError(message: String) {
        Log.e(TAG, "Camera error: $message")
        _cameraAvailability.value = CameraAvailability.Error(message)
    }

    fun retryCamera() {
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
