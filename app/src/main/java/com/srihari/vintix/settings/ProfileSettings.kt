package com.srihari.vintix.settings

import com.srihari.vintix.rendering.CameraProfile
import com.srihari.vintix.rendering.LightLeakBehavior
import com.srihari.vintix.rendering.timestamp.TimestampStyle
import com.srihari.vintix.rendering.timestamp.TimestampStyles

fun CameraProfile.withVintixSettings(settings: VintixSettings): CameraProfile =
    copy(
        jpegQuality = settings.exportQuality.resolve(jpegQuality),
        exportResolution = settings.exportResolution.resolve(exportResolution),
        lightLeakBehavior = if (settings.lightLeaksEnabled) lightLeakBehavior else LightLeakBehavior(),
        feedbackBehavior = feedbackBehavior.copy(
            hapticFeedback = feedbackBehavior.hapticFeedback && settings.hapticsEnabled,
            soundEnabled = feedbackBehavior.soundEnabled && settings.soundEnabled
        )
    )

fun VintixSettings.timestampStyle(): TimestampStyle? =
    if (timestampEnabled) TimestampStyles.ClassicOrange else TimestampStyles.None
