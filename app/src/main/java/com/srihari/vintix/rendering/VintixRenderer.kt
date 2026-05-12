package com.srihari.vintix.rendering

import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

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
         * Fragment shader for camera mode.
         * Uses samplerExternalOES for GL_TEXTURE_EXTERNAL_OES textures.
         * This is the insertion point for future retro effects:
         * CRT scanlines, film grain, color grading, vignette, etc.
         *
         * Pipeline (stack order is intentional for future passes / uniforms):
         * lens (CA + edge softness) → color grade → pseudo-halation → CCD clarity → micro-contrast
         * → CCD sensor noise (uNoisePhase) → radial vignette.
         *
         * Tuning: [CameraProfile] → fragment uniforms (uVignetteIntensity, uHalationStrength, …).
         */
        const val CAMERA_FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            varying vec2 vTexCoord;
            uniform samplerExternalOES uTexture;
            uniform float uNoisePhase;
            uniform float uVignetteIntensity;
            uniform float uHalationStrength;
            uniform float uWarmth;
            uniform float uDesaturation;
            uniform float uCcdClarity;
            uniform float uSensorNoise;
            uniform float uChromaticAberration;
            uniform float uLensSoftness;

            float vnx_luma709(vec3 c) {
                return dot(c, vec3(0.2126, 0.7152, 0.0722));
            }

            void vnx_uv_radial(vec2 uv, out float r, out vec2 dirN) {
                vec2 d = uv - vec2(0.5);
                float len = length(d);
                r = len * 2.0;
                dirN = (len > 1e-4) ? (d / len) : vec2(0.0);
            }

            vec4 vnx_lens_edge_capture(samplerExternalOES tex, vec2 uv) {
                float r;
                vec2 dir;
                vnx_uv_radial(uv, r, dir);
                float edge = smoothstep(0.24, 0.92, r);
                float ca = 0.00072 * edge * uChromaticAberration;
                vec2 uvR = uv + dir * ca;
                vec2 uvB = uv - dir * ca * 0.9;
                vec4 ctr = texture2D(tex, uv);
                float rChan = texture2D(tex, uvR).r;
                float bChan = texture2D(tex, uvB).b;
                vec3 sharp = vec3(rChan, ctr.g, bChan);
                float softW = smoothstep(0.36, 0.94, r);
                vec2 softUv = uv - dir * 0.00155 * softW * uLensSoftness;
                vec3 softRgb = texture2D(tex, softUv).rgb;
                vec3 rgb = mix(sharp, softRgb, softW * 0.38 * uLensSoftness);
                return vec4(rgb, ctr.a);
            }

            vec3 vnx_radial_vignette(vec3 c, vec2 uv) {
                float r;
                vec2 dir;
                vnx_uv_radial(uv, r, dir);
                float vig = 1.0 - uVignetteIntensity * smoothstep(0.18, 0.95, r);
                return c * vig;
            }

            vec3 vnx_retro_highlight_rolloff(vec3 c) {
                float y = vnx_luma709(c);
                float yc = y / (1.0 + 0.26 * y);
                float s = (y > 1e-4) ? (yc / y) : 1.0;
                return c * s;
            }

            vec3 vnx_lift_blacks(vec3 c) {
                float y = vnx_luma709(c);
                float w = 1.0 - smoothstep(0.0, 0.4, y);
                return c + 0.013 * w;
            }

            vec3 vnx_warm_highlight_rolloff(vec3 c) {
                float y = vnx_luma709(c);
                float t = smoothstep(0.34, 0.86, y);
                vec3 warm = vec3(1.016, 1.003, 0.994);
                return mix(c, c * warm, t * 0.18 * clamp(uWarmth, 0.0, 2.0));
            }

            vec3 vnx_vintage_color_shift(vec3 c) {
                mat3 m = mat3(
                    1.015, 0.008, 0.0,
                    0.0,   0.997, 0.004,
                    0.0,   0.006, 0.982
                );
                return m * c;
            }

            vec3 vnx_mild_desaturate(vec3 c, float sat) {
                float y = vnx_luma709(c);
                return mix(vec3(y), c, sat);
            }

            vec3 vnx_halation_warm_tint(vec3 c) {
                vec3 tw = vec3(1.042, 1.005, 0.931);
                return c * mix(vec3(1.0), tw, clamp(uWarmth, 0.0, 1.5));
            }

            vec3 vnx_halation_neighbor_contrib(samplerExternalOES tex, vec2 uv, vec2 off) {
                vec3 n = texture2D(tex, uv + off).rgb;
                float y = vnx_luma709(n);
                float w = smoothstep(0.5, 0.5 + 0.26, y);
                w *= w;
                return vnx_halation_warm_tint(n) * w;
            }

            vec3 vnx_pseudo_halation(samplerExternalOES tex, vec2 uv) {
                vec2 du = vec2(0.00158, 0.0);
                vec2 dv = vec2(0.0, 0.00192);
                vec3 acc = vnx_halation_neighbor_contrib(tex, uv, du);
                acc += vnx_halation_neighbor_contrib(tex, uv, -du);
                acc += vnx_halation_neighbor_contrib(tex, uv, dv);
                acc += vnx_halation_neighbor_contrib(tex, uv, -dv);
                return acc * (0.064 / 4.0) * uHalationStrength;
            }

            vec3 vnx_ccd_sensor_clarity(samplerExternalOES tex, vec2 uv, vec3 graded) {
                vec2 du = vec2(0.0009, 0.0);
                vec2 dv = vec2(0.0, 0.00093);
                vec3 avg = texture2D(tex, uv + du).rgb + texture2D(tex, uv - du).rgb
                         + texture2D(tex, uv + dv).rgb + texture2D(tex, uv - dv).rgb;
                avg *= 0.25;
                vec3 rawC = texture2D(tex, uv).rgb;
                float d = vnx_luma709(rawC - avg);
                d = clamp(d, -0.042, 0.042);
                float edge = smoothstep(0.006, 0.11, abs(d));
                float gain = 0.31 * (0.58 + 0.42 * edge) * uCcdClarity;
                float yg = max(vnx_luma709(graded), 0.02);
                vec3 ratio = graded / yg;
                return graded + gain * d * ratio;
            }

            vec3 vnx_ccd_micro_contrast(vec3 c) {
                float y = vnx_luma709(c);
                vec3 g = vec3(y);
                float bell = smoothstep(0.1, 0.36, y) * (1.0 - smoothstep(0.56, 0.93, y));
                float punch = 1.0 + 0.032 * bell;
                return g + (c - g) * punch;
            }

            float vnx_hash13(vec3 p) {
                p = fract(p * 0.1031);
                p += dot(p, p.zxy + 33.33);
                return fract((p.x + p.y) * p.z);
            }

            vec3 vnx_ccd_sensor_noise(vec3 c, float phase) {
                vec2 fc = gl_FragCoord.xy * 0.68;
                vec3 h0 = vec3(fc, phase * 311.7);
                float nL = vnx_hash13(h0) - 0.5;
                float nR = vnx_hash13(h0 + vec3(19.2, 2.7, 1.1)) - 0.5;
                float nB = vnx_hash13(h0 + vec3(3.3, 61.0, 2.4)) - 0.5;
                float y = vnx_luma709(c);
                float hiClean = smoothstep(0.52, 0.87, y);
                float w = (1.0 - hiClean);
                w *= mix(1.15, 0.94, smoothstep(0.0, 0.48, y));
                float ampL = 0.0102 * uSensorNoise;
                float ampC = 0.0049 * uSensorNoise;
                vec3 o = c;
                o += nL * ampL * w;
                o.r += nR * ampC * w;
                o.b += nB * ampC * w;
                o.g += (nL * 0.38 + nR * 0.28 + nB * 0.34) * ampC * w * 0.42;
                return o;
            }

            vec4 vnx_retro_color_pipeline(samplerExternalOES tex, vec2 uv) {
                vec4 lc = vnx_lens_edge_capture(tex, uv);
                vec3 c = lc.rgb;
                c = vnx_retro_highlight_rolloff(c);
                c = vnx_lift_blacks(c);
                c = vnx_warm_highlight_rolloff(c);
                c = vnx_vintage_color_shift(c);
                c = vnx_mild_desaturate(c, clamp(uDesaturation, 0.0, 1.0));
                c = clamp(c, 0.0, 1.0);
                vec3 halo = vnx_pseudo_halation(tex, uv);
                float yb = vnx_luma709(c);
                float damp = 1.0 - 0.38 * smoothstep(0.62, 0.96, yb);
                c = clamp(c + halo * damp, 0.0, 1.0);
                c = clamp(vnx_ccd_sensor_clarity(tex, uv, c), 0.0, 1.0);
                c = clamp(vnx_ccd_micro_contrast(c), 0.0, 1.0);
                c = clamp(vnx_ccd_sensor_noise(c, uNoisePhase), 0.0, 1.0);
                c = vnx_radial_vignette(c, uv);
                return vec4(clamp(c, 0.0, 1.0), lc.a);
            }

            void main() {
                gl_FragColor = vnx_retro_color_pipeline(uTexture, vTexCoord);
            }
        """
    }

    private lateinit var shaderProgram: ShaderProgram
    private lateinit var quad: TexturedQuad
    private var oesTextureId: Int = 0
    private var textureUniformLocation: Int = 0
    private var texMatrixUniformLocation: Int = 0
    private var noisePhaseUniformLocation: Int = -1
    private var uVignetteIntensityLoc: Int = -1
    private var uHalationStrengthLoc: Int = -1
    private var uWarmthLoc: Int = -1
    private var uDesaturationLoc: Int = -1
    private var uCcdClarityLoc: Int = -1
    private var uSensorNoiseLoc: Int = -1
    private var uChromaticAberrationLoc: Int = -1
    private var uLensSoftnessLoc: Int = -1
    private var noisePhase: Float = 0f

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
        val fragmentShader = shaderProgram.compile(GLES20.GL_FRAGMENT_SHADER, CAMERA_FRAGMENT_SHADER)

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
        noisePhaseUniformLocation = shaderProgram.getUniformLocation("uNoisePhase")
        uVignetteIntensityLoc = shaderProgram.getUniformLocation("uVignetteIntensity")
        uHalationStrengthLoc = shaderProgram.getUniformLocation("uHalationStrength")
        uWarmthLoc = shaderProgram.getUniformLocation("uWarmth")
        uDesaturationLoc = shaderProgram.getUniformLocation("uDesaturation")
        uCcdClarityLoc = shaderProgram.getUniformLocation("uCcdClarity")
        uSensorNoiseLoc = shaderProgram.getUniformLocation("uSensorNoise")
        uChromaticAberrationLoc = shaderProgram.getUniformLocation("uChromaticAberration")
        uLensSoftnessLoc = shaderProgram.getUniformLocation("uLensSoftness")

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

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        // Pull the latest camera frame into the OES texture
        surfaceTexture?.let { st ->
            st.updateTexImage()
            st.getTransformMatrix(texTransformMatrix)
        }

        // Bind shader program
        shaderProgram.use()

        // Pass the texture transform matrix
        GLES20.glUniformMatrix4fv(texMatrixUniformLocation, 1, false, texTransformMatrix, 0)

        noisePhase = (noisePhase + 0.019f).rem(1f)
        if (noisePhaseUniformLocation >= 0) {
            GLES20.glUniform1f(noisePhaseUniformLocation, noisePhase)
        }

        uploadCameraProfileUniforms(cameraProfile)

        // Bind OES texture to unit 0
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTextureId)
        GLES20.glUniform1i(textureUniformLocation, 0)

        // Draw the fullscreen quad
        quad.draw(shaderProgram)
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

    private fun uploadCameraProfileUniforms(profile: CameraProfile) {
        if (uVignetteIntensityLoc >= 0) GLES20.glUniform1f(uVignetteIntensityLoc, profile.vignetteIntensity)
        if (uHalationStrengthLoc >= 0) GLES20.glUniform1f(uHalationStrengthLoc, profile.halationStrength)
        if (uWarmthLoc >= 0) GLES20.glUniform1f(uWarmthLoc, profile.warmth)
        if (uDesaturationLoc >= 0) GLES20.glUniform1f(uDesaturationLoc, profile.desaturation)
        if (uCcdClarityLoc >= 0) GLES20.glUniform1f(uCcdClarityLoc, profile.ccdClarity)
        if (uSensorNoiseLoc >= 0) GLES20.glUniform1f(uSensorNoiseLoc, profile.sensorNoise)
        if (uChromaticAberrationLoc >= 0) GLES20.glUniform1f(uChromaticAberrationLoc, profile.chromaticAberration)
        if (uLensSoftnessLoc >= 0) GLES20.glUniform1f(uLensSoftnessLoc, profile.lensSoftness)
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
