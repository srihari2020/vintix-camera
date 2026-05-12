package com.srihari.vintix.camera

import android.content.Context
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Manages CameraX use cases (Preview + ImageCapture).
 * Supports two preview modes:
 * - Standard [Preview.SurfaceProvider] (used by CameraPreview composable)
 * - Raw [Surface] (used by OpenGL GLPreview composable)
 */
class CameraManager(private val context: Context) {

    /** Exposed for capture callers. Only available after [startCamera] completes. */
    var imageCapture: ImageCapture? = null
        private set

    /**
     * Starts camera with a standard CameraX SurfaceProvider.
     * Used by [CameraPreview] composable (PreviewView-based).
     */
    suspend fun startCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider
    ) {
        val cameraProvider = getCameraProvider()

        val preview = Preview.Builder()
            .build()
            .also {
                it.surfaceProvider = surfaceProvider
            }

        val imageCaptureUseCase = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        imageCapture = imageCaptureUseCase

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        withContext(Dispatchers.Main) {
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCaptureUseCase
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Starts camera with a raw [Surface] from an OpenGL SurfaceTexture.
     * Used by [GLPreview] composable for the GPU rendering pipeline.
     *
     * The surface receives camera preview frames that are rendered
     * through the OpenGL shader pipeline.
     */
    suspend fun startCamera(
        lifecycleOwner: LifecycleOwner,
        surface: Surface
    ) {
        val cameraProvider = getCameraProvider()

        val preview = Preview.Builder()
            .build()
            .also {
                it.surfaceProvider = Preview.SurfaceProvider { request ->
                    request.provideSurface(
                        surface,
                        ContextCompat.getMainExecutor(context)
                    ) { /* Surface release handled by GL lifecycle */ }
                }
            }

        val imageCaptureUseCase = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        imageCapture = imageCaptureUseCase

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        withContext(Dispatchers.Main) {
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCaptureUseCase
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private suspend fun getCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    continuation.resume(future.get())
                } catch (e: Exception) {
                    continuation.resumeWith(Result.failure(e))
                }
            }, ContextCompat.getMainExecutor(context))
        }
}

