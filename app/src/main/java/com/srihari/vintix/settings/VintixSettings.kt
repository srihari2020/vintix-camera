package com.srihari.vintix.settings

enum class ExportResolutionPreset(val label: String) {
    PROFILE("PROFILE"),
    CLASSIC_1MP("1.2MP"),
    CLASSIC_2MP("2MP"),
    NATIVE("FULL");

    fun resolve(profileResolution: Int?): Int? = when (this) {
        PROFILE -> profileResolution
        CLASSIC_1MP -> 1280
        CLASSIC_2MP -> 1600
        NATIVE -> null
    }
}

enum class ExportQualityPreset(val label: String) {
    PROFILE("PROFILE"),
    CLEAN("CLEAN"),
    STANDARD("STD"),
    COMPACT("COMPACT");

    fun resolve(profileQuality: Int): Int = when (this) {
        PROFILE -> profileQuality
        CLEAN -> 94
        STANDARD -> 88
        COMPACT -> 76
    }
}

data class VintixSettings(
    val timestampEnabled: Boolean = true,
    val lightLeaksEnabled: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val soundEnabled: Boolean = true,
    val exportResolution: ExportResolutionPreset = ExportResolutionPreset.PROFILE,
    val exportQuality: ExportQualityPreset = ExportQualityPreset.PROFILE,
)
