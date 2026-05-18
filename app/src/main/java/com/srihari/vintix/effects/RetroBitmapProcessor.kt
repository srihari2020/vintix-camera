package com.srihari.vintix.effects

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.srihari.vintix.effects.timestamp.TimestampRenderer
import com.srihari.vintix.effects.timestamp.TimestampStyle
import java.io.ByteArrayOutputStream
import java.util.Date
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

object RetroBitmapProcessor {
    fun process(
        input: Bitmap,
        profile: CameraProfile,
        timestampStyle: TimestampStyle?,
        captureDate: Date = Date(),
    ): Bitmap {
        val activeProfile = profile.applyInstability()
        val original = input
        var working = ensureArgb8888(input)

        var next = applySoftness(working, activeProfile.softness)
        if (next !== working && working !== original) working.recycle()
        working = next

        working = applyColorNoiseAndVignette(working, activeProfile)
        working = applyBloom(working, activeProfile)
        working = applyCamcorderSmear(working, activeProfile.horizontalSmear)
        working = applySharpen(working, activeProfile.sharpen)

        if (timestampStyle != null) {
            next = TimestampRenderer.applyTimestamp(working, timestampStyle, captureDate)
            if (next !== working && working !== original) working.recycle()
            working = next
        }

        next = simulateJpegArtifacts(working, activeProfile)
        if (next !== working && working !== original) working.recycle()
        return next
    }

    private fun ensureArgb8888(input: Bitmap): Bitmap {
        if (input.config == Bitmap.Config.ARGB_8888 && input.isMutable) return input
        return input.copy(Bitmap.Config.ARGB_8888, true)
    }

    private fun applySoftness(source: Bitmap, strength: Float): Bitmap {
        if (strength <= 0.01f) return source
        val scale = (1f - strength * 0.34f).coerceIn(0.55f, 0.96f)
        val smallW = max(1, (source.width * scale).roundToInt())
        val smallH = max(1, (source.height * scale).roundToInt())
        val small = Bitmap.createScaledBitmap(source, smallW, smallH, true)
        val softened = Bitmap.createScaledBitmap(small, source.width, source.height, true)
        small.recycle()
        return softened.copy(Bitmap.Config.ARGB_8888, true)
    }

    private fun applyColorNoiseAndVignette(source: Bitmap, profile: CameraProfile): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val centerX = (width - 1) * 0.5f
        val centerY = (height - 1) * 0.5f
        val maxDistance = sqrt(centerX * centerX + centerY * centerY).coerceAtLeast(1f)
        val seed = (System.nanoTime() xor (width.toLong() shl 21) xor height.toLong()).toInt()

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val index = row + x
                val color = pixels[index]
                val alpha = color ushr 24
                var r = ((color ushr 16) and 0xff) / 255f
                var g = ((color ushr 8) and 0xff) / 255f
                var b = (color and 0xff) / 255f

                r *= profile.exposure
                g *= profile.exposure
                b *= profile.exposure

                val luminance = luminance(r, g, b)
                val saturatedR = luminance + (r - luminance) * profile.saturation
                val saturatedG = luminance + (g - luminance) * profile.saturation
                val saturatedB = luminance + (b - luminance) * profile.saturation

                r = contrast(saturatedR, profile.contrast)
                g = contrast(saturatedG, profile.contrast)
                b = contrast(saturatedB, profile.contrast)

                r += profile.warmth * 0.055f
                g += profile.warmth * 0.012f
                b -= profile.warmth * 0.050f

                r += profile.magentaShift * 0.035f
                b += profile.magentaShift * 0.050f
                g += profile.cyanShift * 0.035f
                b += profile.cyanShift * 0.030f

                val updatedLuma = luminance(r, g, b)
                val shadow = 1f - smoothstep(0.12f, 0.48f, updatedLuma)
                val highlight = smoothstep(0.70f, 1.0f, updatedLuma)
                val fadeLift = profile.fadedBlacks * shadow * 0.18f

