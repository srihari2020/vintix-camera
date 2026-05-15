package com.srihari.vintix.rendering

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import android.util.Log

/**
 * Custom [GLSurfaceView] configured for the Vintix rendering pipeline.
 *
 * Sets up an OpenGL ES 2.0 context with continuous rendering mode
 * (required for realtime camera preview).
 *
 * Lifecycle-safe: call [onPause]/[onResume] from the hosting component.
 */
class VintixGLSurfaceView(context: Context) : GLSurfaceView(context) {

    companion object {
        private const val TAG = "VintixGLSV"
    }

    /** The renderer instance — accessible for configuration. */
    val vintixRenderer: VintixRenderer

    /** Tracks whether the view is currently paused to prevent double-calls. */
    @Volatile
    private var isPaused: Boolean = false

    /** Tracks whether the view has been detached (terminal state). */
    @Volatile
    private var isDetached: Boolean = false

    init {
        // Request OpenGL ES 2.0 context
        setEGLContextClientVersion(2)
        preserveEGLContextOnPause = true

        // Create and set the renderer
        vintixRenderer = VintixRenderer()
        setRenderer(vintixRenderer)

        // Continuous rendering for realtime camera feed
        renderMode = RENDERMODE_CONTINUOUSLY

        Log.d(TAG, "Initialized — EGL context version=2, preserveOnPause=true, renderMode=CONTINUOUSLY")
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
     * Queues a [Runnable] on the GL thread, catching [IllegalStateException]
     * that may occur if the view has already been detached.
     * Returns true if the event was queued successfully.
     */
    fun safeQueueEvent(r: Runnable): Boolean {
        if (isDetached) {
            Log.w(TAG, "safeQueueEvent ignored — view already detached")
            return false
        }
        return try {
            queueEvent(r)
            true
        } catch (e: IllegalStateException) {
            Log.w(TAG, "safeQueueEvent failed — GL thread unavailable", e)
            false
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        Log.d(TAG, "onAttachedToWindow")
    }

    override fun onPause() {
        if (isDetached) {
            Log.w(TAG, "onPause ignored — view already detached")
            return
        }
        if (isPaused) {
            Log.w(TAG, "onPause ignored — already paused")
            return
        }
        Log.d(TAG, "onPause")
        isPaused = true
        try {
            super.onPause()
        } catch (e: Exception) {
            Log.e(TAG, "Exception during onPause", e)
        }
    }

    override fun onResume() {
        if (isDetached) {
            Log.w(TAG, "onResume ignored — view already detached")
            return
        }
        if (!isPaused) {
            Log.d(TAG, "onResume (was not paused — calling super anyway)")
        } else {
            Log.d(TAG, "onResume")
        }
        isPaused = false
        try {
            super.onResume()
        } catch (e: Exception) {
            Log.e(TAG, "Exception during onResume", e)
        }
    }

    /**
     * Clean up GPU resources when the view is detached.
     */
    override fun onDetachedFromWindow() {
        Log.d(TAG, "onDetachedFromWindow — releasing renderer")
        isDetached = true
        runCatching {
            queueEvent {
                vintixRenderer.release()
            }
        }.onFailure { e ->
            Log.e(TAG, "Exception queuing renderer release", e)
        }
        try {
            super.onDetachedFromWindow()
        } catch (e: Exception) {
            Log.e(TAG, "Exception during super.onDetachedFromWindow", e)
        }
    }
}
