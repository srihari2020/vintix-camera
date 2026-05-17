package com.srihari.vintix.camera

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.Looper
import android.util.Log
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
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
    private var camera: Camera? = null

    private val orientationEventListener by lazy {
        object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                
                // Map device orientation to closest 90-degree angle
                val rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                
                // Only update if it actually changed to avoid unnecessary rebinds/updates
                if (rotation != lastRotation) {
                    lastRotation = rotation
                    updateTargetRotation(rotation)
                }
            }
        }
    }

    private var lastRotation: Int = Surface.ROTATION_0

    /** Guards against duplicate bindToLifecycle calls. */
    @Volatile
    private var _isBound: Boolean = false

    /** Whether the camera is currently bound to a lifecycle. */
    val isBound: Boolean get() = _isBound

    /** Exposed for capture callers. Only available after [startCamera] completes. */
    var imageCapture: ImageCapture? = null
        private set



    /**
     * Starts camera with a standard CameraX SurfaceProvider.
     * Used by [CameraPreview] composable (PreviewView-based).
     */
    suspend fun startCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        facing: Int? = null,
        aspectRatio: Int = androidx.camera.core.AspectRatio.RATIO_4_3
    ) {
        if (facing != null) {
            lensFacing = facing
        }
        Log.d(TAG, "startCamera(SurfaceProvider, facing=$lensFacing) — requesting CameraProvider")

        val provider = getTimedCameraProvider()
        if (provider == null) {
            val msg = "CameraProvider timed out after ${PROVIDER_TIMEOUT_MS}ms"
            Log.e(TAG, msg)
            throw IllegalStateException(msg)
        }

        val rotation = targetRotation()
        orientationEventListener.enable()

        val preview = Preview.Builder()
            .setTargetRotation(rotation)
            .setTargetAspectRatio(aspectRatio)
            .build()
            .also {
                it.surfaceProvider = surfaceProvider
            }

        val imageCaptureUseCase = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setTargetRotation(rotation)
            .setIoExecutor(ioExecutor)
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
                camera = provider.bindToLifecycle(
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
                camera = null
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
    private val ioExecutor = Executors.newSingleThreadExecutor()



    fun updateTargetRotation(rotation: Int = targetRotation()) {
        runOnMain {
            try {
                previewUseCase?.targetRotation = rotation
                imageCapture?.targetRotation = rotation
                Log.d(TAG, "Target rotation updated to: $rotation")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to update target rotation", e)
            }
        }
    }

    fun focus(x: Float, y: Float, width: Int, height: Int) {
        val cameraControl = camera?.cameraControl ?: return
        val factory = SurfaceOrientedMeteringPointFactory(width.toFloat(), height.toFloat())
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF)
            .setAutoCancelDuration(3000, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        cameraControl.startFocusAndMetering(action)
    }

    fun zoom(ratio: Float) {
        camera?.cameraControl?.setZoomRatio(ratio)
    }

    fun setLinearZoom(value: Float) {
        camera?.cameraControl?.setLinearZoom(value)
    }

    fun setExposure(value: Int) {
        camera?.cameraControl?.setExposureCompensationIndex(value)
    }

    fun getExposureRange(): android.util.Range<Int>? {
        return camera?.cameraInfo?.exposureState?.exposureCompensationRange
    }

    fun setFlashMode(mode: Int) {
        imageCapture?.flashMode = mode
    }

    fun stopCamera() {
        orientationEventListener.disable()
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
            camera = null
            _isBound = false
        }
    }

    /** Releases resources, including the background executor. */
    fun release() {
        stopCamera()
        ioExecutor.shutdown()
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
        val rotation = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            activity?.display?.rotation ?: context.display?.rotation ?: Surface.ROTATION_0
        } else {
            @Suppress("DEPRECATION")
            activity?.windowManager?.defaultDisplay?.rotation ?: Surface.ROTATION_0
        }
        Log.d(TAG, "Current target rotation: $rotation")
        return rotation
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
