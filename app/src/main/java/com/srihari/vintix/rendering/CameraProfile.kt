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
    /** Saturation factor in mild desaturation (`1.0` = full color, `0.935` = legacy default). */
    val desaturation: Float = 0.935f,
    /** Scales CCD edge clarity / unsharp gain (baseline `1.0`). */
    val ccdClarity: Float = 1f,
    /** Scales procedural sensor noise amplitudes (baseline `1.0`). */
    val sensorNoise: Float = 1f,
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
            vignetteIntensity = i.jitter(vignetteIntensity, i.vignetteVariance).coerceAtLeast(0f),
            halationStrength = i.jitter(halationStrength, i.halationVariance).coerceAtLeast(0f),
            sensorNoise = i.jitter(sensorNoise, i.noiseVariance).coerceAtLeast(0f)
        )
    }

    companion object {
        /** Default tuning preserved from the original hardcoded shader. */
        val Default = CameraProfile(
            jpegQuality = 88,
            aspectRatio = AspectRatio.RATIO_4_3,
            exportResolution = 1600, // ~2MP authentic early digital res
            flashBehavior = FlashBehavior(
                centerExposureBoost = 0.25f,
                warmBloom = 0.3f,
                highlightClipping = 0.6f,
                contrastFlattening = 0.15f,
                extraHalation = 1.2f
            ),
            instabilityBehavior = InstabilityBehavior(
                exposureVariance = 0.03f,
                warmthVariance = 0.05f,
                noiseVariance = 0.1f
            ),
            feedbackBehavior = FeedbackBehavior(
                useDigitalBeep = true,
                hapticFeedback = true,
                captureFreezeMs = 150L,
                flashFadeDurationMs = 0
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
     * Example alternate preset: lighter retro treatment for reference / daylight.
     * (Demonstrates multi-profile without changing the default baseline.)
     */
    val DaylightNeutral = CameraProfile(
        vignetteIntensity = 0.05f,
        halationStrength = 0.45f,
        warmth = 0.55f,
        desaturation = 0.97f,
        ccdClarity = 0.55f,
        sensorNoise = 0.45f,
        chromaticAberration = 0.5f,
        lensSoftness = 0.55f,
        jpegQuality = 95,
        aspectRatio = AspectRatio.RATIO_4_3,
        exportResolution = 1600,
        flashBehavior = FlashBehavior(
            centerExposureBoost = 0.15f,
            warmBloom = 0.1f,
            highlightClipping = 0.3f,
            contrastFlattening = 0.05f,
            extraHalation = 1.1f
        ),
        feedbackBehavior = FeedbackBehavior(
            useDigitalBeep = false,
            hapticFeedback = false,
            captureFreezeMs = 50L,
            flashFadeDurationMs = 0
        )
    )

    /**
     * High contrast, cooler tone, sharp, heavy noise.
     */
    val CyberShot2003 = CameraProfile(
        vignetteIntensity = 0.15f,
        halationStrength = 1.2f,
        warmth = 0.7f,
        desaturation = 0.9f,
        ccdClarity = 1.5f,
        sensorNoise = 1.8f,
        chromaticAberration = 0.8f,
        lensSoftness = 0.6f,
        jpegQuality = 82,
        aspectRatio = AspectRatio.RATIO_4_3,
        exportResolution = 1280, // ~1.2MP chunkier file
        flashBehavior = FlashBehavior(
            centerExposureBoost = 0.4f,
            warmBloom = 0.1f,
            highlightClipping = 0.9f,
            contrastFlattening = 0.3f,
            extraHalation = 1.8f
        ),
        instabilityBehavior = InstabilityBehavior(
            exposureVariance = 0.08f,
            warmthVariance = 0.1f,
            noiseVariance = 0.3f
        ),
        feedbackBehavior = FeedbackBehavior(
            useDigitalBeep = true,
            hapticFeedback = false,
            captureFreezeMs = 300L,
            flashFadeDurationMs = 200
        )
    )

    /**
     * Heavy vignette, strong chromatic aberration, soft lens, warm, high desaturation.
     */
    val DisposableFilm = CameraProfile(
        vignetteIntensity = 0.35f,
        halationStrength = 0.8f,
        warmth = 1.4f,
        desaturation = 0.75f,
        ccdClarity = 0.4f,
        sensorNoise = 1.2f,
        chromaticAberration = 2.5f,
        lensSoftness = 1.8f,
        jpegQuality = 70,
        aspectRatio = AspectRatio.RATIO_3_2,
        exportResolution = null, // Max resolution, mimicking film
        flashBehavior = FlashBehavior(
            centerExposureBoost = 0.5f,
            warmBloom = 0.8f,
            highlightClipping = 0.4f,
            contrastFlattening = 0.1f,
            extraHalation = 1.5f
        ),
        lightLeakBehavior = LightLeakBehavior(
            probability = 0.40f,
            maxIntensity = 0.85f
        ),
        instabilityBehavior = InstabilityBehavior(
            exposureVariance = 0.15f,
            warmthVariance = 0.25f,
            vignetteVariance = 0.1f,
            halationVariance = 0.3f,
            noiseVariance = 0.2f
        ),
        feedbackBehavior = FeedbackBehavior(
            useDigitalBeep = false,
            hapticFeedback = true,
            captureFreezeMs = 50L,
            flashFadeDurationMs = 800
        )
    )
}
