package com.srihari.vintix.ui.camera

import android.annotation.SuppressLint
import android.util.Log
import android.view.MotionEvent
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.srihari.vintix.camera.CameraManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val TAG = "CameraPreview"

/** Timeout for the entire CameraPreview start + STREAMING sequence. */
private const val CAMERA_PREVIEW_TIMEOUT_MS = 15_000L

/** How long to wait for PreviewView to reach STREAMING after binding. */
private const val STREAMING_TIMEOUT_MS = 10_000L

@SuppressLint("ClickableViewAccessibility")
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    onCameraReady: (CameraManager) -> Unit = {},
    onCameraError: (Throwable) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraManager = remember { CameraManager(context) }

    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
            // Prevent PreviewView from consuming touch events that should reach
            // Compose overlay controls (shutter, settings, gallery, profiles).
            setOnTouchListener { _, event ->
                if (event.action == MotionEvent.ACTION_DOWN) {
                    Log.d(TAG, "PreviewView touch — passing through to Compose")
                }
                false // do NOT consume
            }
            Log.d(TAG, "PreviewView created — impl=COMPATIBLE, scaleType=FILL_CENTER")
        }
    }

    LaunchedEffect(Unit) {
        Log.d(TAG, "Starting CameraX bind sequence (timeout=${CAMERA_PREVIEW_TIMEOUT_MS}ms)")
        try {
            // Phase 1: Bind CameraX to the PreviewView's surface provider
            val bindResult = withTimeoutOrNull(CAMERA_PREVIEW_TIMEOUT_MS) {
                cameraManager.startCamera(
                    lifecycleOwner = lifecycleOwner,
                    surfaceProvider = previewView.surfaceProvider
                )
            }
            if (bindResult == null) {
                throw IllegalStateException(
                    "CameraX bind timed out after ${CAMERA_PREVIEW_TIMEOUT_MS}ms"
                )
            }
            Log.d(TAG, "CameraX bound — waiting for preview to reach STREAMING")

            // Phase 2: Wait for the PreviewView to actually start rendering frames.
            // This catches the case where binding succeeds but HAL never delivers frames.
            val streamingReached = withTimeoutOrNull(STREAMING_TIMEOUT_MS) {
                // Observe LiveData on Main thread; suspend until STREAMING or timeout
                awaitStreamState(previewView, PreviewView.StreamState.STREAMING)
            }

            if (streamingReached == null) {
                Log.w(TAG, "Preview did not reach STREAMING within ${STREAMING_TIMEOUT_MS}ms — proceeding anyway")
                // Don't fail hard — some devices report STREAMING late but frames appear.
            } else {
                Log.d(TAG, "Preview reached STREAMING — camera is fully operational")
            }

            onCameraReady(cameraManager)
        } catch (e: Exception) {
            Log.e(TAG, "Camera start failed", e)
            onCameraError(e)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            Log.d(TAG, "Disposing — stopping camera")
            cameraManager.stopCamera()
        }
    }

    AndroidView(
        factory = {
            Log.d(TAG, "AndroidView factory — PreviewView attached to hierarchy")
            previewView
        },
        modifier = modifier.fillMaxSize()
    )
}

/**
 * Suspends until [PreviewView.getPreviewStreamState] emits [targetState].
 * LiveData observation happens on the Main dispatcher.
 */
private suspend fun awaitStreamState(
    previewView: PreviewView,
    targetState: PreviewView.StreamState
): PreviewView.StreamState = withContext(Dispatchers.Main) {
    suspendCancellableCoroutine { continuation ->
        val liveData = previewView.previewStreamState
        val observer = object : androidx.lifecycle.Observer<PreviewView.StreamState> {
            override fun onChanged(value: PreviewView.StreamState) {
                Log.d(TAG, "PreviewStreamState: $value")
                if (value == targetState) {
                    liveData.removeObserver(this)
                    if (continuation.isActive) {
                        continuation.resume(value)
                    }
                }
            }
        }
        liveData.observeForever(observer)
        continuation.invokeOnCancellation {
            liveData.removeObserver(observer)
        }
    }
}
