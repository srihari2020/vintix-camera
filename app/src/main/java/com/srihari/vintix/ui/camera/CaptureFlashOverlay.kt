package com.srihari.vintix.ui.camera

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * Full-screen white flash overlay that fades out quickly,
 * providing visual feedback when a photo is captured.
 */
@Composable
fun CaptureFlashOverlay(
    trigger: Boolean,
    durationMs: Int,
    onAnimationComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(trigger) {
        if (trigger) {
            if (durationMs > 0) {
                alpha.snapTo(0.6f)
                alpha.animateTo(
                    targetValue = 0f,
                    animationSpec = tween(durationMillis = durationMs)
                )
            }
            onAnimationComplete()
        }
    }

    if (alpha.value > 0f) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .background(Color.White.copy(alpha = alpha.value))
        )
    }
}
