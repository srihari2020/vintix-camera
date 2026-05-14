package com.srihari.vintix.camera

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Looper
import android.util.Log
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

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null

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
        val rotation = targetRotation()

        val preview = Preview.Builder()
            .setTargetRotation(rotation)
            .build()
            .also {
                it.surfaceProvider = surfaceProvider
            }

        val imageCaptureUseCase = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(rotation)
            .build()

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
                previewUseCase = preview
                imageCapture = imageCaptureUseCase
            } catch (e: Exception) {
                previewUseCase = null
                imageCapture = null
                Log.e(TAG, "Camera binding failed", e)
                throw e
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
        val rotation = targetRotation()

        val preview = Preview.Builder()
            .setTargetRotation(rotation)
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
            .setTargetRotation(rotation)
            .build()

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
                previewUseCase = preview
                imageCapture = imageCaptureUseCase
            } catch (e: Exception) {
                previewUseCase = null
                imageCapture = null
                Log.e(TAG, "Camera binding failed", e)
                throw e
            }
        }
    }

    fun updateTargetRotation() {
        val rotation = targetRotation()
        runOnMain {
            previewUseCase?.targetRotation = rotation
            imageCapture?.targetRotation = rotation
        }
    }

    fun stopCamera() {
        runOnMain {
            cameraProvider?.unbindAll()
            previewUseCase = null
            imageCapture = null
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            ContextCompat.getMainExecutor(context).execute(block)
        }
    }

    private suspend fun getCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    val provider = future.get()
                    cameraProvider = provider
                    continuation.resume(provider)
                } catch (e: Exception) {
                    continuation.resumeWith(Result.failure(e))
                }
            }, ContextCompat.getMainExecutor(context))
            continuation.invokeOnCancellation {
                future.cancel(true)
            }
        }

    private fun targetRotation(): Int {
        val activity = context.findActivity()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity?.display?.rotation ?: context.display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            activity?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }

    private companion object {
        private const val TAG = "CameraManager"
    }
}

