package com.srihari.vintix.rendering

/**
 * Defines the simulated hardware flash characteristics applied during photo export.
 */
data class FlashBehavior(
    /** Center-weighted exposure multiplier to simulate direct flash. */
    val centerExposureBoost: Float = 0f,
    /** Intensity of warm highlight bloom caused by flash. */
    val warmBloom: Float = 0f,
    /** How harshly highlights clip (e.g. 0.0 = no extra clip, 1.0 = harsh digital clip). */
    val highlightClipping: Float = 0f,
    /** Flattens contrast in the center to simulate direct light washing out shadows. */
    val contrastFlattening: Float = 0f,
    /** Extra halation multiplier applied only during flash capture. */
    val extraHalation: Float = 1f
)

/**
 * Defines the probability and intensity of procedural light leaks during export.
 */
data class LightLeakBehavior(
    /** Probability (0.0 to 1.0) of a light leak occurring. */
    val probability: Float = 0f,
    /** Maximum intensity multiplier for the leak. */
    val maxIntensity: Float = 0f
)

/**
 * Defines per-capture random variance applied to camera properties for organic inconsistency.
 */
data class InstabilityBehavior(
    /** Variance applied to exposure multiplier (e.g. 0.05 means +/- 0.05). */
    val exposureVariance: Float = 0f,
    /** Variance applied to warmth. */
    val warmthVariance: Float = 0f,
    /** Variance applied to green/magenta white balance shift. */
    val wbVariance: Float = 0f,
    /** Variance applied to vignette. */
    val vignetteVariance: Float = 0f,
    /** Variance applied to halation. */
    val halationVariance: Float = 0f,
    /** Variance applied to sensor noise. */
    val noiseVariance: Float = 0f
) {
    /** Helper to generate a randomly jittered value. */
    fun jitter(base: Float, variance: Float): Float {
        if (variance <= 0f) return base
        return base + (Math.random().toFloat() * 2f - 1f) * variance
    }
}

/**
 * Defines audiovisual and tactile feedback during photo capture.
 */
data class FeedbackBehavior(
    /** If true, plays a digital beep. If false, plays a mechanical shutter click. */
    val useDigitalBeep: Boolean = false,
    /** If false, suppresses profile capture sound while preserving haptic/visual feedback. */
    val soundEnabled: Boolean = true,
    /** If true, triggers a physical haptic bump on capture. */
    val hapticFeedback: Boolean = true,
    /** How long to freeze the live viewfinder (simulates mechanical mirror blackout or digital CCD freeze). */
    val captureFreezeMs: Long = 100L,
    /** Duration of the white UI flash overlay (0 = no flash). */
    val flashFadeDurationMs: Int = 0
)

/**
 * Defines the physical aspect ratio of the camera sensor.
 */
enum class AspectRatio(val ratio: Float, val label: String) {
    RATIO_4_3(4f / 3f, "4:3"),
    RATIO_3_2(3f / 2f, "3:2"),
    RATIO_16_9(16f / 9f, "16:9")
}

/**
 * Tunable parameters for the realtime retro camera fragment pipeline.
 * Values map 1:1 to GLSL uniforms (see [RetroPipelineShaders] / preview [VintixRenderer]).
 *
 * Threading: read on the OpenGL thread during [com.srihari.vintix.rendering.VintixRenderer.onDrawFrame];
 * assign new profiles on the same thread or ensure visibility before the next frame.
 */
