package com.srihari.vintix.rendering

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.Handler
import android.os.Looper
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * Simplified mobile-optimized GPU renderer for live CameraX preview.
 * ONE stable pipeline for all devices.
 */
class VintixRenderer : GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    companion object {
        private const val TAG = "VintixRenderer"

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
    }

    private lateinit var shaderProgram: ShaderProgram
    private lateinit var quad: TexturedQuad
    private var oesTextureId: Int = 0
    private var textureUniformLocation: Int = 0
    private var texMatrixUniformLocation: Int = 0
    private var profileUniforms: CameraProfileUniformHandles? = null
    private var noisePhase: Float = 0f
    private var rendererReady: Boolean = false

    @Volatile
    private var frameAvailable: Boolean = false

    @Volatile
    var initFailed: Boolean = false
        private set

    @Volatile
    var noisePhaseSnapshot: Float = 0f
        private set

    var cameraProfile: CameraProfile = CameraProfile.Default

    private var surfaceTexture: SurfaceTexture? = null

    @Volatile
    private var surfaceTextureReleased: Boolean = false

    private val texTransformMatrix = FloatArray(16)

    var onSurfaceTextureAvailable: ((SurfaceTexture) -> Unit)? = null
    var requestRender: (() -> Unit)? = null
    var onGlPipelineFailed: ((Exception) -> Unit)? = null

    override fun onFrameAvailable(surfaceTexture: SurfaceTexture?) {
        frameAvailable = true
        requestRender?.invoke()
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f)
            rendererReady = false
            initFailed = false
            releaseSurfaceTextureSafely()

            Matrix.setIdentityM(texTransformMatrix, 0)

            shaderProgram = ShaderProgram()
            val vs = shaderProgram.compile(GLES20.GL_VERTEX_SHADER, CAMERA_VERTEX_SHADER)
            val fs = shaderProgram.compile(GLES20.GL_FRAGMENT_SHADER, RetroPipelineShaders.fragmentShaderExternalOes())

            if (vs == 0 || fs == 0 || !shaderProgram.link(vs, fs)) {
                throw RuntimeException("Shader initialization failed")
            }

            textureUniformLocation = shaderProgram.getUniformLocation("uTexture")
            texMatrixUniformLocation = shaderProgram.getUniformLocation("uTexMatrix")
            profileUniforms = CameraProfileUniformHandles(shaderProgram)

            quad = TexturedQuad()
            oesTextureId = createOESTexture()
            surfaceTexture = SurfaceTexture(oesTextureId)
            
            // Use main looper to ensure callbacks fire reliably on all devices (especially OnePlus/Adreno)
            surfaceTexture?.setOnFrameAvailableListener(this, Handler(Looper.getMainLooper()))
            surfaceTextureReleased = false

            onSurfaceTextureAvailable?.invoke(surfaceTexture!!)

            rendererReady = true
            Log.d(TAG, "Renderer ready")
        } catch (e: Exception) {
            Log.e(TAG, "GL Initialization failed: ${e.message}")
            initFailed = true
            onGlPipelineFailed?.invoke(e)
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        if (!surfaceTextureReleased) {
            surfaceTexture?.setDefaultBufferSize(width, height)
        }
    }

    @Volatile
    var freezePreview: Boolean = false

    override fun onDrawFrame(gl: GL10?) {
        if (initFailed || !rendererReady) return

        try {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            val st = surfaceTexture
            if (st != null && !surfaceTextureReleased && frameAvailable) {
                if (!freezePreview) {
                    st.updateTexImage()
                    st.getTransformMatrix(texTransformMatrix)
                }
                frameAvailable = false
            }

            shaderProgram.use()
            GLES20.glUniformMatrix4fv(texMatrixUniformLocation, 1, false, texTransformMatrix, 0)

            noisePhase = (noisePhase + 0.019f).rem(1f)
            noisePhaseSnapshot = noisePhase
            profileUniforms?.upload(cameraProfile, noisePhase)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
            GLES20.glUniform1i(textureUniformLocation, 0)

            quad.draw(shaderProgram)
        } catch (e: Exception) {
            Log.e(TAG, "Error during onDrawFrame", e)
        }
    }

    private fun createOESTexture(): Int {
        val textures = IntArray(1)
        GLES20.glGenTextures(1, textures, 0)
        val texId = textures[0]
        if (texId == 0) return 0

        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        return texId
    }

    fun release() {
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
        } catch (e: Exception) {}
        surfaceTexture = null
        surfaceTextureReleased = true
    }
}
