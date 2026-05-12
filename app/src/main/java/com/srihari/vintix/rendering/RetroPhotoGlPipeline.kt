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
 * Offscreen OpenGL ES 2.0 path: runs the same retro fragment pipeline as preview
 * ([RetroPipelineShaders.fragmentShaderTexture2d]) into an FBO, then reads RGBA into a [Bitmap].
 *
 * Uses a dedicated EGL context (pbuffer surface) so work never touches the preview [GLSurfaceView].
 * Intended for still export; a future HQ path can add multisample / float FBOs behind this API.
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

    fun process(source: Bitmap, profile: CameraProfile, noisePhase: Float): Bitmap {
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
                Log.w(TAG, "Bitmap ${w0}x$h0 exceeds GL_MAX_TEXTURE_SIZE=$maxTex; scaling to ${nw}x$nh")
                Bitmap.createScaledBitmap(source, nw, nh, true)
            } else {
                source
            }

            val w = working.width
            val h = working.height

            val inputTex = genTexture()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, working, 0)
            if (working !== source) working.recycle()

            val colorTex = genTexture()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, colorTex)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_RGBA,
                w,
                h,
                0,
                GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE,
                null
            )

            val fbo = IntArray(1)
            GLES20.glGenFramebuffers(1, fbo, 0)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0])
            GLES20.glFramebufferTexture2D(
                GLES20.GL_FRAMEBUFFER,
                GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D,
                colorTex,
                0
            )
            val status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER)
            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                GLES20.glDeleteFramebuffers(1, fbo, 0)
                GLES20.glDeleteTextures(2, intArrayOf(inputTex, colorTex), 0)
                throw IllegalStateException("Framebuffer incomplete: $status")
            }

            GLES20.glViewport(0, 0, w, h)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

            val program = shaderProgram!!
            program.use()
            profileUniforms!!.upload(profile, noisePhase)

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, inputTex)
            if (uTextureLoc >= 0) GLES20.glUniform1i(uTextureLoc, 0)

            quad.draw(program)

            GLES20.glFinish()

            val rowBytes = w * 4
            val buf = ByteBuffer.allocateDirect(rowBytes * h).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            flipRgbaBufferVertically(buf, w, h)

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glDeleteFramebuffers(1, fbo, 0)
            GLES20.glDeleteTextures(2, intArrayOf(inputTex, colorTex), 0)

            val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            buf.rewind()
            val px = IntArray(w * h)
            var i = 0
            val lim = w * h
            while (i < lim) {
                val r = buf.get().toInt() and 0xFF
                val g = buf.get().toInt() and 0xFF
                val b = buf.get().toInt() and 0xFF
                val a = buf.get().toInt() and 0xFF
                px[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                i++
            }
            out.setPixels(px, 0, w, 0, 0, w, h)
            return out
    }

    fun release() {
        shaderProgram?.release()
        shaderProgram = null
        profileUniforms = null
        uTextureLoc = -1
        destroyEgl()
        initialized = false
    }

    private fun ensureEglAndProgram() {
        if (initialized) return

        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw IllegalStateException("Unable to get EGL14 display")
        }
        val version = IntArray(2)
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw IllegalStateException("Unable to initialize EGL14")
        }

        val attribList = intArrayOf(
            EGL14.EGL_COLOR_BUFFER_TYPE, EGL14.EGL_RGB_BUFFER,
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val numConfig = IntArray(1)
        if (!EGL14.eglChooseConfig(eglDisplay, attribList, 0, configs, 0, 1, numConfig, 0) || numConfig[0] == 0) {
            throw IllegalStateException("Unable to choose EGL config")
        }
        eglConfig = configs[0]

        val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        eglContext = EGL14.eglCreateContext(eglDisplay, eglConfig, EGL14.EGL_NO_CONTEXT, ctxAttribs, 0)
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw IllegalStateException("Unable to create EGL context")
        }

        val surfAttribs = intArrayOf(
            EGL14.EGL_WIDTH, 1,
            EGL14.EGL_HEIGHT, 1,
            EGL14.EGL_NONE
        )
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, eglConfig, surfAttribs, 0)
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw IllegalStateException("Unable to create EGL pbuffer surface")
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw IllegalStateException("Unable to eglMakeCurrent")
        }

        val sp = ShaderProgram()
        val vs = sp.compile(GLES20.GL_VERTEX_SHADER, RetroPipelineShaders.PHOTO_VERTEX_SHADER.trimIndent())
        val fs = sp.compile(GLES20.GL_FRAGMENT_SHADER, RetroPipelineShaders.fragmentShaderTexture2d())
        if (vs == 0 || fs == 0 || !sp.link(vs, fs)) {
            sp.release()
            throw IllegalStateException("Photo retro shader failed to compile or link")
        }
        shaderProgram = sp
        profileUniforms = CameraProfileUniformHandles(sp)
        uTextureLoc = sp.getUniformLocation("uTexture")

        initialized = true
    }

    private fun destroyEgl() {
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(
                eglDisplay,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_SURFACE,
                EGL14.EGL_NO_CONTEXT
            )
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface)
                eglSurface = EGL14.EGL_NO_SURFACE
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext)
                eglContext = EGL14.EGL_NO_CONTEXT
            }
            EGL14.eglTerminate(eglDisplay)
            eglDisplay = EGL14.EGL_NO_DISPLAY
        }
    }

    companion object {
        private const val TAG = "RetroPhotoGlPipeline"
        private val globalLock = Any()
        private var instance: RetroPhotoGlPipeline? = null

        /**
         * Processes [source] through the retro pipeline. Caller must not use OpenGL on the same
         * thread concurrently with other EGL contexts unless this is the only GLES work on the thread.
         */
        fun processBitmap(source: Bitmap, profile: CameraProfile, noisePhase: Float): Bitmap {
            synchronized(globalLock) {
                if (instance == null) {
                    instance = RetroPhotoGlPipeline()
                }
                return instance!!.process(source, profile, noisePhase)
            }
        }

        /** Releases global EGL + shader resources (e.g. on app shutdown or tests). */
        fun releaseShared() {
            synchronized(globalLock) {
                instance?.release()
                instance = null
            }
        }
    }
}

private fun genTexture(): Int {
    val t = IntArray(1)
    GLES20.glGenTextures(1, t, 0)
    return t[0]
}

private fun flipRgbaBufferVertically(buf: ByteBuffer, w: Int, h: Int) {
    val rowBytes = w * 4
    val tmp = ByteArray(rowBytes)
    val arr = ByteArray(buf.capacity())
    buf.rewind()
    buf.get(arr)
    for (y in 0 until h / 2) {
        val top = y * rowBytes
        val bot = (h - 1 - y) * rowBytes
        System.arraycopy(arr, top, tmp, 0, rowBytes)
        System.arraycopy(arr, bot, arr, top, rowBytes)
        System.arraycopy(tmp, 0, arr, bot, rowBytes)
    }
    buf.clear()
    buf.put(arr)
    buf.rewind()
}
