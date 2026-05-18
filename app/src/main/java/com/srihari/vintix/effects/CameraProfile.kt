package com.srihari.vintix.effects

import kotlin.math.max

data class InstabilityBehavior(
    val exposureVariance: Float = 0f,
    val warmthVariance: Float = 0f,
    val noiseVariance: Float = 0f,
) {
    fun jitter(base: Float, variance: Float): Float {
        if (variance <= 0f) return base
        return base + (Math.random().toFloat() * 2f - 1f) * variance
    }
}

data class FeedbackBehavior(
    val useDigitalBeep: Boolean = false,
    val soundEnabled: Boolean = true,
    val hapticFeedback: Boolean = true,
    val captureFreezeMs: Long = 100L,
    val flashFadeDurationMs: Int = 160,
)

enum class AspectRatio(val ratio: Float, val label: String) {
    RATIO_4_3(4f / 3f, "4:3"),
    RATIO_3_2(3f / 2f, "3:2"),
    RATIO_16_9(16f / 9f, "16:9"),
}

data class CameraProfile(
    val displayName: String,
    val contrast: Float,
    val saturation: Float,
    val warmth: Float,
    val coolHighlights: Float,
    val magentaShift: Float,
    val cyanShift: Float,
    val exposure: Float,
    val shadowCrush: Float,
    val fadedBlacks: Float,
    val highlightClip: Float,
    val bloom: Float,
    val sharpen: Float,
    val softness: Float,
    val grain: Float,
    val chromaNoise: Float,
    val jpegArtifacts: Float,
    val vignette: Float,
    val flashBurn: Float,
    val horizontalSmear: Float,
    val jpegQuality: Int,
    val aspectRatio: AspectRatio = AspectRatio.RATIO_4_3,
    val exportResolution: Int? = 1600,
    val instabilityBehavior: InstabilityBehavior = InstabilityBehavior(),
    val feedbackBehavior: FeedbackBehavior = FeedbackBehavior(),
) {
    fun applyInstability(): CameraProfile {
        val behavior = instabilityBehavior
        return copy(
            exposure = max(0f, behavior.jitter(exposure, behavior.exposureVariance)),
            warmth = behavior.jitter(warmth, behavior.warmthVariance),
            grain = max(0f, behavior.jitter(grain, behavior.noiseVariance)),
            chromaNoise = max(0f, behavior.jitter(chromaNoise, behavior.noiseVariance * 0.7f)),
        )
    }

    companion object {
        val Default = CameraProfiles.CyberShot
    }
}

object CameraProfiles {
    val CyberShot = CameraProfile(
        displayName = "CYBERSHOT",
        contrast = 1.22f,
        saturation = 1.06f,
        warmth = 0.05f,
        coolHighlights = 0.34f,
        magentaShift = 0.04f,
        cyanShift = 0.02f,
        exposure = 1.03f,
        shadowCrush = 0.32f,
        fadedBlacks = 0.02f,
        highlightClip = 0.42f,
        bloom = 0.42f,
        sharpen = 0.42f,
        softness = 0.05f,
        grain = 0.22f,
        chromaNoise = 0.18f,
        jpegArtifacts = 0.20f,
        vignette = 0.11f,
        flashBurn = 0.10f,
        horizontalSmear = 0f,
        jpegQuality = 86,
        exportResolution = 2048,
        instabilityBehavior = InstabilityBehavior(0.04f, 0.06f, 0.04f),
        feedbackBehavior = FeedbackBehavior(useDigitalBeep = true, flashFadeDurationMs = 130),
    )

