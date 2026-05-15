package com.srihari.vintix.ui.camera

import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp

private const val TAG = "ShutterButton"

/**
 * Classic camera shutter button — outer ring with inner filled circle.
 * Provides a scale-down press animation for tactile feedback.
 */
@Composable
fun ShutterButton(
    onClick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    var isPressed by remember { mutableStateOf(false) }

    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.85f else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "shutter_scale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(80.dp)
            .scale(scale)
            .border(
                width = 4.dp,
                color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
                shape = CircleShape
            )
            .pointerInput(enabled) {
                if (enabled) {
                    detectTapGestures(
                        onPress = {
                            Log.d(TAG, "SHUTTER PRESSED (enabled=$enabled)")
                            isPressed = true
                            val released = tryAwaitRelease()
                            isPressed = false
                            if (released) {
                                Log.d(TAG, "SHUTTER RELEASED — firing onClick")
                                onClick()
                            } else {
                                Log.d(TAG, "SHUTTER press cancelled")
                            }
                        }
                    )
                }
            }
    ) {
        Surface(
            shape = CircleShape,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.4f),
            modifier = Modifier.size(64.dp)
        ) {}
    }
}
