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
 * Core Vintix GPU renderer implementing [GLSurfaceView.Renderer].
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
class VintixRenderer : GLSurfaceView.Renderer {

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

    /** Transform matrix provided by SurfaceTexture for correct frame orientation. */
    private val texTransformMatrix = FloatArray(16)

    /**
     * Callback invoked on the GL thread when the [SurfaceTexture] is ready.
     * The UI layer uses this to bridge the SurfaceTexture to CameraX
     * without the rendering package importing any camera classes.
     */
    var onSurfaceTextureAvailable: ((SurfaceTexture) -> Unit)? = null

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        Log.d(TAG, "Surface created — initializing camera pipeline")

        GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)

        // Initialize identity matrix as default
        Matrix.setIdentityM(texTransformMatrix, 0)

        // Initialize shader program with camera (OES) shaders
        shaderProgram = ShaderProgram()
        val vertexShader = shaderProgram.compile(GLES20.GL_VERTEX_SHADER, CAMERA_VERTEX_SHADER)
        val fragmentShader = shaderProgram.compile(GLES20.GL_FRAGMENT_SHADER, cameraFragmentShader())

        if (vertexShader == 0 || fragmentShader == 0) {
            Log.e(TAG, "Shader compilation failed")
            return
        }

        if (!shaderProgram.link(vertexShader, fragmentShader)) {
            Log.e(TAG, "Shader program linking failed")
            return
        }

        textureUniformLocation = shaderProgram.getUniformLocation("uTexture")
        texMatrixUniformLocation = shaderProgram.getUniformLocation("uTexMatrix")
        profileUniforms = CameraProfileUniformHandles(shaderProgram)

        // Initialize geometry
        quad = TexturedQuad()

        // Create OES texture for camera frames
        oesTextureId = createOESTexture()

        // Create SurfaceTexture bound to the OES texture
        surfaceTexture = SurfaceTexture(oesTextureId)

        Log.d(TAG, "Camera pipeline initialized — program=${shaderProgram.programId}, oesTexture=$oesTextureId")

        // Notify the UI layer that the SurfaceTexture is ready
        surfaceTexture?.let { st ->
            onSurfaceTextureAvailable?.invoke(st)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        Log.d(TAG, "Surface changed: ${width}x${height}")
        GLES20.glViewport(0, 0, width, height)

        // Update the SurfaceTexture default buffer size to match the viewport
        surfaceTexture?.setDefaultBufferSize(width, height)
    }

    /**
     * Set to true to temporarily stop fetching new camera frames, freezing the viewfinder.
     */
    @Volatile
    var freezePreview: Boolean = false

    override fun onDrawFrame(gl: GL10?) {
        val startNs = System.nanoTime()
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // Pull the latest camera frame into the OES texture
        surfaceTexture?.let { st ->
            if (!freezePreview) {
                st.updateTexImage()
                st.getTransformMatrix(texTransformMatrix)
            }
        }

        // Bind shader program
        shaderProgram.use()

        // Pass the texture transform matrix
        GLES20.glUniformMatrix4fv(texMatrixUniformLocation, 1, false, texTransformMatrix, 0)

        noisePhase = (noisePhase + 0.019f).rem(1f)
        noisePhaseSnapshot = noisePhase
        profileUniforms?.upload(cameraProfile, noisePhase, false)

        // Bind OES texture to unit 0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(textureUniformLocation, 0)

        // Draw the fullscreen quad
        quad.draw(shaderProgram)
        
        PerformanceTelemetry.recordFrame(System.nanoTime() - startNs)
    }

    /**
     * Creates an OpenGL ES OES external texture.
     * This texture target is required for SurfaceTexture / camera frame input.
     */
    private fun createOESTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val texId = textures[0]

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)

        // Linear filtering for smooth camera preview
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)

        return texId
    }

    /**
     * Releases all GPU resources.
     * Call when the GL surface is being destroyed.
     */
    fun release() {
        surfaceTexture?.release()
        surfaceTexture = null
        if (::shaderProgram.isInitialized) shaderProgram.release()
        if (::quad.isInitialized) quad.release()
        if (oesTextureId != 0) {
            GLES20.glDeleteTextures(1, intArrayOf(oesTextureId), 0)
            oesTextureId = 0
        }
    }
}
