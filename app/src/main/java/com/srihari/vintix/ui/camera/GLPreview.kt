package com.srihari.vintix.ui.camera

import android.view.Surface
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
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
    onCameraReady: (CameraManager) -> Unit = {},
    onGlViewReady: (VintixGLSurfaceView) -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraManager = remember { CameraManager(context) }
    val scope = rememberCoroutineScope()

    val glSurfaceView = remember {
        VintixGLSurfaceView(context).apply {
            setOnSurfaceTextureAvailable { surfaceTexture ->
                // GL thread → main thread: start CameraX with this surface
                scope.launch(Dispatchers.Main) {
                    val surface = Surface(surfaceTexture)
                    cameraManager.startCamera(lifecycleOwner, surface)
                    onCameraReady(cameraManager)
                }
            }
        }
    }

    LaunchedEffect(glSurfaceView) {
        onGlViewReady(glSurfaceView)
    }

    // Push profile updates to the GL thread safely
    LaunchedEffect(cameraProfile) {
        glSurfaceView.queueEvent {
            glSurfaceView.vintixRenderer.cameraProfile = cameraProfile
        }
    }

    // Handle lifecycle pause/resume for the GL surface
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_PAUSE -> glSurfaceView.onPause()
                Lifecycle.Event.ON_RESUME -> glSurfaceView.onResume()
                else -> { /* no-op */ }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    AndroidView(
        factory = { glSurfaceView },
        modifier = modifier.fillMaxSize()
    )
}

