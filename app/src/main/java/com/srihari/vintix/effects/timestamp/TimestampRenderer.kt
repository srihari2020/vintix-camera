package com.srihari.vintix.effects.timestamp

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TimestampRenderer {
    fun applyTimestamp(bitmap: Bitmap, style: TimestampStyle, date: Date = Date()): Bitmap {
        val output = if (bitmap.isMutable) {
            bitmap
        } else {
            bitmap.copy(Bitmap.Config.ARGB_8888, true)
        }

        val canvas = Canvas(output)
        val height = output.height.toFloat()
        val width = output.width.toFloat()
        val fontSize = height * style.sizeRatio
        val margin = height * style.marginRatio

        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = style.textColor
            textSize = fontSize
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            if (style.shadowRadius > 0f) {
                setShadowLayer(style.shadowRadius, 0f, 0f, style.shadowColor)
            }
        }

        val text = SimpleDateFormat(style.dateFormat, Locale.US).format(date)
        val bounds = Rect()
        paint.getTextBounds(text, 0, text.length, bounds)
        val textWidth = bounds.width().toFloat()
        val textHeight = bounds.height().toFloat()

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

        canvas.drawText(text, x, y, paint)
        return output
    }
}
