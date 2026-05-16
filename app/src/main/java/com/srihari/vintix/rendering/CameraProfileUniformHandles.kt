package com.srihari.vintix.rendering

import android.opengl.GLES20

/**
 * Simplified uniform handles for the mobile-optimized retro pipeline.
 */
class CameraProfileUniformHandles(program: ShaderProgram) {

    private val uNoisePhase = program.getUniformLocation("uNoisePhase")
    private val uVignetteIntensity = program.getUniformLocation("uVignetteIntensity")
    private val uWarmth = program.getUniformLocation("uWarmth")
    private val uDesaturation = program.getUniformLocation("uDesaturation")
    private val uSensorNoise = program.getUniformLocation("uSensorNoise")
    private val uChromaNoise = program.getUniformLocation("uChromaNoise")
    private val uChromaticAberration = program.getUniformLocation("uChromaticAberration")
    private val uExposureMultiplier = program.getUniformLocation("uExposureMultiplier")
    private val uShadowCrush = program.getUniformLocation("uShadowCrush")
    private val uHighlightHarshness = program.getUniformLocation("uHighlightHarshness")
    private val uBlockArtifacts = program.getUniformLocation("uBlockArtifacts")

    fun upload(
        profile: CameraProfile, 
        noisePhase: Float
    ) {
        if (uNoisePhase >= 0) GLES20.glUniform1f(uNoisePhase, noisePhase)
        if (uVignetteIntensity >= 0) GLES20.glUniform1f(uVignetteIntensity, profile.vignetteIntensity)
        if (uWarmth >= 0) GLES20.glUniform1f(uWarmth, profile.warmth)
        if (uDesaturation >= 0) GLES20.glUniform1f(uDesaturation, profile.desaturation)
        if (uSensorNoise >= 0) GLES20.glUniform1f(uSensorNoise, profile.sensorNoise)
        if (uChromaNoise >= 0) GLES20.glUniform1f(uChromaNoise, profile.chromaNoise)
        if (uChromaticAberration >= 0) GLES20.glUniform1f(uChromaticAberration, profile.chromaticAberration)
        if (uExposureMultiplier >= 0) GLES20.glUniform1f(uExposureMultiplier, profile.exposureMultiplier)
        if (uShadowCrush >= 0) GLES20.glUniform1f(uShadowCrush, profile.shadowCrush)
        if (uHighlightHarshness >= 0) GLES20.glUniform1f(uHighlightHarshness, profile.highlightHarshness)
        if (uBlockArtifacts >= 0) GLES20.glUniform1f(uBlockArtifacts, profile.blockArtifacts)
    }
}