                r = r - profile.shadowCrush * shadow * 0.12f + fadeLift
                g = g - profile.shadowCrush * shadow * 0.11f + fadeLift
                b = b - profile.shadowCrush * shadow * 0.10f + fadeLift

                r += profile.highlightClip * highlight * 0.10f
                g += profile.highlightClip * highlight * 0.10f
                b += profile.highlightClip * highlight * 0.08f
                b += profile.coolHighlights * highlight * 0.10f
                r -= profile.coolHighlights * highlight * 0.04f

                val dx = x - centerX
                val dy = y - centerY
                val distance = sqrt(dx * dx + dy * dy) / maxDistance
                val vignette = 1f - profile.vignette * smoothstep(0.40f, 0.98f, distance)
                val flash = profile.flashBurn * (1f - smoothstep(0.0f, 0.70f, distance)) * highlight.coerceAtLeast(0.25f)

                r = r * vignette + flash * 0.14f
                g = g * vignette + flash * 0.12f
                b = b * vignette + flash * 0.08f

                val monoNoise = (noise(seed, x, y, 0) - 0.5f) * profile.grain * (0.035f + shadow * 0.085f)
                val chromaStrength = profile.chromaNoise * (0.018f + shadow * 0.052f)
                r += monoNoise + (noise(seed, x, y, 1) - 0.5f) * chromaStrength
                g += monoNoise + (noise(seed, x, y, 2) - 0.5f) * chromaStrength * 0.7f
                b += monoNoise + (noise(seed, x, y, 3) - 0.5f) * chromaStrength

                val clipPoint = (1f - profile.highlightClip * 0.18f).coerceIn(0.82f, 1f)
                r = clipHighlight(r, clipPoint)
                g = clipHighlight(g, clipPoint)
                b = clipHighlight(b, clipPoint)

