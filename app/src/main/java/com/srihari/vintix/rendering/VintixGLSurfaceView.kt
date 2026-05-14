package com.srihari.vintix.rendering

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView

/**
 * Custom [GLSurfaceView] configured for the Vintix rendering pipeline.
 *
 * Sets up an OpenGL ES 2.0 context with continuous rendering mode
 * (required for realtime camera preview).
 *
 * Lifecycle-safe: call [onPause]/[onResume] from the hosting component.
 */
class VintixGLSurfaceView(context: Context) : GLSurfaceView(context) {

    /** The renderer instance — accessible for configuration. */
    val vintixRenderer: VintixRenderer

    init {
        // Request OpenGL ES 2.0 context
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true

        // Create and set the renderer
        vintixRenderer = VintixRenderer()
        setRenderer(vintixRenderer)

        // Continuous rendering for realtime camera feed
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /**
     * Sets a callback to receive the [SurfaceTexture] once the GL surface is ready.
     * The SurfaceTexture is created on the GL thread and can be used to feed
     * camera frames into the renderer.
     *
     * Must be called before the renderer's onSurfaceCreated fires.
     */
    fun setOnSurfaceTextureAvailable(callback: (SurfaceTexture) -> Unit) {
        vintixRenderer.onSurfaceTextureAvailable = callback
    }

    /**
     * Clean up GPU resources when the view is detached.
     */
    override fun onDetachedFromWindow() {
        runCatching {
            queueEvent {
                vintixRenderer.release()
            }
        }
        super.onDetachedFromWindow()
    }
}
