package com.srihari.vintix.rendering

/**
 * Unified, mobile-optimized GLSL for the Vintix retro color pipeline.
 * Designed for stability on Adreno/GLES2 devices with minimal texture reads.
 */
object RetroPipelineShaders {

    private val RETRO_FRAGMENT_CORE: String = """
            varying vec2 vTexCoord;
            uniform VKX_SAMPLER uTexture;
            uniform float uNoisePhase;
            uniform float uVignetteIntensity;
            uniform float uWarmth;
            uniform float uDesaturation;
            uniform float uSensorNoise;
            uniform float uChromaNoise;
            uniform float uChromaticAberration;
            uniform float uExposureMultiplier;
            uniform float uShadowCrush;
            uniform float uHighlightHarshness;
            uniform float uBlockArtifacts;

            // Simplified luma
            float vnx_luma(vec3 c) {
                return dot(c, vec3(0.299, 0.587, 0.114));
            }

            // Faster hash without sin() for Adreno performance
            float vnx_hash(vec2 p) {
                p = fract(p * vec2(123.34, 456.21));
                p += dot(p, p + 45.32);
                return fract(p.x * p.y);
            }

            vec3 vnx_vintage_color_science(vec3 c) {
                float y = vnx_luma(c);
                
                // Authentic CCD Color Science: Subtle shifts
                // Shadows: Slight purple/blue tint
                vec3 shadowShift = vec3(1.02, 0.98, 1.05); 
                // Mids: Neutral
                vec3 midShift = vec3(1.0, 1.0, 1.0); 
                // Highlights: Natural warmth
                vec3 highShift = vec3(1.05, 1.02, 0.98); 
                
                vec3 warmTint = vec3(1.0 + uWarmth * 0.05, 1.0 + uWarmth * 0.02, 1.0 - uWarmth * 0.03);
                c *= warmTint;

                vec3 shifted = mix(c * shadowShift, c * midShift, smoothstep(-0.1, 0.5, y));
                shifted = mix(shifted, c * highShift, smoothstep(0.4, 1.0, y));
                
                // Highlight blooming/halation - very subtle roll-off
                float threshold = 0.94 - (0.08 * uHighlightHarshness);
                float bloom = smoothstep(threshold, 1.05, y);
                shifted = mix(shifted, vec3(1.0, 0.99, 0.97) * 1.02, bloom * 0.5);
                
                // Subtle shadow crush - reduced aggressiveness
                float crush = uShadowCrush * 0.12;
                shifted = max(shifted - crush, 0.0) / (1.0 - crush);
                
                // Low-light tint
                float lowLight = 1.0 - smoothstep(0.0, 0.3, y);
                shifted = mix(shifted, shifted * vec3(1.03, 0.97, 1.03), lowLight * uShadowCrush * 0.3);
                
                return shifted;
            }

            void main() {
                vec2 uv = vTexCoord;
                
                // Subtle sensor blur (nostalgic softness) - very slight
                vec2 d = uv - vec2(0.5);
                float r = length(d) * 2.0;
                
                // Chromatic Aberration - reduced
                float ca = 0.0008 * uChromaticAberration * r;
                vec3 c;
                if (ca > 0.0 && r > 0.001) {
                    vec2 dir = d / r;
                    float rChan = texture2D(uTexture, uv + dir * ca).r;
                    vec4 ctr = texture2D(uTexture, uv);
                    float bChan = texture2D(uTexture, uv - dir * ca * 1.1).b;
                    c = vec3(rChan, ctr.g, bChan);
                } else {
                    c = texture2D(uTexture, uv).rgb;
                }
                
                c *= uExposureMultiplier;
                c = vnx_vintage_color_science(c);
                c = mix(vec3(vnx_luma(c)), c, clamp(uDesaturation, 0.0, 1.1));
                
                // CCD Noise (Luma-dependent) - reduced
                vec2 fc = gl_FragCoord.xy;
                float y = vnx_luma(c);
                float noiseW = (1.0 + pow(1.0 - smoothstep(0.0, 0.8, y), 2.0) * 3.0) * uSensorNoise;
                float grain = vnx_hash(fc + uNoisePhase) - 0.5;
                
                // Subtle blotchy chroma noise - reduced
                vec2 blotchUv = floor(fc * 0.2);
                float nR = vnx_hash(blotchUv + uNoisePhase) - 0.5;
                float nG = vnx_hash(blotchUv + uNoisePhase + 9.0) - 0.5;
                float nB = vnx_hash(blotchUv + uNoisePhase + 17.0) - 0.5;
                vec3 chroma = vec3(nR, nG, nB) * 0.06 * uChromaNoise;
                
                c += (vec3(grain * 0.02) + chroma) * noiseW;
                
                // JPEG macroblocking simulation (Subtle)
                if (uBlockArtifacts > 0.0) {
                    vec2 res = vec2(640.0, 480.0); 
                    vec2 grid = floor(uv * res) / res;
                    vec3 blockC = texture2D(uTexture, grid).rgb;
                    c = mix(c, blockC, uBlockArtifacts * 0.2);
                }

                // Vignette - softer
                c *= (1.0 - uVignetteIntensity * pow(r, 3.0) * 0.6);
                
                gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
            }
        """.trimIndent()

    const val CAMERA_VERTEX_SHADER = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        uniform mat4 uTexMatrix;
        uniform bool uMirror;
        varying vec2 vTexCoord;
        void main() {
            // Mirror horizontally by flipping X position for front camera
            vec4 pos = aPosition;
            if (uMirror) {
                pos.x = -pos.x;
            }
            gl_Position = pos;
            vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy;
        }
    """

    const val PHOTO_VERTEX_SHADER: String = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            // Flip texture coordinates vertically because Android Bitmaps are top-down,
            // but OpenGL expects bottom-up.
            vTexCoord = vec2(aTexCoord.x, 1.0 - aTexCoord.y);
        }
    """

    fun fragmentShaderExternalOes(): String {
        return "#extension GL_OES_EGL_image_external : require\n" +
               "precision mediump float;\n" +
               RETRO_FRAGMENT_CORE.replace("VKX_SAMPLER", "samplerExternalOES")
    }

    fun fragmentShaderTexture2d(): String {
        return "precision mediump float;\n" +
               RETRO_FRAGMENT_CORE.replace("VKX_SAMPLER", "sampler2D")
    }
}
