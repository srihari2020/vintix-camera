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
                
                // Optimized CCD color shifts
                vec3 shadowShift = vec3(1.02, 0.98, 1.04);
                vec3 midShift = vec3(1.0, 1.02, 0.96);
                vec3 highShift = vec3(0.96, 1.0, 1.04);
                
                vec3 warmTint = vec3(1.0 + uWarmth * 0.08, 1.0, 1.0 - uWarmth * 0.04);
                c *= warmTint;

                vec3 shifted = mix(c * shadowShift, c * midShift, smoothstep(0.0, 0.4, y));
                shifted = mix(shifted, c * highShift, smoothstep(0.5, 1.0, y));
                
                // Highlight clipping and shadow crush
                float threshold = 1.0 - (0.25 * uHighlightHarshness);
                shifted = mix(shifted, vec3(1.0, 1.0, 0.98), smoothstep(threshold, 1.02, y));
                
                float crush = uShadowCrush * 0.15;
                shifted = max(shifted - crush, 0.0) / (1.0 - crush);
                
                return shifted;
            }

            void main() {
                vec2 uv = vTexCoord;
                vec2 d = uv - vec2(0.5);
                float r = length(d) * 2.0;
                
                // Chromatic Aberration (One extra tap, only if intensity > 0)
                float ca = 0.001 * uChromaticAberration * r;
                vec3 c;
                if (ca > 0.0) {
                    float rChan = texture2D(uTexture, uv + (d/r) * ca).r;
                    vec4 ctr = texture2D(uTexture, uv);
                    float bChan = texture2D(uTexture, uv - (d/r) * ca * 1.1).b;
                    c = vec3(rChan, ctr.g, bChan);
                } else {
                    c = texture2D(uTexture, uv).rgb;
                }
                
                c *= uExposureMultiplier;
                c = vnx_vintage_color_science(c);
                c = mix(vec3(vnx_luma(c)), c, clamp(uDesaturation, 0.0, 1.0));
                
                // CCD Noise
                vec2 fc = gl_FragCoord.xy;
                float y = vnx_luma(c);
                float noiseW = (1.0 + pow(1.0 - smoothstep(0.0, 0.6, y), 2.0) * 3.0) * uSensorNoise;
                float grain = vnx_hash(fc + uNoisePhase) - 0.5;
                
                // Large blotchy chroma noise
                vec2 blotchUv = floor(fc * 0.1);
                float nR = vnx_hash(blotchUv + uNoisePhase) - 0.5;
                float nG = vnx_hash(blotchUv + uNoisePhase + 7.0) - 0.5;
                float nB = vnx_hash(blotchUv + uNoisePhase + 19.0) - 0.5;
                vec3 chroma = vec3(nR, nG, nB) * 0.06 * uChromaNoise;
                
                c += (vec3(grain * 0.02) + chroma) * noiseW;
                
                // JPEG softness simulation via subtle 8x8 block blur
                if (uBlockArtifacts > 0.0) {
                    vec2 blockUv = floor(uv * 120.0) / 120.0;
                    c = mix(c, texture2D(uTexture, blockUv).rgb, uBlockArtifacts * 0.2);
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
