package com.srihari.vintix.rendering

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
    val jpegQuality: Int = 92
) {
    companion object {
        /** Default tuning preserved from the original hardcoded shader. */
        val Default = CameraProfile(jpegQuality = 88)
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
        jpegQuality = 95
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
        jpegQuality = 82
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
        jpegQuality = 70
    )
}