data class CameraProfile(
    /** Radial vignette strength (matches prior `0.11` multiplier at `1.0`). */
    val vignetteIntensity: Float = 0.11f,
    /** Scales pseudo-halation accumulation (baseline `1.0` = legacy look). */
    val halationStrength: Float = 1f,
    /**
     * Warmth: scales warm highlight rolloff and halation tint toward neutral when lowered.
     * `1.0` matches the original pipeline.
     */
    val warmth: Float = 1f,
    /** Green/Magenta white balance shift (-1.0 to 1.0). */
    val greenMagentaShift: Float = 0f,
    /** Saturation factor in mild desaturation (`1.0` = full color, `0.935` = legacy default). */
    val desaturation: Float = 0.935f,
    /** Scales CCD edge clarity / unsharp gain (baseline `1.0`). */
    val ccdClarity: Float = 1f,
    /** Scales procedural sensor noise amplitudes (baseline `1.0`). */
    val sensorNoise: Float = 1f,
    /** Intensity of blotchy chroma noise (0.0 to 2.0). */
    val chromaNoise: Float = 0f,
    /** Scales chromatic aberration offset in the lens stage (baseline `1.0`). */
    val chromaticAberration: Float = 1f,
    /** Scales lens edge softness mix and sample shift (baseline `1.0`). */
    val lensSoftness: Float = 1f,
    /** Quality setting for JPEG compression upon export (0-100). */
    val jpegQuality: Int = 92,
    /** Base exposure multiplier (1.0 = normal exposure). */
    val exposureMultiplier: Float = 1f,
    /** Target aspect ratio for framing and export. */
    val aspectRatio: AspectRatio = AspectRatio.RATIO_4_3,
    /** Maximum pixel length of the longest side during export. Null means native sensor resolution. */
    val exportResolution: Int? = null,
    /** If > 0, internally pixelates UV coordinates to simulate this vertical resolution. */
    val internalResolution: Float = 0f,
    /** Simulates 8x8 block artifacts common in low-quality JPEGs. (0.0 to 2.0) */
    val blockArtifacts: Float = 0f,
    /** Intensity of mosquito noise artifacts (0.0 to 2.0). */
    val mosquitoNoise: Float = 0f,
    /** Crushes shadow detail (0.0 to 1.0). */
    val shadowCrush: Float = 0.1f,
    /** Harshness of highlight clipping (0.0 = soft, 1.0 = harsh digital clip). */
    val highlightHarshness: Float = 0.5f,
    /** CCD saturation bloom intensity (0.0 to 1.0). */
    val bloomIntensity: Float = 0f,
    /** Flash characteristics applied during photo export. */
    val flashBehavior: FlashBehavior = FlashBehavior(),
    /** Probabilistic light leak behavior applied during photo export. */
    val lightLeakBehavior: LightLeakBehavior = LightLeakBehavior(),
    /** Instability/randomness configuration applied per-capture. */
    val instabilityBehavior: InstabilityBehavior = InstabilityBehavior(),
    /** Audiovisual and tactile feedback during capture. */
    val feedbackBehavior: FeedbackBehavior = FeedbackBehavior()
) {
    /** Generates a new profile with jittered parameters for a single capture. */
    fun applyInstability(): CameraProfile {
        if (instabilityBehavior == InstabilityBehavior()) return this
        val i = instabilityBehavior
        return this.copy(
            exposureMultiplier = i.jitter(exposureMultiplier, i.exposureVariance).coerceAtLeast(0f),
            warmth = i.jitter(warmth, i.warmthVariance).coerceAtLeast(0f),
            greenMagentaShift = i.jitter(greenMagentaShift, i.wbVariance),
            vignetteIntensity = i.jitter(vignetteIntensity, i.vignetteVariance).coerceAtLeast(0f),
            halationStrength = i.jitter(halationStrength, i.halationVariance).coerceAtLeast(0f),
            sensorNoise = i.jitter(sensorNoise, i.noiseVariance).coerceAtLeast(0f)
        )
    }

    companion object {
        /** Default tuning: 2002-era mid-range consumer CCD (e.g. Canon PowerShot S-series) */
        val Default = CameraProfile(
            vignetteIntensity = 0.12f,
            halationStrength = 0.65f,
            warmth = 0.88f,
            greenMagentaShift = 0.04f, // Slight green tint bias
            desaturation = 0.94f,
            ccdClarity = 0.85f,
            sensorNoise = 0.75f,
            chromaNoise = 0.65f,
            chromaticAberration = 0.85f,
            lensSoftness = 0.65f,
            jpegQuality = 82,
            aspectRatio = AspectRatio.RATIO_4_3,
            exportResolution = 1600, // 2MP
            internalResolution = 1200f,
            blockArtifacts = 0.45f,
            mosquitoNoise = 0.35f,
            shadowCrush = 0.22f,
            highlightHarshness = 0.75f,
            bloomIntensity = 0.15f,
            flashBehavior = FlashBehavior(
                centerExposureBoost = 0.45f,
                warmBloom = 0.35f,
                highlightClipping = 0.82f,
                contrastFlattening = 0.35f,
                extraHalation = 1.25f
            ),
            instabilityBehavior = InstabilityBehavior(
                exposureVariance = 0.04f,
                warmthVariance = 0.05f,
                wbVariance = 0.08f,
                noiseVariance = 0.12f
            ),
            feedbackBehavior = FeedbackBehavior(
                useDigitalBeep = true,
                hapticFeedback = true,
                captureFreezeMs = 150L,
                flashFadeDurationMs = 120
            )
        )
    }
}

/**
 * Named presets for UI or future camera modes. Add new entries as product presets grow.
 */
object CameraProfiles {
    /** Same as [CameraProfile.Default]: early-2000s consumer CCD character. */
    val EarlyDigitalConsumer = CameraProfile.Default

