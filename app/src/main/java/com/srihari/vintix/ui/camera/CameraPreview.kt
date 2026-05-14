package com.srihari.vintix.ui.camera

import android.util.Log
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

private const val TAG = "CameraPreview"

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
        }
    }

    LaunchedEffect(Unit) {
        Log.d(TAG, "Starting CameraX with PreviewView")
        try {
            cameraManager.startCamera(
                lifecycleOwner = lifecycleOwner,
                surfaceProvider = previewView.surfaceProvider
            )
            Log.d(TAG, "Camera started successfully")
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
        factory = { previewView },
        modifier = modifier.fillMaxSize()
    )
}