    val Coolpix = CameraProfile(
        displayName = "COOLPIX",
        contrast = 1.28f,
        saturation = 1.22f,
        warmth = 0.12f,
        coolHighlights = 0.12f,
        magentaShift = 0.02f,
        cyanShift = 0.05f,
        exposure = 1.02f,
        shadowCrush = 0.38f,
        fadedBlacks = 0f,
        highlightClip = 0.28f,
        bloom = 0.18f,
        sharpen = 0.72f,
        softness = 0f,
        grain = 0.34f,
        chromaNoise = 0.28f,
        jpegArtifacts = 0.18f,
        vignette = 0.08f,
        flashBurn = 0.08f,
        horizontalSmear = 0f,
        jpegQuality = 88,
        exportResolution = 2048,
        instabilityBehavior = InstabilityBehavior(0.03f, 0.04f, 0.05f),
        feedbackBehavior = FeedbackBehavior(useDigitalBeep = true, flashFadeDurationMs = 110),
    )

    val Handycam = CameraProfile(
        displayName = "HANDYCAM",
        contrast = 0.94f,
        saturation = 0.78f,
        warmth = -0.12f,
        coolHighlights = 0.26f,
        magentaShift = 0.02f,
        cyanShift = 0.08f,
        exposure = 1.0f,
        shadowCrush = 0.18f,
        fadedBlacks = 0.08f,
        highlightClip = 0.24f,
        bloom = 0.24f,
        sharpen = 0.08f,
        softness = 0.28f,
        grain = 0.30f,
        chromaNoise = 0.30f,
        jpegArtifacts = 0.32f,
        vignette = 0.04f,
        flashBurn = 0.02f,
        horizontalSmear = 0.34f,
        jpegQuality = 78,
        aspectRatio = AspectRatio.RATIO_16_9,
        exportResolution = 1280,
        instabilityBehavior = InstabilityBehavior(0.05f, 0.05f, 0.05f),
        feedbackBehavior = FeedbackBehavior(useDigitalBeep = true, flashFadeDurationMs = 90),
    )

    val Vga1999 = CameraProfile(
        displayName = "VGA 1999",
        contrast = 0.98f,
        saturation = 0.68f,
        warmth = -0.05f,
        coolHighlights = 0.18f,
        magentaShift = 0.09f,
        cyanShift = 0.08f,
        exposure = 1.06f,
        shadowCrush = 0.24f,
        fadedBlacks = 0.12f,
        highlightClip = 0.52f,
        bloom = 0.26f,
        sharpen = 0.34f,
        softness = 0.14f,
        grain = 0.72f,
        chromaNoise = 0.68f,
        jpegArtifacts = 0.82f,
        vignette = 0.09f,
        flashBurn = 0.12f,
        horizontalSmear = 0.12f,
        jpegQuality = 48,
        exportResolution = 640,
        instabilityBehavior = InstabilityBehavior(0.08f, 0.10f, 0.12f),
        feedbackBehavior = FeedbackBehavior(useDigitalBeep = true, flashFadeDurationMs = 170),
    )

    val Disposable = CameraProfile(
        displayName = "DISPOSABLE",
        contrast = 1.04f,
        saturation = 0.86f,
        warmth = 0.34f,
        coolHighlights = 0f,
        magentaShift = 0.05f,
        cyanShift = -0.02f,
        exposure = 1.05f,
        shadowCrush = 0.12f,
        fadedBlacks = 0.18f,
        highlightClip = 0.36f,
        bloom = 0.36f,
        sharpen = 0.10f,
        softness = 0.12f,
        grain = 0.42f,
        chromaNoise = 0.26f,
        jpegArtifacts = 0.22f,
        vignette = 0.34f,
        flashBurn = 0.42f,
        horizontalSmear = 0f,
        jpegQuality = 84,
        aspectRatio = AspectRatio.RATIO_3_2,
        exportResolution = 1600,
        instabilityBehavior = InstabilityBehavior(0.10f, 0.10f, 0.08f),
        feedbackBehavior = FeedbackBehavior(useDigitalBeep = false, flashFadeDurationMs = 220),
    )

    val all: List<CameraProfile> = listOf(CyberShot, Coolpix, Handycam, Vga1999, Disposable)
}
