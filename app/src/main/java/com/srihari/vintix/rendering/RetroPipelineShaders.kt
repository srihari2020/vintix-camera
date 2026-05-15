package com.srihari.vintix.rendering

/**
 * Shared GLSL for the Vintix retro color pipeline.
 * [fragmentShaderExternalOes] targets [android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES];
 * [fragmentShaderTexture2d] targets [android.opengl.GLES20.GL_TEXTURE_2D] for still export / FBO.
 */
object RetroPipelineShaders {

    private val RETRO_FRAGMENT_CORE: String = """
            varying vec2 vTexCoord;
            uniform VKX_SAMPLER uTexture;
            uniform float uNoisePhase;
            uniform float uVignetteIntensity;
            uniform float uHalationStrength;
            uniform float uWarmth;
            uniform float uDesaturation;
            uniform float uCcdClarity;
            uniform float uSensorNoise;
            uniform float uChromaticAberration;
            uniform float uLensSoftness;
            uniform float uInternalResolution;
            uniform float uBlockArtifacts;
            uniform float uShadowCrush;
            uniform float uHighlightHarshness;
            uniform vec2 uResolution;

            float vnx_luma709(vec3 c) {
                return dot(c, vec3(0.2126, 0.7152, 0.0722));
            }

            void vnx_uv_radial(vec2 uv, out float r, out vec2 dirN) {
                vec2 d = uv - vec2(0.5);
                float len = length(d);
                r = len * 2.0;
                dirN = (len > 1e-4) ? (d / len) : vec2(0.0);
            }

            vec4 vnx_lens_edge_capture(VKX_SAMPLER tex, vec2 uv, float r, vec2 dir, out vec3 centerRgb) {
                float edge = smoothstep(0.15, 0.95, r);
                float ca = 0.0009 * edge * uChromaticAberration;
                
                // CCD artifacts: Slight vertical smear or misalignment
                vec2 uvR = uv + dir * ca;
                vec2 uvB = uv - dir * ca * 1.1;
                
                vec4 ctr = texture2D(tex, uv);
                centerRgb = ctr.rgb;
                
                float rChan = texture2D(tex, uvR).r;
                float bChan = texture2D(tex, uvB).b;
                vec3 sharp = vec3(rChan, ctr.g, bChan);
                
                // Early digital lens softness: Not just blur, but "cheap" feeling smearing
                float softW = smoothstep(0.2, 0.98, r);
                vec2 softUv = uv - dir * 0.0022 * softW * uLensSoftness;
                vec3 softRgb = texture2D(tex, softUv).rgb;
                
                // Uneven sharpness: Harsher mix at the very edges
                float lensMix = mix(0.15, 0.55, softW) * uLensSoftness;
                vec3 rgb = mix(sharp, softRgb, lensMix);
                
                return vec4(rgb, ctr.a);
            }

            vec3 vnx_radial_vignette(vec3 c, float r) {
                float vig = 1.0 - uVignetteIntensity * pow(r, 2.5);
                return c * vig;
            }

            // Harsh digital clipping instead of smooth rolloff
            vec3 vnx_retro_highlight_rolloff(vec3 c, float y) {
                float threshold = 1.0 - (0.2 * uHighlightHarshness);
                vec3 clipped = mix(c, vec3(1.0), smoothstep(threshold, 1.05, y));
                return min(clipped, vec3(1.0));
            }

            // Crushed shadow detail
            vec3 vnx_lift_blacks(vec3 c, float y) {
                float crush = uShadowCrush * 0.12;
                return max(c - crush, 0.0) / (1.0 - crush);
            }

            // Harsher warmth shift
            vec3 vnx_warm_highlight_rolloff(vec3 c, float y) {
                float t = smoothstep(0.6, 1.0, y);
                vec3 cyanTint = vec3(0.92, 1.0, 1.0);
                return mix(c, c * cyanTint, t * 0.25 * uHighlightHarshness);
            }

            // CCD Color Science: Midtones yellow-green, shadows magenta, highlights cyan
            vec3 vnx_vintage_color_shift(vec3 c) {
                float y = vnx_luma709(c);
                
                // Shadows: push magenta
                vec3 shadowShift = vec3(1.05, 0.95, 1.05);
                // Midtones: push yellow-green
                vec3 midShift = vec3(1.02, 1.04, 0.94);
                // Highlights: push cyan
                vec3 highShift = vec3(0.92, 1.02, 1.05);
                
                vec3 shifted = mix(c * shadowShift, c * midShift, smoothstep(0.0, 0.45, y));
                shifted = mix(shifted, c * highShift, smoothstep(0.55, 1.0, y));
                
                // Blue channel precision loss (CCD character)
                shifted.b = floor(shifted.b * 32.0) / 32.0;
                
                return shifted;
            }

            vec3 vnx_mild_desaturate(vec3 c, float y, float sat) {
                return mix(vec3(y), c, sat);
            }

            vec3 vnx_halation_warm_tint(vec3 c) {
                vec3 tw = vec3(1.05, 1.02, 0.95);
                return c * mix(vec3(1.0), tw, clamp(uWarmth, 0.0, 1.5));
            }

            vec3 vnx_halation_neighbor_contrib(VKX_SAMPLER tex, vec2 uv, vec2 off) {
                vec3 n = texture2D(tex, uv + off).rgb;
                float y = vnx_luma709(n);
                float w = smoothstep(0.6, 0.9, y);
                return vnx_halation_warm_tint(n) * w * w;
            }

            vec3 vnx_pseudo_halation(VKX_SAMPLER tex, vec2 uv) {
                if (uHalationStrength <= 0.001) return vec3(0.0);
                vec2 du = vec2(0.002, 0.0);
                vec2 dv = vec2(0.0, 0.002);
                vec3 acc = vnx_halation_neighbor_contrib(tex, uv, du);
                acc += vnx_halation_neighbor_contrib(tex, uv, -du);
                acc += vnx_halation_neighbor_contrib(tex, uv, dv);
                acc += vnx_halation_neighbor_contrib(tex, uv, -dv);
                return acc * 0.02 * uHalationStrength;
            }

            vec3 vnx_ccd_sensor_clarity(VKX_SAMPLER tex, vec2 uv, vec3 rawCenter, vec3 graded) {
                if (uCcdClarity <= 0.001) return graded;
                vec2 du = vec2(0.001, 0.0);
                vec2 dv = vec2(0.0, 0.001);
                vec3 avg = texture2D(tex, uv + du).rgb + texture2D(tex, uv - du).rgb
                         + texture2D(tex, uv + dv).rgb + texture2D(tex, uv - dv).rgb;
                avg *= 0.25;
                float d = vnx_luma709(rawCenter - avg);
                d = clamp(d, -0.06, 0.06);
                float gain = 0.45 * uCcdClarity;
                return graded + d * gain;
            }

            vec3 vnx_ccd_micro_contrast(vec3 c) {
                float y = vnx_luma709(c);
                float bell = smoothstep(0.05, 0.3, y) * (1.0 - smoothstep(0.4, 0.9, y));
                return c * (1.0 + 0.08 * bell);
            }

            float vnx_hash13(vec3 p) {
                p = fract(p * 0.1031);
                p += dot(p, p.zxy + 33.33);
                return fract((p.x + p.y) * p.z);
            }

            // Rewritten CCD sensor noise: larger chroma blotches and stronger shadow noise
            vec3 vnx_ccd_sensor_noise(vec3 c, float phase) {
                if (uSensorNoise <= 0.001) return c;
                
                vec2 fc = gl_FragCoord.xy;
                
                // Fine grain
                float grain = vnx_hash13(vec3(fc * 0.8, phase * 100.0)) - 0.5;
                
                // Larger chroma blotches (low-freq noise)
                vec2 blotchUv = fc * 0.12;
                float nR = vnx_hash13(vec3(blotchUv, phase * 123.4)) - 0.5;
                float nG = vnx_hash13(vec3(blotchUv + vec2(17.0, 4.0), phase * 123.4)) - 0.5;
                float nB = vnx_hash13(vec3(blotchUv + vec2(2.0, 31.0), phase * 123.4)) - 0.5;
                
                float y = vnx_luma709(c);
                // Stronger noise in shadows
                float shadowBoost = 1.0 + (1.0 - smoothstep(0.0, 0.5, y)) * 2.0;
                float hiClean = smoothstep(0.6, 0.9, y);
                float w = (1.0 - hiClean) * shadowBoost * uSensorNoise;
                
                vec3 noise = vec3(grain * 0.015);
                noise += vec3(nR, nG, nB) * 0.035;
                
                return c + noise * w;
            }

            // Procedural 8x8 block artifact simulation
             vec3 vnx_jpeg_blocks(vec3 c, vec2 uv) {
                 if (uBlockArtifacts <= 0.001) return c;
                 
                 vec2 res = uResolution;
                 if (uInternalResolution > 0.0) {
                     // If internal resolution is set, we use it as the vertical height 
                     // and calculate width based on actual aspect ratio
                     float aspect = uResolution.x / uResolution.y;
                     res = vec2(uInternalResolution * aspect, uInternalResolution);
                 }
                 
                 vec2 blockUv = floor(uv * res / 8.0) * 8.0 / res;
                vec2 inBlock = fract(uv * res / 8.0);
                
                // Simulate DCT precision loss by quantizing the block color
                vec3 blockColor = texture2D(uTexture, blockUv).rgb;
                float y = vnx_luma709(c);
                
                // Edge darkening/lightening at 8x8 boundaries
                float edge = step(0.95, inBlock.x) + step(0.95, inBlock.y);
                float blockMod = 1.0 + (edge * 0.02 * uBlockArtifacts);
                
                // Mosquito noise simulation
                float mosquito = (vnx_hash13(vec3(uv * 500.0, 0.0)) - 0.5) * 0.04 * uBlockArtifacts;
                
                return mix(c, c * blockMod + mosquito, uBlockArtifacts * 0.5);
            }

            uniform float uFlashCenterBoost;
            uniform float uFlashWarmBloom;
            uniform float uFlashHighlightClipping;
            uniform float uFlashContrastFlattening;

            uniform float uLeakIntensity;
            uniform vec2 uLeakOrigin;

            uniform float uExposureMultiplier;

            vec3 vnx_apply_light_leak(vec3 c, vec2 uv) {
                if (uLeakIntensity <= 0.0) return c;
                
                float dist = length(uv - uLeakOrigin);
                float angle = atan(uv.y - uLeakOrigin.y, uv.x - uLeakOrigin.x);
                float irregularity = sin(angle * 4.0) * 0.1 + sin(angle * 9.0) * 0.05;
                
                float leakMask = smoothstep(0.9 + irregularity, 0.0, dist) * uLeakIntensity;
                
                vec3 coreColor = vec3(1.0, 0.9, 0.6);
                vec3 midColor = vec3(1.0, 0.4, 0.0);
                vec3 edgeColor = vec3(0.8, 0.0, 0.0);
                
                vec3 leakColor = mix(edgeColor, midColor, smoothstep(0.2, 0.6, leakMask));
                leakColor = mix(leakColor, coreColor, smoothstep(0.6, 1.0, leakMask));
                
                c = mix(c, c + vec3(0.15, 0.05, 0.0), leakMask * 0.4);
                return c + leakColor * leakMask;
            }

            vec4 vnx_retro_color_pipeline(VKX_SAMPLER tex, vec2 uv) {
                // UV Quantization (True Low-Res CCD Feel)
                vec2 resUv = uv;
                if (uInternalResolution > 0.0) {
                    float aspect = uResolution.x / uResolution.y;
                    vec2 res = vec2(uInternalResolution * aspect, uInternalResolution);
                    resUv = floor(uv * res) / res;
                }

                float r;
                vec2 dir;
                vnx_uv_radial(resUv, r, dir);
                vec3 rawCenter;
                vec4 lc = vnx_lens_edge_capture(tex, resUv, r, dir, rawCenter);
                vec3 c = lc.rgb;
                
                c *= uExposureMultiplier;
                
                // FLASH PASS: Harsher center weight, washed out skin tones
                if (uFlashCenterBoost > 0.0) {
                    // Center-weight the exposure harshly
                    float flashFalloff = pow(max(0.0, 1.0 - r * 0.85), 3.0);
                    
                    c += c * flashFalloff * uFlashCenterBoost * 1.5;
                    
                    float y = vnx_luma709(c);
                    // Wash out colors (skin tones)
                    c = mix(c, vec3(y * 1.1), flashFalloff * uFlashContrastFlattening * 1.2);
                    
                    vec3 bloomTint = vec3(1.1, 1.05, 0.95);
                    c += c * bloomTint * pow(y, 3.0) * flashFalloff * uFlashWarmBloom;
                    
                    // Clip the near-field strongly
                    float clipThresh = 1.0 - (uFlashHighlightClipping * 0.45);
                    c = min(c, vec3(clipThresh)) / clipThresh;
                }
                
                float y = vnx_luma709(c);
                c = vnx_retro_highlight_rolloff(c, y);
                y = vnx_luma709(c);
                c = vnx_lift_blacks(c, y);
                y = vnx_luma709(c);
                c = vnx_warm_highlight_rolloff(c, y);
                c = vnx_vintage_color_shift(c);
                y = vnx_luma709(c);
                c = vnx_mild_desaturate(c, y, clamp(uDesaturation, 0.0, 1.0));
                
                vec3 halo = vnx_pseudo_halation(tex, resUv);
                c = clamp(c + halo, 0.0, 1.0);
                
                c = vnx_apply_light_leak(c, resUv);
                
                c = clamp(vnx_ccd_sensor_clarity(tex, resUv, rawCenter, c), 0.0, 1.0);
                c = clamp(vnx_ccd_micro_contrast(c), 0.0, 1.0);
                c = clamp(vnx_ccd_sensor_noise(c, uNoisePhase), 0.0, 1.0);
                
                // JPEG Block Artifacts
                c = vnx_jpeg_blocks(c, resUv);
                
                c = vnx_radial_vignette(c, r);
                return vec4(clamp(c, 0.0, 1.0), lc.a);
            }

            void main() {
                gl_FragColor = vnx_retro_color_pipeline(uTexture, vTexCoord);
            }
        """.trimIndent()

