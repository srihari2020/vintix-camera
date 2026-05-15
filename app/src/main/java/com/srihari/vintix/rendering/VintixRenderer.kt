package com.srihari.vintix.rendering

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import com.srihari.vintix.telemetry.PerformanceTelemetry

/**
 * Core Vintix GPU renderer implementing [GLSurfaceView.Renderer] and [SurfaceTexture.OnFrameAvailableListener].
 *
 * Supports two modes:
 * - **Camera mode**: Renders live CameraX frames via [SurfaceTexture] + OES external texture
 * - **Fallback mode**: Renders a checkerboard texture (when no camera is connected)
 *
 * Architecture:
 * - [CameraProfile] drives fragment uniforms (vignette, halation, warmth, etc.).
 * - [CameraProfiles] holds named presets; assign [cameraProfile] on the GL thread when possible.
 * - Multi-pass GLSL retro effects (future): swap fragment shader or chain programs.
 * - Framebuffer objects for off-screen rendering.
 * - Filter chaining via multiple [ShaderProgram] instances.
 */
class VintixRenderer : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    companion object {
        private const val TAG = "VintixRenderer"

        /**
         * Vertex shader for camera mode.
         * Applies the SurfaceTexture transform matrix to texture coordinates
         * so camera frames render with correct orientation.
         */
        const val CAMERA_VERTEX_SHADER = """
            attribute vec4 aPosition;
            attribute vec2 aTexCoord;
            uniform mat4 uTexMatrix;
            varying vec2 vTexCoord;
            void main() {
                gl_Position = aPosition;
                vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
            }
        """

