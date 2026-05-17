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
                
                // Optimized CCD color shifts - less "perfect" than modern sensors
                // Shifts towards cyan/magenta in certain exposures
                vec3 shadowShift = vec3(1.05, 0.9, 1.15); // Purple-ish shadows
                vec3 midShift = vec3(0.95, 1.05, 0.9); // Greenish-cyan mids
                vec3 highShift = vec3(1.1, 1.0, 0.85); // Warm yellow highlights
                
                vec3 warmTint = vec3(1.0 + uWarmth * 0.15, 1.0 + uWarmth * 0.08, 1.0 - uWarmth * 0.1);
                c *= warmTint;

                vec3 shifted = mix(c * shadowShift, c * midShift, smoothstep(-0.1, 0.5, y));
                shifted = mix(shifted, c * highShift, smoothstep(0.4, 0.98, y));
                
                // Highlight blooming/clipping - simulating sensor overflow
                // Real CCDs have very harsh highlight clipping
                float threshold = 0.9 - (0.15 * uHighlightHarshness);
                float bloom = smoothstep(threshold, 1.05, y);
                shifted = mix(shifted, vec3(1.0, 0.99, 0.98) * 1.1, bloom);
                
                // Dirty shadow crush - low dynamic range
                float crush = uShadowCrush * 0.3;
                shifted = max(shifted - crush, 0.0) / (1.0 - crush);
                
                // Add a slight magenta shift in low light
                float lowLight = 1.0 - smoothstep(0.0, 0.3, y);
                shifted = mix(shifted, shifted * vec3(1.1, 0.9, 1.1), lowLight * uShadowCrush);
                
                return shifted;
            }

            void main() {
                vec2 uv = vTexCoord;
                
                // Slight sensor blur simulation (low-quality lens)
                // Reduced texture taps for performance
                vec2 d = uv - vec2(0.5);
                float r = length(d) * 2.0;
                
                // Chromatic Aberration
                float ca = 0.0015 * uChromaticAberration * r;
                vec3 c;
                if (ca > 0.0 && r > 0.001) {
                    vec2 dir = d / r;
                    float rChan = texture2D(uTexture, uv + dir * ca).r;
                    vec4 ctr = texture2D(uTexture, uv);
                    float bChan = texture2D(uTexture, uv - dir * ca * 1.2).b;
                    c = vec3(rChan, ctr.g, bChan);
                } else {
                    c = texture2D(uTexture, uv).rgb;
                }
                
                c *= uExposureMultiplier;
                c = vnx_vintage_color_science(c);
                c = mix(vec3(vnx_luma(c)), c, clamp(uDesaturation, 0.0, 1.0));
                
                // CCD Noise (Luma-dependent)
                // Old sensors struggle in shadows
                vec2 fc = gl_FragCoord.xy;
                float y = vnx_luma(c);
                float noiseW = (1.5 + pow(1.0 - smoothstep(0.0, 0.6, y), 3.0) * 6.0) * uSensorNoise;
                float grain = vnx_hash(fc + uNoisePhase) - 0.5;
                
                // Blotchy chroma noise
                vec2 blotchUv = floor(fc * 0.08);
                float nR = vnx_hash(blotchUv + uNoisePhase) - 0.5;
                float nG = vnx_hash(blotchUv + uNoisePhase + 7.0) - 0.5;
                float nB = vnx_hash(blotchUv + uNoisePhase + 13.0) - 0.5;
                vec3 chroma = vec3(nR, nG, nB) * 0.12 * uChromaNoise;
                
                c += (vec3(grain * 0.03) + chroma) * noiseW;
                
                // JPEG macroblocking simulation (8x8 pixel blocks)
                if (uBlockArtifacts > 0.0) {
                    vec2 res = vec2(240.0, 180.0); // Even lower res for more artifacts
                    vec2 grid = floor(uv * res) / res;
                    vec3 blockC = texture2D(uTexture, grid).rgb;
                    c = mix(c, blockC, uBlockArtifacts * 0.4);
                }

                // Vignette
                c *= (1.0 - uVignetteIntensity * pow(r, 2.5));
                
                gl_FragColor = vec4(clamp(c, 0.0, 1.0), 1.0);
            }
        """.trimIndent()

    const val PHOTO_VERTEX_SHADER: String = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
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
