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

            // Simplified CCD luma
            float vnx_luma(vec3 c) {
                return dot(c, vec3(0.299, 0.587, 0.114));
            }

            float vnx_hash(vec2 p) {
                return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
            }

            vec3 vnx_vintage_color_science(vec3 c) {
                float y = vnx_luma(c);
                
                // Optimized CCD color shifts - less "perfect" than modern sensors
                vec3 shadowShift = vec3(1.1, 0.95, 1.2); // Blue/purple tint in dark areas
                vec3 midShift = vec3(1.0, 1.05, 0.9); // Greenish midtones
                vec3 highShift = vec3(1.1, 1.0, 0.9); // Warm highlights
                
                vec3 warmTint = vec3(1.0 + uWarmth * 0.12, 1.0 + uWarmth * 0.05, 1.0 - uWarmth * 0.08);
                c *= warmTint;

                vec3 shifted = mix(c * shadowShift, c * midShift, smoothstep(-0.1, 0.45, y));
                shifted = mix(shifted, c * highShift, smoothstep(0.4, 0.95, y));
                
                // Highlight blooming/clipping - simulating sensor overflow
                float threshold = 0.92 - (0.15 * uHighlightHarshness);
                float bloom = smoothstep(threshold, 1.1, y);
                shifted = mix(shifted, vec3(1.0, 0.98, 0.95) * 1.05, bloom);
                
                // Dirty shadow crush
                float crush = uShadowCrush * 0.22;
                shifted = max(shifted - crush, 0.0) / (1.0 - crush);
                shifted = mix(shifted, shifted * vec3(0.9, 0.85, 1.0), smoothstep(0.0, 0.2, y) * uShadowCrush);
                
                return shifted;
            }

            void main() {
                vec2 uv = vTexCoord;
                
                // Slight sensor blur simulation (box blur)
                vec2 blurUv = uv;
                if (uBlockArtifacts > 0.0) {
                   vec3 b0 = texture2D(uTexture, uv + vec2(0.0005)).rgb;
                   vec3 b1 = texture2D(uTexture, uv - vec2(0.0005)).rgb;
                   // We'll use this later
                }

                vec2 d = uv - vec2(0.5);
                float r = length(d) * 2.0;
                
                // Chromatic Aberration (One extra tap, only if intensity > 0)
                float ca = 0.0012 * uChromaticAberration * r;
                vec3 c;
                if (ca > 0.0 && r > 0.001) {
                    vec2 dir = d / r;
                    float rChan = texture2D(uTexture, uv + dir * ca).r;
                    vec4 ctr = texture2D(uTexture, uv);
                    float bChan = texture2D(uTexture, uv - dir * ca * 1.15).b;
                    c = vec3(rChan, ctr.g, bChan);
                } else {
                    c = texture2D(uTexture, uv).rgb;
                }
                
                c *= uExposureMultiplier;
                c = vnx_vintage_color_science(c);
                c = mix(vec3(vnx_luma(c)), c, clamp(uDesaturation, 0.0, 1.0));
                
                // CCD Noise (Luma-dependent)
                vec2 fc = gl_FragCoord.xy;
                float y = vnx_luma(c);
                float noiseW = (1.2 + pow(1.0 - smoothstep(0.0, 0.7, y), 2.5) * 4.0) * uSensorNoise;
                float grain = vnx_hash(fc + uNoisePhase) - 0.5;
                
                // Large blotchy chroma noise - very characteristic of old digital sensors
                vec2 blotchUv = floor(fc * 0.12);
                float nR = vnx_hash(blotchUv + uNoisePhase) - 0.5;
                float nG = vnx_hash(blotchUv + uNoisePhase + 11.0) - 0.5;
                float nB = vnx_hash(blotchUv + uNoisePhase + 23.0) - 0.5;
                vec3 chroma = vec3(nR, nG, nB) * 0.08 * uChromaNoise;
                
                c += (vec3(grain * 0.025) + chroma) * noiseW;
                
                // JPEG macroblocking simulation (8x8 pixel blocks)
                if (uBlockArtifacts > 0.0) {
                    // Force uv to 8x8 block grid
                    vec2 res = vec2(320.0, 240.0); // Simulate low-res sensor grid
                    vec2 grid = floor(uv * res) / res;
                    vec3 blockC = texture2D(uTexture, grid).rgb;
                    c = mix(c, blockC, uBlockArtifacts * 0.25);
                }

                // Vignette - softer and more organic
                c *= (1.0 - uVignetteIntensity * pow(r, 2.2));
                
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
