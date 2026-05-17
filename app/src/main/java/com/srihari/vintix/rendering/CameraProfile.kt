package com.srihari.vintix.rendering

/**
 * Defines per-capture random variance applied to camera properties for organic inconsistency.
 */
data class InstabilityBehavior(
    val exposureVariance: Float = 0f,
    val warmthVariance: Float = 0f,
    val noiseVariance: Float = 0f
) {
    fun jitter(base: Float, variance: Float): Float {
        if (variance <= 0f) return base
        return base + (Math.random().toFloat() * 2f - 1f) * variance
    }
}

/**
 * Defines audiovisual and tactile feedback during photo capture.
 */
data class FeedbackBehavior(
    val useDigitalBeep: Boolean = false,
    val soundEnabled: Boolean = true,
    val hapticFeedback: Boolean = true,
    val captureFreezeMs: Long = 100L,
    val flashFadeDurationMs: Int = 0
)

enum class AspectRatio(val ratio: Float, val label: String) {
    RATIO_4_3(4f / 3f, "4:3"),
    RATIO_3_2(3f / 2f, "3:2"),
    RATIO_16_9(16f / 9f, "16:9")
}

/**
 * Simplified mobile-first camera profile.
 * Focuses on stability and authentic CCD color science.
 */
data class CameraProfile(
    val vignetteIntensity: Float = 0.12f,
    val warmth: Float = 1.0f,
    val desaturation: Float = 0.94f,
    val sensorNoise: Float = 0.8f,
    val chromaNoise: Float = 0.6f,
    val chromaticAberration: Float = 0.5f,
    val exposureMultiplier: Float = 1.0f,
    val shadowCrush: Float = 0.2f,
    val highlightHarshness: Float = 0.7f,
    val blockArtifacts: Float = 0.4f,
    val jpegQuality: Int = 85,
    val aspectRatio: AspectRatio = AspectRatio.RATIO_4_3,
    val exportResolution: Int? = 1600,
    val instabilityBehavior: InstabilityBehavior = InstabilityBehavior(),
    val feedbackBehavior: FeedbackBehavior = FeedbackBehavior()
) {
    fun applyInstability(): CameraProfile {
        val i = instabilityBehavior
        return this.copy(
            exposureMultiplier = i.jitter(exposureMultiplier, i.exposureVariance).coerceAtLeast(0f),
            warmth = i.jitter(warmth, i.warmthVariance).coerceAtLeast(0f),
            sensorNoise = i.jitter(sensorNoise, i.noiseVariance).coerceAtLeast(0f)
        )
    }

    companion object {
        val Default = CameraProfile()
    }
}

object CameraProfiles {
    val EarlyDigitalConsumer = CameraProfile(
        vignetteIntensity = 0.12f,
        warmth = 0.9f,
        sensorNoise = 0.8f,
        chromaNoise = 0.7f,
        chromaticAberration = 0.6f,
        shadowCrush = 0.25f,
        highlightHarshness = 0.8f,
        blockArtifacts = 0.4f,
        jpegQuality = 80
    )

    val CyberShot2003 = CameraProfile(
        vignetteIntensity = 0.15f,
        warmth = 0.4f,
        sensorNoise = 1.2f,
        chromaNoise = 1.5f,
        chromaticAberration = 1.2f,
        shadowCrush = 0.4f,
        highlightHarshness = 0.95f,
        blockArtifacts = 1.0f,
        jpegQuality = 50,
        exportResolution = 1280
    )

    val DisposableFilm = CameraProfile(
        vignetteIntensity = 0.35f,
        warmth = 1.4f,
        desaturation = 0.8f,
        sensorNoise = 1.0f,
        chromaNoise = 0.8f,
        chromaticAberration = 2.0f,
        shadowCrush = 0.2f,
        highlightHarshness = 0.5f,
        blockArtifacts = 0.6f,
        jpegQuality = 40,
        aspectRatio = AspectRatio.RATIO_3_2,
        exportResolution = null
    )

    val DaylightNeutral = CameraProfile(
        vignetteIntensity = 0.08f,
        warmth = 0.5f,
        desaturation = 0.98f,
        sensorNoise = 0.4f,
        chromaNoise = 0.2f,
        chromaticAberration = 0.4f,
        shadowCrush = 0.1f,
        highlightHarshness = 0.3f,
        blockArtifacts = 0.2f,
        jpegQuality = 92,
        exportResolution = 2048
    )

    val VGA1999 = CameraProfile(
        vignetteIntensity = 0.1f,
        warmth = 0.3f,
        desaturation = 0.6f,
        sensorNoise = 2.5f,
        chromaNoise = 3.0f,
        chromaticAberration = 1.5f,
        shadowCrush = 0.5f,
        highlightHarshness = 1.2f,
        blockArtifacts = 2.0f,
        jpegQuality = 30,
        exportResolution = 640
    )

    val Coolpix = CameraProfile(
        vignetteIntensity = 0.18f,
        warmth = 1.2f,
        desaturation = 1.1f,
        sensorNoise = 0.9f,
        chromaNoise = 1.0f,
        chromaticAberration = 0.8f,
        shadowCrush = 0.3f,
        highlightHarshness = 0.85f,
        blockArtifacts = 0.5f,
        jpegQuality = 85
    )

    val Handycam = CameraProfile(
        vignetteIntensity = 0.05f,
        warmth = 0.2f,
        desaturation = 0.85f,
        sensorNoise = 1.8f,
        chromaNoise = 2.2f,
        chromaticAberration = 0.5f,
        shadowCrush = 0.45f,
        highlightHarshness = 1.0f,
        blockArtifacts = 1.5f,
        jpegQuality = 60,
        aspectRatio = AspectRatio.RATIO_16_9,
        exportResolution = 720
    )
}
