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
                
                // Authentic CCD Color Science: Shifts towards cyan/magenta in mid-tones
                // Shadows: Purple-ish tint (dirty blacks)
                vec3 shadowShift = vec3(1.1, 0.9, 1.15); 
                // Mids: Greenish-cyan (early digital look)
                vec3 midShift = vec3(0.9, 1.1, 0.95); 
                // Highlights: Warm yellow-orange (nostalgic warmth)
                vec3 highShift = vec3(1.15, 1.05, 0.8); 
                
                vec3 warmTint = vec3(1.0 + uWarmth * 0.18, 1.0 + uWarmth * 0.08, 1.0 - uWarmth * 0.12);
                c *= warmTint;

                vec3 shifted = mix(c * shadowShift, c * midShift, smoothstep(-0.15, 0.55, y));
                shifted = mix(shifted, c * highShift, smoothstep(0.45, 1.0, y));
                
                // Highlight blooming/halation - simulating sensor well overflow
                float threshold = 0.88 - (0.18 * uHighlightHarshness);
                float bloom = smoothstep(threshold, 1.08, y);
                shifted = mix(shifted, vec3(1.0, 0.99, 0.97) * 1.12, bloom);
                
                // Dirty shadow crush - simulating low dynamic range of cheap CCDs
                float crush = uShadowCrush * 0.35;
                shifted = max(shifted - crush, 0.0) / (1.0 - crush);
                
                // Low-light magenta cast
                float lowLight = 1.0 - smoothstep(0.0, 0.35, y);
                shifted = mix(shifted, shifted * vec3(1.15, 0.85, 1.15), lowLight * uShadowCrush);
                
                return shifted;
            }

            void main() {
                vec2 uv = vTexCoord;
                
                // Slight sensor blur simulation (low-quality lens/interpolation)
                vec2 d = uv - vec2(0.5);
                float r = length(d) * 2.0;
                
                // Chromatic Aberration (Lens imperfection)
                float ca = 0.0022 * uChromaticAberration * r;
                vec3 c;
                if (ca > 0.0 && r > 0.001) {
                    vec2 dir = d / r;
                    float rChan = texture2D(uTexture, uv + dir * ca).r;
                    vec4 ctr = texture2D(uTexture, uv);
                    float bChan = texture2D(uTexture, uv - dir * ca * 1.3).b;
                    c = vec3(rChan, ctr.g, bChan);
                } else {
                    c = texture2D(uTexture, uv).rgb;
                }
                
                c *= uExposureMultiplier;
                c = vnx_vintage_color_science(c);
                c = mix(vec3(vnx_luma(c)), c, clamp(uDesaturation, 0.0, 1.2));
                
                // CCD Noise (Luma-dependent)
                // Old sensors struggle significantly in shadows with blotchy chroma noise
                vec2 fc = gl_FragCoord.xy;
                float y = vnx_luma(c);
                float noiseW = (1.8 + pow(1.0 - smoothstep(0.0, 0.65, y), 3.5) * 8.0) * uSensorNoise;
                float grain = vnx_hash(fc + uNoisePhase) - 0.5;
                
                // Large blotchy chroma noise (very characteristic of early CMOS/CCD)
                vec2 blotchUv = floor(fc * 0.1);
                float nR = vnx_hash(blotchUv + uNoisePhase) - 0.5;
                float nG = vnx_hash(blotchUv + uNoisePhase + 9.0) - 0.5;
                float nB = vnx_hash(blotchUv + uNoisePhase + 17.0) - 0.5;
                vec3 chroma = vec3(nR, nG, nB) * 0.15 * uChromaNoise;
                
                c += (vec3(grain * 0.04) + chroma) * noiseW;
                
                // JPEG macroblocking simulation (8x8 pixel blocks)
                if (uBlockArtifacts > 0.0) {
                    // Simulate low-resolution grid sampling
                    vec2 res = vec2(320.0, 240.0); 
                    vec2 grid = floor(uv * res) / res;
                    vec3 blockC = texture2D(uTexture, grid).rgb;
                    
                    // Mix in blocking and slight edge artifacts
                    c = mix(c, blockC, uBlockArtifacts * 0.5);
                }

                // Vignette (Physical lens shading)
                c *= (1.0 - uVignetteIntensity * pow(r, 2.8));
                
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
