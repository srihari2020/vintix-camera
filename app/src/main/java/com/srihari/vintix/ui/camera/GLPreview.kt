package com.srihari.vintix.ui.camera

import android.util.Log
import android.view.Surface
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.srihari.vintix.camera.CameraManager
import com.srihari.vintix.rendering.CameraProfile
import com.srihari.vintix.rendering.VintixGLSurfaceView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val TAG = "GLPreview"

/** Timeout for CameraX start from GLPreview (covers provider + bind). */
private const val CAMERA_START_TIMEOUT_MS = 12_000L

/**
 * Jetpack Compose wrapper that bridges CameraX and the OpenGL rendering pipeline.
 *
 * This is the **only** composable that imports from both `rendering/` and `camera/`
 * packages, keeping them fully decoupled from each other.
 *
 * Flow:
 * 1. Creates [VintixGLSurfaceView] with a SurfaceTexture callback
 * 2. When the GL surface is ready, wraps [SurfaceTexture] in a [Surface]
 * 3. Starts CameraX with the Surface (on the main thread)
 * 4. Camera frames flow: CameraX → SurfaceTexture → OES texture → shader → screen
 *
 * @param cameraProfile The active [CameraProfile] to apply to the renderer.
 * @param onCameraReady Callback with the [CameraManager] once camera is started.
 *                      Matches [CameraPreview]'s API for seamless swap in [CameraScreen].
 * @param onGlViewReady Optional callback with the [VintixGLSurfaceView] (e.g. for noise snapshot on capture).
 */
@Composable
fun GLPreview(
    modifier: Modifier = Modifier,
    cameraProfile: CameraProfile = CameraProfile.Default,
    isFrontCamera: Boolean = false,
    onCameraReady: (CameraManager) -> Unit = {},
    onGlViewReady: (VintixGLSurfaceView) -> Unit = {},
    onCameraError: (Throwable) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val configuration = LocalConfiguration.current
    val cameraManager = remember { CameraManager(context) }
    val scope = rememberCoroutineScope()
    val activeSurface = remember { AtomicReference<Surface?>() }
    val isDisposed = remember { AtomicBoolean(false) }

    // Re-bind camera when lens facing changes
    LaunchedEffect(isFrontCamera) {
        val surface = activeSurface.get()
        if (surface != null && !isDisposed.get()) {
            Log.d(TAG, "Lens facing changed to ${if (isFrontCamera) "FRONT" else "BACK"} — toggling camera")
            cameraManager.toggleCamera(lifecycleOwner, surface = surface)
        }
    }

    val glSurfaceView = remember {
        VintixGLSurfaceView(context).apply {
            // Set up the pipeline failure callback — fires on GL thread if shaders fail
            vintixRenderer.onGlPipelineFailed = { e ->
                Log.e(TAG, "GL pipeline failed — triggering fallback", e)
                scope.launch(Dispatchers.Main) {
                    if (!isDisposed.get()) {
                        onCameraError(e)
                    }
                }
            }

            setOnSurfaceTextureAvailable { surfaceTexture ->
                Log.d(TAG, "SurfaceTexture available — starting CameraX")
                if (isDisposed.get()) {
                    Log.w(TAG, "SurfaceTexture arrived after dispose — ignoring")
                    return@setOnSurfaceTextureAvailable
                }
                // GL thread → main thread: start CameraX with this surface
                scope.launch(Dispatchers.Main) {
                    if (isDisposed.get()) {
                        Log.w(TAG, "Disposed before camera start — aborting")
                        return@launch
                    }
                    val surface = Surface(surfaceTexture)
                    val previousSurface = activeSurface.get()
                    try {
                        Log.d(TAG, "Starting CameraX with GL Surface (timeout=${CAMERA_START_TIMEOUT_MS}ms)")
                        val result = withTimeoutOrNull(CAMERA_START_TIMEOUT_MS) {
                            cameraManager.startCamera(lifecycleOwner, surface)
                        }
                        if (result == null) {
                            throw IllegalStateException("CameraX start timed out after ${CAMERA_START_TIMEOUT_MS}ms")
                        }
                        activeSurface.set(surface)
                        previousSurface?.release()
                        Log.d(TAG, "CameraX started successfully with GL Surface")
                        onCameraReady(cameraManager)
                    } catch (e: Exception) {
                        Log.e(TAG, "CameraX start failed", e)
                        surface.release()
                        previousSurface?.release()
                        activeSurface.set(null)
                        onCameraError(e)
                    }
                }
            }
        }
    }

    LaunchedEffect(glSurfaceView) {
        onGlViewReady(glSurfaceView)
    }

    // Push profile updates to the GL thread safely
    LaunchedEffect(cameraProfile) {
        try {
            glSurfaceView.safeQueueEvent {
                glSurfaceView.vintixRenderer.cameraProfile = cameraProfile
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to queue profile update", e)
        }
    }

    LaunchedEffect(configuration.orientation, configuration.screenWidthDp, configuration.screenHeightDp) {
        cameraManager.updateTargetRotation()
    }

    // Handle lifecycle pause/resume for the GL surface
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> {
                    Log.d(TAG, "Lifecycle ON_PAUSE")
                    glSurfaceView.onPause()
                }
                Lifecycle.Event.ON_RESUME -> {
                    Log.d(TAG, "Lifecycle ON_RESUME")
                    glSurfaceView.onResume()
                }
                else -> { /* no-op */ }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            Log.d(TAG, "onDispose — stopping camera, releasing surface, removing GL view")
            isDisposed.set(true)
            lifecycleOwner.lifecycle.removeObserver(observer)
            cameraManager.stopCamera()
            activeSurface.getAndSet(null)?.release()

            // Explicitly remove GLSurfaceView from its parent to prevent the
            // SurfaceView zombie from sitting above Compose and stealing touches.
            try {
                (glSurfaceView.parent as? ViewGroup)?.removeView(glSurfaceView)
                Log.d(TAG, "GLSurfaceView removed from parent")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove GLSurfaceView from parent", e)
            }
        }
    }

    AndroidView(
        factory = { glSurfaceView },
        modifier = modifier.fillMaxSize()
    )
}
