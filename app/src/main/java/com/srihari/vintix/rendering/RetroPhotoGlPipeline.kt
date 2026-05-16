package com.srihari.vintix.rendering

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLUtils
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Offscreen OpenGL ES 2.0 path for still photo export.
 * Shared ONE unified shader with the live preview.
 */
class RetroPhotoGlPipeline private constructor() {

    private var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var eglConfig: EGLConfig? = null

    private var shaderProgram: ShaderProgram? = null
    private var profileUniforms: CameraProfileUniformHandles? = null
    private var uTextureLoc: Int = -1
    private val quad = TexturedQuad()

    private var initialized = false
    private var inputTextureId: Int = 0
    private var outputTextureId: Int = 0
    private var frameBufferId: Int = 0
    private var outputWidth: Int = 0
    private var outputHeight: Int = 0
    private var readBuffer: ByteBuffer? = null
    private var pixelBuffer = IntArray(0)

    fun process(
        source: Bitmap, 
        profile: CameraProfile, 
        noisePhase: Float
    ): Bitmap {
        val w0 = source.width
        val h0 = source.height
        if (w0 <= 0 || h0 <= 0) throw IllegalArgumentException("Invalid bitmap size")

        ensureEglAndProgram()
        GLES20.glPixelStorei(GLES20.GL_PACK_ALIGNMENT, 1)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)

        val maxSize = IntArray(1)
        GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, maxSize, 0)
        val maxTex = maxSize[0].coerceAtLeast(1024)

        val working = if (w0 > maxTex || h0 > maxTex) {
            val scale = (maxTex.toFloat() / w0).coerceAtMost(maxTex.toFloat() / h0)
            val nw = (w0 * scale).toInt().coerceAtLeast(1)
            val nh = (h0 * scale).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(source, nw, nh, true)
        } else {
            source
        }

        try {
            val w = working.width
            val h = working.height

            ensureInputTexture()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTextureId)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, working, 0)

            ensureOutputTarget(w, h)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBufferId)

            GLES20.glViewport(0, 0, w, h)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            val program = shaderProgram!!
            program.use()
            profileUniforms!!.upload(profile, noisePhase)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTextureId)
            if (uTextureLoc >= 0) GLES20.glUniform1i(uTextureLoc, 0)

            quad.draw(program)
            GLES20.glFinish()

            val buf = ensureReadBuffer(w, h)
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)

            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            copyReadBufferToBitmap(buf, w, h, out)
            return out
        } finally {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0)
            if (working !== source && !working.isRecycled) working.recycle()
        }
    }

    fun release() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
        }
        releaseOutputTarget()
        releaseInputTexture()
        shaderProgram?.release()
        shaderProgram = null
        profileUniforms = null
        destroyEgl()
        initialized = false
    }

    private fun ensureEglAndProgram() {
        if (initialized) {
            if (EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) return
            forgetGlObjects()
            destroyEgl()
        }

        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        EGL14.eglInitialize(eglDisplay, IntArray(2), 0, IntArray(2), 1)

        val attribList = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT, EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, IntArray(1), 0)
        eglConfig = configs[0]

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)

        val sp = ShaderProgram()
        val vs = sp.compile(GLES20.GL_VERTEX_SHADER, RetroPipelineShaders.PHOTO_VERTEX_SHADER)
        val fs = sp.compile(GLES20.GL_FRAGMENT_SHADER, RetroPipelineShaders.fragmentShaderTexture2d())
        if (vs == 0 || fs == 0 || !sp.link(vs, fs)) throw IllegalStateException("Export shader failed")

        shaderProgram = sp
        profileUniforms = CameraProfileUniformHandles(sp)
        uTextureLoc = sp.getUniformLocation("uTexture")
        initialized = true
    }

    private fun ensureInputTexture() {
        if (inputTextureId != 0) return
        inputTextureId = genTexture()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTextureId)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
    }

    private fun ensureOutputTarget(width: Int, height: Int) {
        if (outputTextureId != 0 && outputWidth == width && outputHeight == height) return
        releaseOutputTarget()
        outputTextureId = genTexture()
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, outputTextureId)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        val fbo = IntArray(1)
        GLES20.glGenFramebuffers(1, fbo, 0)
        frameBufferId = fbo[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBufferId)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, outputTextureId, 0)
        outputWidth = width
        outputHeight = height
    }

    private fun ensureReadBuffer(width: Int, height: Int): ByteBuffer {
        val bytes = width * height * 4
        if (readBuffer == null || readBuffer!!.capacity() < bytes) {
            readBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())
        }
        return readBuffer!!.apply { clear(); limit(bytes) }
    }

    private fun copyReadBufferToBitmap(buffer: ByteBuffer, width: Int, height: Int, output: Bitmap) {
        if (pixelBuffer.size < width * height) pixelBuffer = IntArray(width * height)
        val rowBytes = width * 4
        var target = 0
        for (y in 0 until height) {
            var source = (height - 1 - y) * rowBytes
            for (x in 0 until width) {
                val r = buffer.get(source).toInt() and 0xFF
                val g = buffer.get(source + 1).toInt() and 0xFF
                val b = buffer.get(source + 2).toInt() and 0xFF
                val a = buffer.get(source + 3).toInt() and 0xFF
                pixelBuffer[target++] = (a shl 24) or (r shl 16) or (g shl 8) or b
                source += 4
            }
        }
        output.setPixels(pixelBuffer, 0, width, 0, 0, width, height)
    }

    private fun releaseInputTexture() {
        if (inputTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(inputTextureId), 0)
        inputTextureId = 0
    }

    private fun releaseOutputTarget() {
        if (frameBufferId != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(frameBufferId), 0)
        if (outputTextureId != 0) GLES20.glDeleteTextures(1, intArrayOf(outputTextureId), 0)
        frameBufferId = 0
        outputTextureId = 0
    }

    private fun forgetGlObjects() {
        shaderProgram = null
        profileUniforms = null
        inputTextureId = 0
        outputTextureId = 0
        frameBufferId = 0
    }

    private fun destroyEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(eglDisplay, eglSurface)
            if (eglContext != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(eglDisplay, eglContext)
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }

    companion object {
        private const val TAG = "RetroPhotoGlPipeline"
        private val globalLock = Any()
        private var instance: RetroPhotoGlPipeline? = null

        fun processBitmap(source: Bitmap, profile: CameraProfile, noisePhase: Float): Bitmap {
            synchronized(globalLock) {
                if (instance == null) instance = RetroPhotoGlPipeline()
                return instance!!.process(source, profile, noisePhase)
            }
        }

        fun releaseShared() {
            synchronized(globalLock) { instance?.release(); instance = null }
        }
    }
}

private fun genTexture(): Int {
    val t = IntArray(1)
    GLES20.glGenTextures(1, t, 0)
    return t[0]
}