    /**
     * Daylight optimized: cleaner, but still preserves CCD color science.
     */
    val DaylightNeutral = CameraProfile(
        vignetteIntensity = 0.06f,
        halationStrength = 0.35f,
        warmth = 0.45f,
        greenMagentaShift = 0f,
        desaturation = 0.98f,
        ccdClarity = 0.45f,
        sensorNoise = 0.35f,
        chromaNoise = 0.15f,
        chromaticAberration = 0.45f,
        lensSoftness = 0.35f,
        jpegQuality = 95,
        aspectRatio = AspectRatio.RATIO_4_3,
        exportResolution = 2560, // 5MP
        internalResolution = 0f,
        blockArtifacts = 0.15f,
        mosquitoNoise = 0.1f,
        shadowCrush = 0.08f,
        highlightHarshness = 0.35f,
        bloomIntensity = 0.05f,
        flashBehavior = FlashBehavior(
            centerExposureBoost = 0.25f,
            warmBloom = 0.12f,
            highlightClipping = 0.25f,
            contrastFlattening = 0.15f,
            extraHalation = 1.05f
        ),
        feedbackBehavior = FeedbackBehavior(
            useDigitalBeep = false,
            hapticFeedback = false,
            captureFreezeMs = 50L,
            flashFadeDurationMs = 60
        )
    )

    /**
     * Aggressive 2003 look: sharp, high chroma noise, cyan skies, yellow whites.
     */
    val CyberShot2003 = CameraProfile(
        vignetteIntensity = 0.18f,
        halationStrength = 0.95f,
        warmth = 0.35f,
        greenMagentaShift = 0.12f, // Stronger green bias
        desaturation = 0.92f,
        ccdClarity = 1.85f, // Oversharpened
        sensorNoise = 1.45f,
        chromaNoise = 1.95f, // Blotchy color noise
        chromaticAberration = 1.15f,
        lensSoftness = 0.45f,
        jpegQuality = 45,
        aspectRatio = AspectRatio.RATIO_4_3,
        exportResolution = 1280, // 1.2MP
        internalResolution = 960f,
        blockArtifacts = 1.25f,
        mosquitoNoise = 1.65f,
        shadowCrush = 0.45f,
        highlightHarshness = 0.98f, // Digital clipping
        bloomIntensity = 0.35f,
        flashBehavior = FlashBehavior(
            centerExposureBoost = 0.65f,
            warmBloom = 0.15f,
            highlightClipping = 0.95f,
            contrastFlattening = 0.45f,
            extraHalation = 1.45f
        ),
        instabilityBehavior = InstabilityBehavior(
            exposureVariance = 0.08f,
            warmthVariance = 0.12f,
            wbVariance = 0.15f,
            noiseVariance = 0.35f
        ),
        feedbackBehavior = FeedbackBehavior(
            useDigitalBeep = true,
            hapticFeedback = true,
            captureFreezeMs = 450L,
            flashFadeDurationMs = 350
        )
    )

    /**
     * Plastic lens, warm shifts, heavy CA, soft focus.
     */
    val DisposableFilm = CameraProfile(
        vignetteIntensity = 0.38f,
        halationStrength = 1.15f,
        warmth = 1.45f,
        greenMagentaShift = -0.08f, // Magenta bias
        desaturation = 0.82f,
        ccdClarity = 0.15f, // Soft
        sensorNoise = 1.25f,
        chromaNoise = 0.85f,
        chromaticAberration = 2.25f, // Plastic lens fringing
        lensSoftness = 1.85f,
        jpegQuality = 35,
        aspectRatio = AspectRatio.RATIO_3_2,
        exportResolution = null,
        internalResolution = 720f,
        blockArtifacts = 0.85f,
        mosquitoNoise = 0.45f,
        shadowCrush = 0.18f,
        highlightHarshness = 0.45f,
        bloomIntensity = 0.45f,
        flashBehavior = FlashBehavior(
            centerExposureBoost = 0.55f,
            warmBloom = 0.65f,
            highlightClipping = 0.45f,
            contrastFlattening = 0.35f,
            extraHalation = 1.35f
        ),
        lightLeakBehavior = LightLeakBehavior(
            probability = 0.25f,
            maxIntensity = 0.55f
        ),
        instabilityBehavior = InstabilityBehavior(
            exposureVariance = 0.12f,
            warmthVariance = 0.18f,
            vignetteVariance = 0.15f,
            halationVariance = 0.25f,
            noiseVariance = 0.2f
        ),
        feedbackBehavior = FeedbackBehavior(
            useDigitalBeep = false,
            hapticFeedback = true,
            captureFreezeMs = 150L,
            flashFadeDurationMs = 600
        )
    )
}

