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

    fun upload(profile: CameraProfile, noisePhase: Float) {
        if (uNoisePhase >= 0) GLES20.glUniform1f(uNoisePhase, noisePhase)
        if (uVignetteIntensity >= 0) GLES20.glUniform1f(uVignetteIntensity, profile.vignetteIntensity)
        if (uHalationStrength >= 0) GLES20.glUniform1f(uHalationStrength, profile.halationStrength)
        if (uWarmth >= 0) GLES20.glUniform1f(uWarmth, profile.warmth)
        if (uDesaturation >= 0) GLES20.glUniform1f(uDesaturation, profile.desaturation)
        if (uCcdClarity >= 0) GLES20.glUniform1f(uCcdClarity, profile.ccdClarity)
        if (uSensorNoise >= 0) GLES20.glUniform1f(uSensorNoise, profile.sensorNoise)
        if (uChromaticAberration >= 0) GLES20.glUniform1f(uChromaticAberration, profile.chromaticAberration)
        if (uLensSoftness >= 0) GLES20.glUniform1f(uLensSoftness, profile.lensSoftness)
    }
}
