package com.srihari.vintix.rendering.timestamp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Utility to draw retro timestamps onto exported photos.
 */
object TimestampRenderer {

    /**
     * Applies the given [style] timestamp to the [bitmap].
     * If the bitmap is mutable, it draws directly on it to save memory.
     * Otherwise, it creates a mutable copy.
     */
    fun applyTimestamp(bitmap: Bitmap, style: TimestampStyle, date: Date = Date()): Bitmap {
        val output = if (bitmap.isMutable) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, true)
        }

        val canvas = Canvas(output)
        
        // Calculate dynamic dimensions based on image resolution
        val height = output.height.toFloat()
        val width = output.width.toFloat()
        val fontSize = height * style.sizeRatio
        val margin = height * style.marginRatio

        // Setup Paint
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.textColor
            textSize = fontSize
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD) // Classic digital look
            if (style.shadowRadius > 0) {
                setShadowLayer(style.shadowRadius, 0f, 0f, style.shadowColor)
            }
        }

        // Format Date
        val formatter = SimpleDateFormat(style.dateFormat, Locale.US)
        val text = formatter.format(date)

        // Measure Text
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val textWidth = bounds.width().toFloat()
        val textHeight = bounds.height().toFloat()

        // Calculate Position
        val x: Float
        val y: Float
        when (style.corner) {
            TimestampCorner.BOTTOM_RIGHT -> {
                x = width - margin - textWidth
                y = height - margin
            }
            TimestampCorner.BOTTOM_LEFT -> {
                x = margin
                y = height - margin
            }
            TimestampCorner.TOP_RIGHT -> {
                x = width - margin - textWidth
                y = margin + textHeight
            }
            TimestampCorner.TOP_LEFT -> {
                x = margin
                y = margin + textHeight
            }
        }

        // Draw Timestamp
        canvas.drawText(text, x, y, paint)

        return output
    }
}
