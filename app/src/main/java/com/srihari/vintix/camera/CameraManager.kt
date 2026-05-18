package com.srihari.vintix.camera

import android.annotation.SuppressLint
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
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.Executors
import kotlin.coroutines.resume

class CameraManager(private val context: Context) {
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewUseCase: Preview? = null
    private var camera: Camera? = null
    private var lensFacing: Int = CameraSelector.LENS_FACING_BACK
    private var lastRotation: Int = Surface.ROTATION_0

    private val cameraIoExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var bound = false

    var imageCapture: ImageCapture? = null
        private set

    val isBound: Boolean
        get() = bound

    private val orientationEventListener by lazy {
        object : OrientationEventListener(context) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == ORIENTATION_UNKNOWN) return
                val rotation = when (orientation) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                if (rotation != lastRotation) {
                    lastRotation = rotation
                    updateTargetRotation(rotation)
                }
            }
        }
    }

    @SuppressLint("MissingPermission")
    suspend fun startCamera(
        lifecycleOwner: LifecycleOwner,
        surfaceProvider: Preview.SurfaceProvider,
        facing: Int = lensFacing,
        aspectRatio: Int = androidx.camera.core.AspectRatio.RATIO_4_3,
    ) {
        val provider = getTimedCameraProvider()
            ?: throw IllegalStateException("CameraProvider timed out after ${PROVIDER_TIMEOUT_MS}ms")

        val requestedSelector = CameraSelector.Builder()
            .requireLensFacing(facing)
            .build()
        val backSelector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        val hasRequestedCamera = hasCamera(provider, requestedSelector)
        val selector = when {
            hasRequestedCamera -> requestedSelector
            hasCamera(provider, backSelector) -> backSelector
            else -> throw IllegalStateException("No usable camera is available")
        }
        lensFacing = if (hasRequestedCamera) facing else CameraSelector.LENS_FACING_BACK

        val rotation = targetRotation()
        lastRotation = rotation
        orientationEventListener.enable()
        val resolutionSelector = ResolutionSelector.Builder()
            .setAspectRatioStrategy(
                AspectRatioStrategy(aspectRatio, AspectRatioStrategy.FALLBACK_RULE_AUTO),
            )
            .build()

        val preview = Preview.Builder()
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(rotation)
            .build()
            .also { it.surfaceProvider = surfaceProvider }

        val capture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setResolutionSelector(resolutionSelector)
            .setTargetRotation(rotation)
            .setIoExecutor(cameraIoExecutor)
            .build()

        withContext(Dispatchers.Main) {
            provider.unbindAll()
            bound = false
            camera = provider.bindToLifecycle(lifecycleOwner, selector, preview, capture)
            previewUseCase = preview
            imageCapture = capture
            bound = true
        }
    }

    fun updateTargetRotation(rotation: Int = targetRotation()) {
        runOnMain {
            previewUseCase?.targetRotation = rotation
            imageCapture?.targetRotation = rotation
        }
    }

    fun focus(x: Float, y: Float, width: Int, height: Int) {
        val currentCamera = camera ?: return
        if (width <= 0 || height <= 0) return
        val factory = SurfaceOrientedMeteringPointFactory(width.toFloat(), height.toFloat())
        val point = factory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(2500, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()
        currentCamera.cameraControl.startFocusAndMetering(action)
    }

    fun zoom(ratio: Float) {
        val currentCamera = camera ?: return
        val zoomState = currentCamera.cameraInfo.zoomState.value
        val minZoom = zoomState?.minZoomRatio ?: 1f
        val maxZoom = zoomState?.maxZoomRatio ?: 8f
        currentCamera.cameraControl.setZoomRatio(ratio.coerceIn(minZoom, maxZoom))
    }

    fun setExposure(value: Int) {
        val currentCamera = camera ?: return
        val range = currentCamera.cameraInfo.exposureState.exposureCompensationRange
        currentCamera.cameraControl.setExposureCompensationIndex(value.coerceIn(range.lower, range.upper))
    }

    fun getExposureRange(): android.util.Range<Int>? {
        return camera?.cameraInfo?.exposureState?.exposureCompensationRange
    }

    fun setFlashMode(mode: Int) {
        imageCapture?.flashMode = mode
    }

    fun stopCamera() {
        orientationEventListener.disable()
        val provider = cameraProvider ?: return
        runOnMain {
            provider.unbindAll()
            previewUseCase = null
            imageCapture = null
            camera = null
            bound = false
        }
    }

    fun release() {
        stopCamera()
        cameraIoExecutor.shutdown()
    }

    private fun hasCamera(provider: ProcessCameraProvider, selector: CameraSelector): Boolean {
        return runCatching { provider.hasCamera(selector) }.getOrDefault(false)
    }

    private fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            ContextCompat.getMainExecutor(context).execute(block)
        }
    }

    private suspend fun getTimedCameraProvider(): ProcessCameraProvider? {
        cameraProvider?.let { return it }
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
                    continuation.resume(provider)
                } catch (e: Exception) {
                    Log.e(TAG, "ProcessCameraProvider failed", e)
                    continuation.resumeWith(Result.failure(e))
                }
            }, ContextCompat.getMainExecutor(context))
            continuation.invokeOnCancellation { future.cancel(true) }
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
        private const val PROVIDER_TIMEOUT_MS = 10_000L
    }
}
