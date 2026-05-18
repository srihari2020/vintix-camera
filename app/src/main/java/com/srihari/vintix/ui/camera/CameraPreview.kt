package com.srihari.vintix.ui.camera

import android.annotation.SuppressLint
import androidx.camera.core.CameraSelector
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Observer
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.srihari.vintix.camera.CameraManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

private const val CAMERA_PREVIEW_TIMEOUT_MS = 15_000L
private const val STREAMING_TIMEOUT_MS = 8_000L

@SuppressLint("ClickableViewAccessibility")
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    isFrontCamera: Boolean,
    aspectRatio: Int,
    retryKey: Int,
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
            setOnTouchListener { _, _ -> false }
        }
    }

    LaunchedEffect(lifecycleOwner, isFrontCamera, aspectRatio, retryKey) {
        try {
            val bound = withTimeoutOrNull(CAMERA_PREVIEW_TIMEOUT_MS) {
                cameraManager.startCamera(
                    lifecycleOwner = lifecycleOwner,
                    surfaceProvider = previewView.surfaceProvider,
                    facing = if (isFrontCamera) {
                        CameraSelector.LENS_FACING_FRONT
                    } else {
                        CameraSelector.LENS_FACING_BACK
                    },
                    aspectRatio = aspectRatio,
                )
            }
            if (bound == null) {
                throw IllegalStateException("CameraX preview timed out after ${CAMERA_PREVIEW_TIMEOUT_MS}ms")
            }

            withTimeoutOrNull(STREAMING_TIMEOUT_MS) {
                awaitStreamState(previewView, PreviewView.StreamState.STREAMING)
            }
            onCameraReady(cameraManager)
        } catch (e: Exception) {
            onCameraError(e)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraManager.release()
        }
    }

    AndroidView(
        factory = { previewView },
        modifier = modifier.fillMaxSize(),
    )
}

private suspend fun awaitStreamState(
    previewView: PreviewView,
    targetState: PreviewView.StreamState,
): PreviewView.StreamState = withContext(Dispatchers.Main) {
    suspendCancellableCoroutine { continuation ->
        val liveData = previewView.previewStreamState
        val observer = object : Observer<PreviewView.StreamState> {
            override fun onChanged(value: PreviewView.StreamState) {
                if (value == targetState) {
                    liveData.removeObserver(this)
                    if (continuation.isActive) continuation.resume(value)
                }
            }
        }
        liveData.observeForever(observer)
        continuation.invokeOnCancellation {
            liveData.removeObserver(observer)
        }
    }
}
