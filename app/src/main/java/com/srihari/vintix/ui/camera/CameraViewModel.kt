package com.srihari.vintix.ui.camera

import android.net.Uri
import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Represents the current state of photo capture. */
sealed interface CaptureState {
    data object Idle : CaptureState
    data class Capturing(val feedback: com.srihari.vintix.rendering.FeedbackBehavior) : CaptureState
    data class Success(val uri: Uri) : CaptureState
    data class Error(val message: String) : CaptureState
}

class CameraViewModel : ViewModel() {

    private val _isCameraPermissionGranted = MutableStateFlow(false)
    val isCameraPermissionGranted: StateFlow<Boolean> = _isCameraPermissionGranted.asStateFlow()

    private val _captureState = MutableStateFlow<CaptureState>(CaptureState.Idle)
    val captureState: StateFlow<CaptureState> = _captureState.asStateFlow()

    fun onPermissionResult(isGranted: Boolean) {
        _isCameraPermissionGranted.value = isGranted
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

    fun resetCaptureState() {
        _captureState.value = CaptureState.Idle
    }
}

