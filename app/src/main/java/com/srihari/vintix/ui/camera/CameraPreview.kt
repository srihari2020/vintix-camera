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
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "CameraPreview"

/** Timeout for the entire CameraPreview start sequence. */
private const val CAMERA_PREVIEW_TIMEOUT_MS = 15_000L

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

    Log.d(TAG, "CameraPreview composed (safe mode path)")

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
        Log.d(TAG, "Starting CameraX with PreviewView (timeout=${CAMERA_PREVIEW_TIMEOUT_MS}ms)")
        try {
            val result = withTimeoutOrNull(CAMERA_PREVIEW_TIMEOUT_MS) {
                cameraManager.startCamera(
                    lifecycleOwner = lifecycleOwner,
                    surfaceProvider = previewView.surfaceProvider
                )
            }
            if (result == null) {
                throw IllegalStateException(
                    "CameraPreview timed out after ${CAMERA_PREVIEW_TIMEOUT_MS}ms"
                )
            }
            Log.d(TAG, "Camera started successfully — preview should be visible")
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