                pixels[index] = pack(alpha, r, g, b)
            }
        }

        source.setPixels(pixels, 0, width, 0, 0, width, height)
        return source
    }

    private fun applyBloom(source: Bitmap, profile: CameraProfile): Bitmap {
        if (profile.bloom <= 0.01f) return source
        val width = source.width
        val height = source.height
        val sourcePixels = IntArray(width * height)
        val maskPixels = IntArray(width * height)
        source.getPixels(sourcePixels, 0, width, 0, 0, width, height)

        for (index in sourcePixels.indices) {
            val color = sourcePixels[index]
            val r = ((color ushr 16) and 0xff) / 255f
            val g = ((color ushr 8) and 0xff) / 255f
            val b = (color and 0xff) / 255f
            val highlight = smoothstep(0.70f, 1.0f, luminance(r, g, b))
            val alpha = (highlight * profile.bloom * 155f).roundToInt().coerceIn(0, 180)
            val warm = profile.warmth.coerceIn(-1f, 1f)
            val red = (248 + warm * 18f - profile.coolHighlights * 18f).roundToInt().coerceIn(0, 255)
            val green = (246 + warm * 8f + profile.coolHighlights * 4f).roundToInt().coerceIn(0, 255)
            val blue = (235 - warm * 20f + profile.coolHighlights * 32f).roundToInt().coerceIn(0, 255)
            maskPixels[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }

        val mask = Bitmap.createBitmap(maskPixels, width, height, Bitmap.Config.ARGB_8888)
        val smallW = max(1, width / 9)
        val smallH = max(1, height / 9)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val small = Bitmap.createScaledBitmap(mask, smallW, smallH, true)
        val bloom = Bitmap.createScaledBitmap(small, width, height, true)
        Canvas(source).drawBitmap(bloom, 0f, 0f, paint)
        mask.recycle()
        small.recycle()
        bloom.recycle()
        return source
    }

    private fun applyCamcorderSmear(source: Bitmap, strength: Float): Bitmap {
        if (strength <= 0.01f) return source
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val amount = strength.coerceIn(0f, 1f) * 0.42f

        for (y in 1 until height step 2) {
            val row = y * width
            val previous = (y - 1) * width
            for (x in 0 until width) {
                val index = row + x
                pixels[index] = blend(pixels[index], pixels[previous + x], amount)
            }
        }

        source.setPixels(pixels, 0, width, 0, 0, width, height)
        return source
    }

    private fun applySharpen(source: Bitmap, strength: Float): Bitmap {
        if (strength <= 0.01f) return source
        val width = source.width
        val height = source.height
        val halfW = max(1, width / 2)
        val halfH = max(1, height / 2)
        val small = Bitmap.createScaledBitmap(source, halfW, halfH, true)
        val blurred = Bitmap.createScaledBitmap(small, width, height, true)
        small.recycle()

        val sourcePixels = IntArray(width * height)
        val blurPixels = IntArray(width * height)
        source.getPixels(sourcePixels, 0, width, 0, 0, width, height)
        blurred.getPixels(blurPixels, 0, width, 0, 0, width, height)
        val amount = strength.coerceIn(0f, 1f) * 0.85f

        for (index in sourcePixels.indices) {
            sourcePixels[index] = unsharp(sourcePixels[index], blurPixels[index], amount)
        }

        source.setPixels(sourcePixels, 0, width, 0, 0, width, height)
        blurred.recycle()
        return source
    }

    private fun simulateJpegArtifacts(source: Bitmap, profile: CameraProfile): Bitmap {
        if (profile.jpegArtifacts <= 0.05f) return source
        val quality = (profile.jpegQuality - profile.jpegArtifacts * 34f)
            .roundToInt()
            .coerceIn(34, 92)
        val bytes = ByteArrayOutputStream()
        source.compress(Bitmap.CompressFormat.JPEG, quality, bytes)
        val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes.toByteArray(), 0, bytes.size())
        return decoded?.copy(Bitmap.Config.ARGB_8888, true) ?: source
    }

    private fun contrast(value: Float, contrast: Float): Float {
        return (value - 0.5f) * contrast + 0.5f
    }

    private fun clipHighlight(value: Float, clipPoint: Float): Float {
        if (value <= clipPoint) return value
        val excess = (value - clipPoint) / (1f - clipPoint).coerceAtLeast(0.001f)
        return clipPoint + smoothstep(0f, 1f, excess) * (1f - clipPoint)
    }

    private fun luminance(r: Float, g: Float, b: Float): Float {
        return r * 0.2126f + g * 0.7152f + b * 0.0722f
    }

    private fun smoothstep(edge0: Float, edge1: Float, value: Float): Float {
        val t = ((value - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun noise(seed: Int, x: Int, y: Int, channel: Int): Float {
        var n = seed
        n = n xor (x * 374761393)
        n = n xor (y * 668265263)
        n = n xor (channel * 1442695041)
        n = (n xor (n ushr 13)) * 1274126177
        n = n xor (n ushr 16)
        return (n and 0x7fffffff) / 2147483647f
    }

    private fun pack(alpha: Int, r: Float, g: Float, b: Float): Int {
        val red = (r.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val green = (g.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        val blue = (b.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun blend(a: Int, b: Int, amount: Float): Int {
        val inv = 1f - amount
        val alpha = a ushr 24
        val red = (((a ushr 16) and 0xff) * inv + ((b ushr 16) and 0xff) * amount).roundToInt()
        val green = (((a ushr 8) and 0xff) * inv + ((b ushr 8) and 0xff) * amount).roundToInt()
        val blue = ((a and 0xff) * inv + (b and 0xff) * amount).roundToInt()
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun unsharp(original: Int, blurred: Int, amount: Float): Int {
        val alpha = original ushr 24
        val red = sharpenChannel((original ushr 16) and 0xff, (blurred ushr 16) and 0xff, amount)
        val green = sharpenChannel((original ushr 8) and 0xff, (blurred ushr 8) and 0xff, amount)
        val blue = sharpenChannel(original and 0xff, blurred and 0xff, amount)
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    private fun sharpenChannel(original: Int, blurred: Int, amount: Float): Int {
        return (original + (original - blurred) * amount).roundToInt().coerceIn(0, 255)
    }
}
