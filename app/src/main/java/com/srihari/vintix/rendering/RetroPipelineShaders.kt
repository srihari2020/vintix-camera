package com.srihari.vintix.rendering

/**
 * Shared GLSL for the Vintix retro color pipeline.
 * [fragmentShaderExternalOes] targets [android.opengl.GLES11Ext.GL_TEXTURE_EXTERNAL_OES];
 * [fragmentShaderTexture2d] targets [android.opengl.GLES20.GL_TEXTURE_2D] for still export / FBO.
 */
object RetroPipelineShaders {

    /**
     * Shader complexity levels for graceful degradation on mobile GPUs.
     */
    enum class ShaderLevel {
        FULL,       // All effects: halation, chromatic aberration, sensor noise, jpeg blocks
        MEDIUM,     // Core color science, vignette, simplified noise, no blocks/halation
        MINIMAL     // Basic color shift and vignette only (passthrough-like)
    }

    private val RETRO_FRAGMENT_CORE: String = """
            varying vec2 vTexCoord;
            uniform VKX_SAMPLER uTexture;
            uniform float uNoisePhase;
            uniform float uVignetteIntensity;
            uniform float uHalationStrength;
            uniform float uWarmth;
            uniform float uGreenMagentaShift;
            uniform float uDesaturation;
            uniform float uCcdClarity;
            uniform float uSensorNoise;
            uniform float uChromaNoise;
            uniform float uChromaticAberration;
            uniform float uLensSoftness;
            uniform float uInternalResolution;
            uniform float uBlockArtifacts;
            uniform float uMosquitoNoise;
            uniform float uShadowCrush;
            uniform float uHighlightHarshness;
            uniform float uBloomIntensity;
            uniform vec2 uResolution;

            #define SHADER_LEVEL_FULL 0
            #define SHADER_LEVEL_MEDIUM 1
            #define SHADER_LEVEL_MINIMAL 2
            
            #ifndef SHADER_LEVEL
            #define SHADER_LEVEL SHADER_LEVEL_FULL
            #endif

            // Authentic CCD luma (slight green bias in weights)
            float vnx_luma(vec3 c) {
                return dot(c, vec3(0.25, 0.65, 0.1));
            }

            float vnx_hash(vec2 p) {
                return fract(sin(dot(p, vec2(12.9898, 78.233))) * 43758.5453);
            }

            // Simulated highlight bloom (sensor saturation leak)
            vec3 vnx_ccd_bloom(VKX_SAMPLER tex, vec2 uv, vec3 c) {
                if (uBloomIntensity <= 0.0) return c;
                float y = vnx_luma(c);
                float bloomMask = smoothstep(0.7, 1.0, y);
                
                vec2 du = vec2(0.003, 0.0);
                vec2 dv = vec2(0.0, 0.003);
                vec3 blur = texture2D(tex, uv + du).rgb + texture2D(tex, uv - du).rgb
                          + texture2D(tex, uv + dv).rgb + texture2D(tex, uv - dv).rgb;
                blur *= 0.25;
                
                return mix(c, c + blur * uBloomIntensity, bloomMask);
            }

            vec4 vnx_lens_edge_capture(VKX_SAMPLER tex, vec2 uv, float r, vec2 dir, out vec3 centerRgb) {
                #if SHADER_LEVEL == SHADER_LEVEL_MINIMAL
                    vec4 ctr = texture2D(tex, uv);
                    centerRgb = ctr.rgb;
                    return ctr;
                #else
                    vec4 ctr = texture2D(tex, uv);
                    centerRgb = ctr.rgb;
                    
                    float edge = smoothstep(0.1, 0.9, r);
                    float ca = 0.0012 * edge * uChromaticAberration;
                    
                    vec2 uvR = uv + dir * ca;
                    vec2 uvB = uv - dir * ca * 1.2; // Harsher blue fringing
                    
                    float rChan = texture2D(tex, uvR).r;
                    float bChan = texture2D(tex, uvB).b;
                    vec3 sharp = vec3(rChan, ctr.g, bChan);
                    
                    #if SHADER_LEVEL == SHADER_LEVEL_MEDIUM
                        return vec4(sharp, ctr.a);
                    #else
                        // Lens softness: Smeary, not just blurry
                        float softW = smoothstep(0.15, 0.98, r);
                        vec2 softUv = uv - dir * 0.003 * softW * uLensSoftness;
                        vec3 softRgb = texture2D(tex, softUv).rgb;
                        
                        float lensMix = mix(0.1, 0.6, softW) * uLensSoftness;
                        return vec4(mix(sharp, softRgb, lensMix), ctr.a);
                    #endif
                #endif
            }

            vec3 vnx_vintage_color_science(vec3 c) {
                float y = vnx_luma(c);
                
                // AUTHENTIC COLOR SHIFTS
                // Yellowed whites: highlights push toward (1.0, 0.98, 0.9)
                // Cyan skies: Blue-greens push cyan
                // Dirty blacks: Shadows push magenta/green bias
                
                vec3 shadowShift = vec3(1.02, 0.98, 1.04); // Slight magenta shadows
                vec3 midShift = vec3(1.0, 1.02, 0.96);    // Yellow-green midtones
                vec3 highShift = vec3(0.96, 1.0, 1.04);   // Cyan-blue highlights
                
                // Apply green/magenta white balance shift
                vec3 wbShift = vec3(1.0 + uGreenMagentaShift * 0.05, 1.0 - uGreenMagentaShift * 0.05, 1.0);
                c *= wbShift;
                
                // Warmth shift (unstable early digital feel)
                vec3 warmTint = vec3(1.0 + uWarmth * 0.1, 1.0, 1.0 - uWarmth * 0.05);
                c *= warmTint;

                vec3 shifted = mix(c * shadowShift, c * midShift, smoothstep(0.0, 0.4, y));
                shifted = mix(shifted, c * highShift, smoothstep(0.5, 1.0, y));
                
                // Harsher clipping and shadow crush
                float threshold = 1.0 - (0.3 * uHighlightHarshness);
                // Clipping pushes toward yellow (warm clipping)
                vec3 clipColor = vec3(1.0, 1.0, 0.95);
                shifted = mix(shifted, clipColor, smoothstep(threshold, 1.02, y));
                
                float crush = uShadowCrush * 0.18;
                shifted = max(shifted - crush, 0.0) / (1.0 - crush);
                
                #if SHADER_LEVEL == SHADER_LEVEL_FULL
                    // Blue channel precision loss
                    shifted.b = floor(shifted.b * 24.0) / 24.0;
                #endif
                
                return shifted;
            }

            vec3 vnx_ccd_noise(vec3 c, float phase) {
                if (uSensorNoise <= 0.001) return c;
                
                vec2 fc = gl_FragCoord.xy;
                float y = vnx_luma(c);
                
                // Harsher shadow noise crawling
                float shadowBoost = 1.0 + pow(1.0 - smoothstep(0.0, 0.6, y), 2.5) * 4.0;
                float w = shadowBoost * uSensorNoise;

                #if SHADER_LEVEL == SHADER_LEVEL_FULL
                    // Blotchy chroma noise
                    vec2 blotchUv = floor(fc * 0.08); // Large blotches
                    float nR = vnx_hash(blotchUv + phase) - 0.5;
                    float nG = vnx_hash(blotchUv + phase + 7.0) - 0.5;
                    float nB = vnx_hash(blotchUv + phase + 19.0) - 0.5;
                    
                    vec3 chroma = vec3(nR, nG, nB) * 0.08 * uChromaNoise;
                    
                    // Fine luma grain
                    float grain = vnx_hash(fc + phase * 0.5) - 0.5;
                    return c + (vec3(grain * 0.02) + chroma) * w;
                #else
                    float grain = vnx_hash(fc * 0.5 + phase) - 0.5;
                    return c + vec3(grain * 0.04) * w;
                #endif
            }

            vec3 vnx_jpeg_artifacts(VKX_SAMPLER tex, vec3 c, vec2 uv) {
                #if SHADER_LEVEL == SHADER_LEVEL_FULL
                    if (uBlockArtifacts <= 0.001 && uMosquitoNoise <= 0.001) return c;
                    
                    vec2 res = uResolution;
                    if (uInternalResolution > 0.0) {
                        float aspect = uResolution.x / uResolution.y;
                        res = vec2(uInternalResolution * aspect, uInternalResolution);
                    }
                    
                    // 8x8 block artifacts
                    vec2 blockUv = floor(uv * res / 8.0) * 8.0 / res;
                    vec2 inBlock = fract(uv * res / 8.0);
                    
                    if (uBlockArtifacts > 0.0) {
                        vec3 blockColor = texture2D(tex, blockUv).rgb;
                        float edge = step(0.96, inBlock.x) + step(0.96, inBlock.y);
                        c = mix(c, blockColor * (1.0 + edge * 0.03), uBlockArtifacts * 0.35);
                    }
                    
                    // Mosquito noise (ringing around edges)
                    if (uMosquitoNoise > 0.0) {
                        float ring = vnx_hash(floor(uv * res)) - 0.5;
                        float edgeMask = length(fwidth(vnx_luma(c)));
                        c += ring * edgeMask * 1.5 * uMosquitoNoise;
                    }
                #endif
                return c;
            }

            vec4 vnx_retro_color_pipeline(VKX_SAMPLER tex, vec2 uv) {
                vec2 resUv = uv;
                #if SHADER_LEVEL != SHADER_LEVEL_MINIMAL
                    if (uInternalResolution > 0.0) {
                        float aspect = uResolution.x / uResolution.y;
                        vec2 res = vec2(uInternalResolution * aspect, uInternalResolution);
                        resUv = floor(uv * res) / res;
                    }
                #endif

                vec2 d = resUv - vec2(0.5);
                float r = length(d) * 2.0;
                vec2 dir = (r > 1e-4) ? (d / (r * 0.5)) : vec2(0.0);
                
                vec3 rawCenter;
                vec4 lc = vnx_lens_edge_capture(tex, resUv, r, dir, rawCenter);
                vec3 c = lc.rgb * uExposureMultiplier;
                
                // CCD Bloom
                c = vnx_ccd_bloom(tex, resUv, c);
                
                #if SHADER_LEVEL != SHADER_LEVEL_MINIMAL
                    if (uFlashCenterBoost > 0.0) {
                        float flashFalloff = pow(max(0.0, 1.0 - r * 0.9), 4.0); // Harsher falloff
                        c += c * flashFalloff * uFlashCenterBoost * 2.0;
                        float yVal = vnx_luma(c);
                        // Harsh washout
                        c = mix(c, vec3(yVal * 1.15), flashFalloff * uFlashContrastFlattening * 1.5);
                        // Blown highlights in flash
                        float flashClip = 1.0 - (uFlashHighlightClipping * 0.5);
                        c = min(c, vec3(flashClip)) / flashClip;
                    }
                #endif
                
                c = vnx_vintage_color_science(c);
                c = mix(vec3(vnx_luma(c)), c, clamp(uDesaturation, 0.0, 1.0));
                
                #if SHADER_LEVEL == SHADER_LEVEL_FULL
                    // Fake halation (sensor bleed)
                    if (uHalationStrength > 0.001) {
                        vec2 du = vec2(0.0025, 0.0);
                        vec2 dv = vec2(0.0, 0.0025);
                        vec3 halo = texture2D(tex, resUv + du).rgb + texture2D(tex, resUv - du).rgb
                                  + texture2D(tex, resUv + dv).rgb + texture2D(tex, resUv - dv).rgb;
                        c = clamp(c + halo * 0.008 * uHalationStrength, 0.0, 1.0);
                    }
                    
                    // CCD Clarity (sharpening artifacts)
                    if (uCcdClarity > 0.001) {
                        vec2 du = vec2(0.0012, 0.0);
                        vec2 dv = vec2(0.0, 0.0012);
                        vec3 avg = (texture2D(tex, resUv + du).rgb + texture2D(tex, resUv - du).rgb
                                 + texture2D(tex, resUv + dv).rgb + texture2D(tex, resUv - dv).rgb) * 0.25;
                        c = clamp(c + (vnx_luma(rawCenter - avg)) * 0.6 * uCcdClarity, 0.0, 1.0);
                    }
                #endif

                #if SHADER_LEVEL != SHADER_LEVEL_MINIMAL
                    // Micro-contrast instability
                    float yBell = vnx_luma(c);
                    float bell = smoothstep(0.05, 0.25, yBell) * (1.0 - smoothstep(0.35, 0.85, yBell));
                    c *= (1.0 + 0.12 * bell);
                #endif

                c = clamp(vnx_ccd_noise(c, uNoisePhase), 0.0, 1.0);
                
                // JPEG Artifacts (Blocks + Mosquito)
                c = vnx_jpeg_artifacts(tex, c, resUv);
                
                c *= (1.0 - uVignetteIntensity * pow(r, 2.8)); // Harsher vignette curve
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
    fun fragmentShaderExternalOes(level: ShaderLevel = ShaderLevel.FULL): String {
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
        
        val levelDefine = when(level) {
            ShaderLevel.FULL -> "#define SHADER_LEVEL 0\n"
            ShaderLevel.MEDIUM -> "#define SHADER_LEVEL 1\n"
            ShaderLevel.MINIMAL -> "#define SHADER_LEVEL 2\n"
        }

        return "#extension GL_OES_EGL_image_external : require\n" +
               "precision highp float;\n" +
               levelDefine +
               RETRO_FRAGMENT_CORE.replace("VKX_SAMPLER", "samplerExternalOES")
    }

    /**
     * Fragment shader for 2D texture (still export / FBO).
     * Uses mediump precision which is sufficient for offline rendering.
     */
    fun fragmentShaderTexture2d(level: ShaderLevel = ShaderLevel.FULL): String {
        val levelDefine = when(level) {
            ShaderLevel.FULL -> "#define SHADER_LEVEL 0\n"
            ShaderLevel.MEDIUM -> "#define SHADER_LEVEL 1\n"
            ShaderLevel.MINIMAL -> "#define SHADER_LEVEL 2\n"
        }
        return "precision mediump float;\n" +
               levelDefine +
               RETRO_FRAGMENT_CORE.replace("VKX_SAMPLER", "sampler2D")
    }
}