        /**
         * Fragment shader for camera mode (OES external texture).
         * Body is shared with still export via [RetroPipelineShaders].
         */
        fun cameraFragmentShader(): String = RetroPipelineShaders.fragmentShaderExternalOes()
    }

    private lateinit var shaderProgram: ShaderProgram
    private lateinit var quad: TexturedQuad
    private var oesTextureId: Int = 0
    private var textureUniformLocation: Int = 0
    private var texMatrixUniformLocation: Int = 0
    private var profileUniforms: CameraProfileUniformHandles? = null
    private var noisePhase: Float = 0f
    private var rendererReady: Boolean = false
    private var surfaceWidth: Int = 0
    private var surfaceHeight: Int = 0

    /** Guards against concurrent updates and drawing. */
    @Volatile
    private var frameAvailable: Boolean = false

    /**
     * Set to true if the GL pipeline initialization failed.
     * When true, [onDrawFrame] becomes a no-op (clear only) and
     * [onGlPipelineFailed] is invoked to trigger fallback.
     */
    @Volatile
    var initFailed: Boolean = false
        private set

    /**
     * Latest noise phase used for preview (for aligning still export grain with live view).
     * Updated on the GL thread each frame.
     */
    @Volatile
    var noisePhaseSnapshot: Float = 0f
        private set

    /**
     * Active camera look; uploaded as fragment uniforms each frame.
     * Prefer assigning on the GL thread before or during [onDrawFrame].
     */
    var cameraProfile: CameraProfile = CameraProfile.Default

    /** The SurfaceTexture that receives camera frames. Created on the GL thread. */
    private var surfaceTexture: SurfaceTexture? = null

    /** Whether the SurfaceTexture has been released (guards against double-release). */
    @Volatile
    private var surfaceTextureReleased: Boolean = false

    /** Transform matrix provided by SurfaceTexture for correct frame orientation. */
    private val texTransformMatrix = FloatArray(16)

    /**
     * Callback invoked on the GL thread when the [SurfaceTexture] is ready.
     * The UI layer uses this to bridge the SurfaceTexture to CameraX
     * without the rendering package importing any camera classes.
     */
    var onSurfaceTextureAvailable: ((SurfaceTexture) -> Unit)? = null

    /**
     * Callback invoked to tell the GLSurfaceView to request a render pass.
     */
    var requestRender: (() -> Unit)? = null

    /**
     * Callback invoked (on the GL thread) when the GL pipeline fails to initialize.
     * The UI layer can use this to trigger a fallback to CameraPreview.
     */
    var onGlPipelineFailed: ((Exception) -> Unit)? = null

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        frameAvailable = true
        requestRender?.invoke()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Log.d(TAG, "onSurfaceCreated — initializing camera pipeline")
        
        // Log GPU capabilities
        val vendor = GLES20.glGetString(GLES20.GL_VENDOR)
        val renderer = GLES20.glGetString(GLES20.GL_RENDERER)
        val version = GLES20.glGetString(GLES20.GL_VERSION)
        Log.i(TAG, "GPU Vendor: $vendor")
        Log.i(TAG, "GPU Renderer: $renderer")
        Log.i(TAG, "GPU Version: $version")

        val maxUniforms = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_FRAGMENT_UNIFORM_VECTORS, maxUniforms, 0)
        Log.i(TAG, "Max Fragment Uniform Vectors: ${maxUniforms[0]}")

        val maxVarying = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_VARYING_VECTORS, maxVarying, 0)
        Log.i(TAG, "Max Varying Vectors: ${maxVarying[0]}")

        try {
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
            rendererReady = false
            initFailed = false
            releaseSurfaceTextureSafely()

            Matrix.setIdentityM(texTransformMatrix, 0)

            // Try compiling shaders with graceful degradation
            val levels = listOf(
                RetroPipelineShaders.ShaderLevel.FULL,
                RetroPipelineShaders.ShaderLevel.MEDIUM,
                RetroPipelineShaders.ShaderLevel.MINIMAL
            )

            var success = false
            for (level in levels) {
                Log.i(TAG, "Attempting to initialize pipeline with shader level: $level")
                
                shaderProgram = ShaderProgram()
                val vertexShader = shaderProgram.compile(GLES20.GL_VERTEX_SHADER, CAMERA_VERTEX_SHADER)
                val fragmentShader = shaderProgram.compile(GLES20.GL_FRAGMENT_SHADER, RetroPipelineShaders.fragmentShaderExternalOes(level))

                if (vertexShader != 0 && fragmentShader != 0 && shaderProgram.link(vertexShader, fragmentShader)) {
                    Log.i(TAG, "Successfully initialized pipeline with level: $level")
                    success = true
                    break
                } else {
                    Log.w(TAG, "Level $level failed, falling back...")
                    shaderProgram.release()
                }
            }

            if (!success) {
                Log.e(TAG, "All shader levels failed to compile/link")
                initFailed = true
                notifyPipelineFailed(RuntimeException("All shader levels failed"))
                return
            }

            textureUniformLocation = shaderProgram.getUniformLocation("uTexture")
            texMatrixUniformLocation = shaderProgram.getUniformLocation("uTexMatrix")
            profileUniforms = CameraProfileUniformHandles(shaderProgram)

            quad = TexturedQuad()
            oesTextureId = createOESTexture()
            surfaceTexture = SurfaceTexture(oesTextureId)
            surfaceTexture?.setOnFrameAvailableListener(this)

            onSurfaceTextureAvailable?.invoke(surfaceTexture!!)

            rendererReady = true
            Log.d(TAG, "Renderer ready")
        } catch (e: Exception) {
            Log.e(TAG, "Exception during onSurfaceCreated", e)
            initFailed = true
            notifyPipelineFailed(e)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Log.d(TAG, "onSurfaceChanged: ${width}x${height}")
        surfaceWidth = width
        surfaceHeight = height
        GLES20.glViewport(0, 0, width, height)

        // Update the SurfaceTexture default buffer size to match the viewport
        if (!surfaceTextureReleased) {
            surfaceTexture?.setDefaultBufferSize(width, height)
        }
    }

    /**
     * Set to true to temporarily stop fetching new camera frames, freezing the viewfinder.
     */
    @Volatile
    var freezePreview: Boolean = false

    override fun onDrawFrame(gl: GL10?) {
        try {
            val startNs = System.nanoTime()
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            // If init failed or not ready, just clear to black
            if (initFailed || !rendererReady) return

            // Pull the latest camera frame into the OES texture ONLY if available
            val st = surfaceTexture
            if (st != null && !surfaceTextureReleased) {
                if (frameAvailable) {
                    if (!freezePreview) {
                        try {
                            st.updateTexImage()
                            st.getTransformMatrix(texTransformMatrix)
                        } catch (e: RuntimeException) {
                            Log.e(TAG, "SurfaceTexture update failed", e)
                        }
                    }
                    frameAvailable = false
                }
            }

            // Bind shader program
            shaderProgram.use()

            // Pass the texture transform matrix
            GLES20.glUniformMatrix4fv(texMatrixUniformLocation, 1, false, texTransformMatrix, 0)

            noisePhase = (noisePhase + 0.019f).rem(1f)
            noisePhaseSnapshot = noisePhase
            profileUniforms?.upload(
                cameraProfile, 
                noisePhase, 
                false,
                width = surfaceWidth.toFloat(),
                height = surfaceHeight.toFloat()
            )

            // Bind OES texture to unit 0
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES20.glUniform1i(textureUniformLocation, 0)

            // Draw the fullscreen quad
            quad.draw(shaderProgram)

            PerformanceTelemetry.recordFrame(System.nanoTime() - startNs)
        } catch (e: Exception) {
            Log.e(TAG, "CRITICAL: onDrawFrame crashed", e)
            // Don't let one bad frame kill the GL thread
        }
    }

    /**
     * Creates an OpenGL ES OES external texture.
     * This texture target is required for SurfaceTexture / camera frame input.
     */
    private fun createOESTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val texId = textures[0]

        val genErr = GLES20.glGetError()
        if (genErr != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "glGenTextures error: $genErr")
            return 0
        }
        if (texId == 0) {
            Log.e(TAG, "glGenTextures returned 0")
            return 0
        }

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)

        // Linear filtering for smooth camera preview
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        val bindErr = GLES20.glGetError()
        if (bindErr != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "OES texture setup error: $bindErr")
        }

        Log.d(TAG, "Created OES texture id=$texId")
        return texId
    }

    /**
     * Releases all GPU resources.
     * Call when the GL surface is being destroyed.
     */
    fun release() {
        Log.d(TAG, "release() — cleaning up GPU resources")
        rendererReady = false
        releaseSurfaceTextureSafely()
        if (::shaderProgram.isInitialized) shaderProgram.release()
        if (::quad.isInitialized) quad.release()
        if (oesTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
            oesTextureId = 0
        }
    }

    private fun releaseSurfaceTextureSafely() {
        if (surfaceTextureReleased) return
        try {
            surfaceTexture?.release()
        } catch (e: Exception) {
            Log.w(TAG, "SurfaceTexture release exception (may already be released)", e)
        }
        surfaceTexture = null
        surfaceTextureReleased = true
    }

    private fun recoverSurfaceTexture() {
        Log.d(TAG, "Recovering SurfaceTexture")
        rendererReady = false
        releaseSurfaceTextureSafely()
        if (oesTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
        }
        oesTextureId = createOESTexture()
        if (oesTextureId == 0) {
            Log.e(TAG, "Recovery failed — OES texture creation failed")
            initFailed = true
            notifyPipelineFailed(RuntimeException("SurfaceTexture recovery failed"))
            return
        }
        surfaceTexture = SurfaceTexture(oesTextureId)
        surfaceTexture?.setOnFrameAvailableListener(this)
        surfaceTextureReleased = false
        if (surfaceWidth > 0 && surfaceHeight > 0) {
            surfaceTexture?.setDefaultBufferSize(surfaceWidth, surfaceHeight)
        }
        Matrix.setIdentityM(texTransformMatrix, 0)
        rendererReady = true
        frameAvailable = false
        surfaceTexture?.let { onSurfaceTextureAvailable?.invoke(it) }
    }

    private fun notifyPipelineFailed(e: Exception) {
        try {
            onGlPipelineFailed?.invoke(e)
        } catch (callbackErr: Exception) {
            Log.e(TAG, "onGlPipelineFailed callback threw", callbackErr)
        }
    }

    private fun checkGlError(label: String) {
        val err = GLES20.glGetError()
        if (err != GLES20.GL_NO_ERROR) {
            Log.e(TAG, "GL error at [$label]: $err")
        }
    }
}


