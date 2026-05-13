package com.srihari.vintix.rendering

import android.opengl.GLES20

/**
 * Cached uniform locations and upload for [CameraProfile] + [uNoisePhase].
 * Shared by preview ([VintixRenderer]) and still export ([RetroPhotoGlPipeline]).
 */
class CameraProfileUniformHandles(program: ShaderProgram) {

    private val uNoisePhase = program.getUniformLocation("uNoisePhase")
    private val uVignetteIntensity = program.getUniformLocation("uVignetteIntensity")
    private val uHalationStrength = program.getUniformLocation("uHalationStrength")
    private val uWarmth = program.getUniformLocation("uWarmth")
    private val uDesaturation = program.getUniformLocation("uDesaturation")
    private val uCcdClarity = program.getUniformLocation("uCcdClarity")
    private val uSensorNoise = program.getUniformLocation("uSensorNoise")
    private val uChromaticAberration = program.getUniformLocation("uChromaticAberration")
    private val uLensSoftness = program.getUniformLocation("uLensSoftness")
    private val uFlashCenterBoost = program.getUniformLocation("uFlashCenterBoost")
    private val uFlashWarmBloom = program.getUniformLocation("uFlashWarmBloom")
    private val uFlashHighlightClipping = program.getUniformLocation("uFlashHighlightClipping")
    private val uFlashContrastFlattening = program.getUniformLocation("uFlashContrastFlattening")
    private val uLeakIntensity = program.getUniformLocation("uLeakIntensity")
    private val uLeakOrigin = program.getUniformLocation("uLeakOrigin")
    private val uExposureMultiplier = program.getUniformLocation("uExposureMultiplier")

    fun upload(
        profile: CameraProfile, 
        noisePhase: Float, 
        isExport: Boolean,
        leakIntensity: Float = 0f,
        leakOriginX: Float = 0f,
        leakOriginY: Float = 0f
    ) {
        if (uNoisePhase >= 0) GLES20.glUniform1f(uNoisePhase, noisePhase)
        if (uVignetteIntensity >= 0) GLES20.glUniform1f(uVignetteIntensity, profile.vignetteIntensity)
        if (uWarmth >= 0) GLES20.glUniform1f(uWarmth, profile.warmth)
        if (uDesaturation >= 0) GLES20.glUniform1f(uDesaturation, profile.desaturation)
        if (uCcdClarity >= 0) GLES20.glUniform1f(uCcdClarity, profile.ccdClarity)
        if (uSensorNoise >= 0) GLES20.glUniform1f(uSensorNoise, profile.sensorNoise)
        if (uChromaticAberration >= 0) GLES20.glUniform1f(uChromaticAberration, profile.chromaticAberration)
        if (uLensSoftness >= 0) GLES20.glUniform1f(uLensSoftness, profile.lensSoftness)
        if (uExposureMultiplier >= 0) GLES20.glUniform1f(uExposureMultiplier, profile.exposureMultiplier)

        if (isExport) {
            if (uHalationStrength >= 0) GLES20.glUniform1f(uHalationStrength, profile.halationStrength * profile.flashBehavior.extraHalation)
            if (uFlashCenterBoost >= 0) GLES20.glUniform1f(uFlashCenterBoost, profile.flashBehavior.centerExposureBoost)
            if (uFlashWarmBloom >= 0) GLES20.glUniform1f(uFlashWarmBloom, profile.flashBehavior.warmBloom)
            if (uFlashHighlightClipping >= 0) GLES20.glUniform1f(uFlashHighlightClipping, profile.flashBehavior.highlightClipping)
            if (uFlashContrastFlattening >= 0) GLES20.glUniform1f(uFlashContrastFlattening, profile.flashBehavior.contrastFlattening)
            if (uLeakIntensity >= 0) GLES20.glUniform1f(uLeakIntensity, leakIntensity)
            if (uLeakOrigin >= 0) GLES20.glUniform2f(uLeakOrigin, leakOriginX, leakOriginY)
        } else {
            if (uHalationStrength >= 0) GLES20.glUniform1f(uHalationStrength, profile.halationStrength)
            if (uFlashCenterBoost >= 0) GLES20.glUniform1f(uFlashCenterBoost, 0f)
            if (uFlashWarmBloom >= 0) GLES20.glUniform1f(uFlashWarmBloom, 0f)
            if (uFlashHighlightClipping >= 0) GLES20.glUniform1f(uFlashHighlightClipping, 0f)
            if (uFlashContrastFlattening >= 0) GLES20.glUniform1f(uFlashContrastFlattening, 0f)
            if (uLeakIntensity >= 0) GLES20.glUniform1f(uLeakIntensity, 0f)
        }
    }
}
