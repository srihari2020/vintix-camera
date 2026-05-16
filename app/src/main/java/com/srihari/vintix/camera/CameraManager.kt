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
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK

    /** Guards against duplicate bindToLifecycle calls. */
    @Volatile
    private var _isBound: Boolean = false

    /** Whether the camera is currently bound to a lifecycle. */
    val isBound: Boolean get() = _isBound

    /** Exposed for capture callers. Only available after [startCamera] completes. */
    var imageCapture: ImageCapture? = null
        private set

    fun toggleCamera(lifecycleOwner: LifecycleOwner, surface: Surface? = null, surfaceProvider: Preview.SurfaceProvider? = null) {
        lensFacing = if (lensFacing == CameraSelector.LENS_FACING_BACK) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }
        
        runOnMain {
            lifecycleOwner.lifecycleScope.launch {
                if (surface != null) {
                    startCamera(lifecycleOwner, surface)
                } else if (surfaceProvider != null) {
                    startCamera(lifecycleOwner, surfaceProvider)
                }
            }
        }
    }

    /**
     * Starts camera with a standard CameraX SurfaceProvider.
     * Used by [CameraPreview] composable (PreviewView-based).
     */
    suspend fun startCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider
    ) {
        Log.d(TAG, "startCamera(SurfaceProvider, facing=$lensFacing) — requesting CameraProvider")

        val provider = getTimedCameraProvider()
        if (provider == null) {
            val msg = "CameraProvider timed out after ${PROVIDER_TIMEOUT_MS}ms"
            Log.e(TAG, msg)
            throw IllegalStateException(msg)
        }

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

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        withContext(Dispatchers.Main) {
            try {
                Log.d(TAG, "Unbinding all use cases before rebind")
                provider.unbindAll()
                _isBound = false

                Log.d(TAG, "bindToLifecycle — preview + imageCapture")
                provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCaptureUseCase
                )
                previewUseCase = preview
                imageCapture = imageCaptureUseCase
                _isBound = true
                Log.d(TAG, "Camera bound successfully (SurfaceProvider mode)")
            } catch (e: Exception) {
                previewUseCase = null
                imageCapture = null
                _isBound = false
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
        Log.d(TAG, "startCamera(Surface, facing=$lensFacing) — requesting CameraProvider")

        val provider = getTimedCameraProvider()
        if (provider == null) {
            val msg = "CameraProvider timed out after ${PROVIDER_TIMEOUT_MS}ms"
            Log.e(TAG, msg)
            throw IllegalStateException(msg)
        }

        val rotation = targetRotation()

        val preview = Preview.Builder()
            .setTargetRotation(rotation)
            .build()
            .also {
                it.surfaceProvider = Preview.SurfaceProvider { request ->
                    Log.d(TAG, "SurfaceProvider.onSurfaceRequested — providing GL surface")
                    request.provideSurface(
                        surface,
                        ContextCompat.getMainExecutor(context)
                    ) { result ->
                        Log.d(TAG, "Surface release callback (resultCode=${result.resultCode})")
                    }
                }
            }

        val imageCaptureUseCase = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(rotation)
            .build()

        val cameraSelector = CameraSelector.Builder()
            .requireLensFacing(lensFacing)
            .build()

        withContext(Dispatchers.Main) {
            try {
                Log.d(TAG, "Unbinding all use cases before rebind")
                provider.unbindAll()
                _isBound = false

                Log.d(TAG, "bindToLifecycle — preview(Surface) + imageCapture")
                provider.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageCaptureUseCase
                )
                previewUseCase = preview
                imageCapture = imageCaptureUseCase
                _isBound = true
                Log.d(TAG, "Camera bound successfully (Surface/GL mode)")
            } catch (e: Exception) {
                previewUseCase = null
                imageCapture = null
                _isBound = false
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
        if (!_isBound && cameraProvider == null) {
            Log.d(TAG, "stopCamera — already stopped, skipping")
            return
        }
        Log.d(TAG, "stopCamera — unbinding all")
        runOnMain {
            try {
                cameraProvider?.unbindAll()
            } catch (e: Exception) {
                Log.e(TAG, "Error during unbindAll", e)
            }
            previewUseCase = null
            imageCapture = null
            _isBound = false
        }
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            ContextCompat.getMainExecutor(context).execute(block)
        }
    }

    /**
     * Gets the CameraProvider with a timeout to handle vendor delays
     * (e.g., OnePlus devices that may stall ProcessCameraProvider.getInstance).
     */
    private suspend fun getTimedCameraProvider(): ProcessCameraProvider? {
        // If we already have a provider, return it immediately
        cameraProvider?.let {
            Log.d(TAG, "Reusing existing CameraProvider")
            return it
        }

        Log.d(TAG, "Awaiting ProcessCameraProvider.getInstance (timeout=${PROVIDER_TIMEOUT_MS}ms)")
        return withTimeoutOrNull(PROVIDER_TIMEOUT_MS) {
            getCameraProvider()
        }
    }

    private suspend fun getCameraProvider(): ProcessCameraProvider =
        suspendCancellableCoroutine { continuation ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                try {
                    val provider = future.get()
                    cameraProvider = provider
                    Log.d(TAG, "ProcessCameraProvider obtained successfully")
                    continuation.resume(provider)
                } catch (e: Exception) {
                    Log.e(TAG, "ProcessCameraProvider.getInstance failed", e)
                    continuation.resumeWith(Result.failure(e))
                }
            }, ContextCompat.getMainExecutor(context))
            continuation.invokeOnCancellation {
                Log.d(TAG, "getCameraProvider cancelled")
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
        /** Timeout for ProcessCameraProvider.getInstance — generous for slow vendor init. */
        private const val PROVIDER_TIMEOUT_MS = 10_000L
    }
}
