package com.srihari.vintix.effects.timestamp

import android.graphics.Color

enum class TimestampCorner {
    BOTTOM_RIGHT,
    BOTTOM_LEFT,
    TOP_RIGHT,
    TOP_LEFT,
}

data class TimestampStyle(
    val textColor: Int,
    val dateFormat: String,
    val sizeRatio: Float = 0.035f,
    val marginRatio: Float = 0.04f,
    val corner: TimestampCorner = TimestampCorner.BOTTOM_RIGHT,
    val shadowColor: Int = Color.argb(180, 0, 0, 0),
    val shadowRadius: Float = 6f,
)

object TimestampStyles {
    val ClassicOrange = TimestampStyle(
        textColor = Color.rgb(255, 140, 0),
        dateFormat = "''yy MM dd",
        sizeRatio = 0.038f,
        marginRatio = 0.05f,
        corner = TimestampCorner.BOTTOM_RIGHT,
        shadowColor = Color.argb(120, 255, 100, 0),
        shadowRadius = 8f,
    )

    val None: TimestampStyle? = null
}