    /** Vertex shader for 2D texture / FBO export (identity texture coordinates). */
    const val PHOTO_VERTEX_SHADER: String = """
        attribute vec4 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = aPosition;
            vTexCoord = aTexCoord;
        }
    """

    /**
     * Set to TRUE to completely bypass all retro effects and render a raw 1:1 camera passthrough.
     * This is required for Step 1 of the aggressive stabilization plan.
     */
    private const val MINIMAL_PASSTHROUGH_MODE = false

    /**
     * Fragment shader for OES external texture (camera preview).
     * Uses highp precision for Adreno GPU compatibility with samplerExternalOES.
     * Extension directive MUST be on the very first line for some drivers.
     */
    fun fragmentShaderExternalOes(): String {
        if (MINIMAL_PASSTHROUGH_MODE) {
            return """
                #extension GL_OES_EGL_image_external : require
                precision highp float;
                varying vec2 vTexCoord;
                uniform samplerExternalOES uTexture;
                void main() {
                    gl_FragColor = texture2D(uTexture, vTexCoord);
                }
            """.trimIndent()
        }
        
        return "#extension GL_OES_EGL_image_external : require\nprecision highp float;\n" +
            RETRO_FRAGMENT_CORE.replace("VKX_SAMPLER", "samplerExternalOES")
    }

    /**
     * Fragment shader for 2D texture (still export / FBO).
     * Uses mediump precision which is sufficient for offline rendering.
     */
    fun fragmentShaderTexture2d(): String =
        "precision mediump float;\n" +
            RETRO_FRAGMENT_CORE.replace("VKX_SAMPLER", "sampler2D")
}
