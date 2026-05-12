package com.srihari.vintix.rendering.timestamp

import android.graphics.Color

/**
 * Defines the corner where the timestamp will be placed.
 */
enum class TimestampCorner {
    BOTTOM_RIGHT,
    BOTTOM_LEFT,
    TOP_RIGHT,
    TOP_LEFT
}

/**
 * Configuration for the retro timestamp overlay applied to exported photos.
 */
data class TimestampStyle(
    /** Color of the text (e.g. vibrant orange for classic digicams). */
    val textColor: Int,
    /** Date format string (e.g., "yy MM dd", "yyyy/MM/dd"). */
    val dateFormat: String,
    /** Font size as a ratio of the image height to remain resolution-independent. */
    val sizeRatio: Float = 0.035f,
    /** Margin from the edge as a ratio of the image height. */
    val marginRatio: Float = 0.04f,
    /** Corner to anchor the text. */
    val corner: TimestampCorner = TimestampCorner.BOTTOM_RIGHT,
    /** Shadow color (can add a slight glow or readability drop shadow). */
    val shadowColor: Int = Color.argb(180, 0, 0, 0),
    /** Shadow radius. */
    val shadowRadius: Float = 6f
)

/**
 * Predefined timestamp aesthetics.
 */
object TimestampStyles {
    /** The classic early 2000s bright orange digital date format: 'yy MM dd */
    val ClassicOrange = TimestampStyle(
        textColor = Color.rgb(255, 140, 0), // Retro orange
        dateFormat = "''yy MM dd", // Single quote escapes, outputs: '98 10 24
        sizeRatio = 0.038f,
        marginRatio = 0.05f,
        corner = TimestampCorner.BOTTOM_RIGHT,
        shadowColor = Color.argb(120, 255, 100, 0), // Slight orange glow
        shadowRadius = 8f
    )
    
    /** No timestamp overlay. */
    val None: TimestampStyle? = null
}
