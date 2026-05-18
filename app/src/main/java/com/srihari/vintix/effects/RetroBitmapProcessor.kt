package com.srihari.vintix.effects

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.srihari.vintix.effects.timestamp.TimestampRenderer
import com.srihari.vintix.effects.timestamp.TimestampStyle
import java.io.ByteArrayOutputStream
import java.util.Date
import kotlin.math.abs
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

        var next = applyOpticalDiffusion(working, activeProfile)
        if (next !== working && working !== original) working.recycle()
        working = next

        working = applyToneColorNoiseAndVignette(working, activeProfile)
        working = applyHighlightBloom(working, activeProfile)
        working = applyCcdStreaks(working, activeProfile)
        working = applyCamcorderSmear(working, activeProfile.horizontalSmear)
        working = applyScanlines(working, activeProfile.scanlineStrength)
        working = applySharpen(working, activeProfile.sharpen)
        working = applyCompressionBreakup(working, activeProfile)

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

    private fun applyOpticalDiffusion(source: Bitmap, profile: CameraProfile): Bitmap {
        if (profile.softness <= 0.01f) return source
        val scale = (1f - profile.softness * 0.35f).coerceIn(0.50f, 0.98f)
        val smallW = max(1, (source.width * scale).roundToInt())
        val smallH = max(1, (source.height * scale).roundToInt())
        val small = Bitmap.createScaledBitmap(source, smallW, smallH, true)
        val diffused = Bitmap.createScaledBitmap(small, source.width, source.height, true)
        small.recycle()

        val width = source.width
        val height = source.height
        val originalPixels = IntArray(width * height)
        val diffusePixels = IntArray(width * height)
        source.getPixels(originalPixels, 0, width, 0, 0, width, height)
        diffused.getPixels(diffusePixels, 0, width, 0, 0, width, height)
        val amount = profile.softness.coerceIn(0f, 1f) * 0.45f

        val centerX = (width - 1) * 0.5f
        val centerY = (height - 1) * 0.5f
        val maxDistance = sqrt(centerX * centerX + centerY * centerY).coerceAtLeast(1f)

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val index = row + x
                val base = originalPixels[index]
                val blur = diffusePixels[index]
                val r = (base ushr 16) and 0xff
                val g = (base ushr 8) and 0xff
                val b = base and 0xff
                val luma = luminance255(r, g, b)

                val dx = x - centerX
                val dy = y - centerY
                val distance = sqrt(dx * dx + dy * dy) / maxDistance

                // Edge falloff (plastic lens softness at edges)
                val edgeSoftness = smoothstep(0.4f, 1.0f, distance)

                // Highlight bloom softness
                val highlightSoftness = smoothstep(0.65f, 1.0f, luma)

                // Blend them, keeping center details sharp if not a highlight
                val mask = max(edgeSoftness * 0.8f, highlightSoftness)

                originalPixels[index] = blend(base, blur, amount * mask)
            }
        }

        source.setPixels(originalPixels, 0, width, 0, 0, width, height)
        diffused.recycle()
        return source
    }

    private fun applyToneColorNoiseAndVignette(source: Bitmap, profile: CameraProfile): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val centerX = (width - 1) * 0.5f
        val centerY = (height - 1) * 0.5f
        val maxDistance = sqrt(centerX * centerX + centerY * centerY).coerceAtLeast(1f)
        val seed = (System.nanoTime() xor (width.toLong() shl 21) xor height.toLong()).toInt()
        val coarseSize = (1 + profile.grainCoarseness * 7f).roundToInt().coerceIn(1, 8)

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

                var luma = luminance(r, g, b)
                val saturation = profile.saturation
                r = luma + (r - luma) * saturation
                g = luma + (g - luma) * saturation
                b = luma + (b - luma) * saturation

                r = contrast(r, profile.contrast)
                g = contrast(g, profile.contrast)
                b = contrast(b, profile.contrast)
                luma = luminance(r, g, b)

                val shadow = 1f - smoothstep(0.10f, 0.50f, luma)
                val mid = smoothstep(0.18f, 0.78f, luma) * (1f - smoothstep(0.78f, 1.0f, luma))
                val highlight = smoothstep(0.64f, 1.0f, luma)

                val fadeLift = profile.fadedBlacks * shadow * 0.18f
                val hazeLift = profile.haze * (0.08f + shadow * 0.08f)
                val pastel = profile.pastelLift * highlight

                r = r - profile.shadowCrush * shadow * 0.12f + fadeLift + hazeLift
                g = g - profile.shadowCrush * shadow * 0.11f + fadeLift + hazeLift
                b = b - profile.shadowCrush * shadow * 0.10f + fadeLift + hazeLift

                r += profile.warmth * (0.052f + mid * 0.030f)
                g += profile.warmth * 0.010f
                b -= profile.warmth * 0.050f

                r += profile.magentaShift * 0.040f
                b += profile.magentaShift * 0.054f
                g += profile.cyanShift * 0.034f
                b += profile.cyanShift * 0.034f

                r += shadow * profile.shadowBlue * -0.030f
                g += shadow * profile.shadowGreen * 0.036f
                b += shadow * profile.shadowBlue * 0.070f
                r += highlight * profile.highlightRed * 0.075f
                g += highlight * profile.highlightGreen * 0.060f
                b += highlight * profile.highlightBlue * 0.075f

                b += profile.coolHighlights * highlight * 0.090f
                r -= profile.coolHighlights * highlight * 0.035f
                r += pastel * 0.08f
                g += pastel * 0.08f
                b += pastel * 0.08f

                val dx = x - centerX
                val dy = y - centerY
                val distance = sqrt(dx * dx + dy * dy) / maxDistance
                val vignette = 1f - profile.vignette * smoothstep(0.42f, 0.98f, distance)
                val flash = profile.flashBurn *
                    (1f - smoothstep(0.0f, 0.72f, distance)) *
                    (0.18f + highlight * 0.82f)

                r = r * vignette + flash * (0.15f + profile.warmth * 0.04f)
                g = g * vignette + flash * 0.12f
                b = b * vignette + flash * 0.08f

                val fineNoise = noise(seed, x, y, 0) - 0.5f
                val coarseNoise = noise(seed, x / coarseSize, y / coarseSize, 4) - 0.5f
                val clusterNoise = noise(seed, x / (coarseSize * 2), y / (coarseSize * 2), 5) - 0.5f
                
                // Sensor noise shape: exponential curve based on luma to crush darks with noise
                val shadowNoiseMask = (1f - luma).coerceIn(0f, 1f)
                val shadowNoiseMaskSquared = shadowNoiseMask * shadowNoiseMask
                val noiseShape = 0.010f + shadowNoiseMaskSquared * 0.220f + (1f - highlight) * 0.015f
                
                val monoNoise = (fineNoise * (1f - profile.grainCoarseness * 0.65f) + 
                                 coarseNoise * profile.grainCoarseness * 0.45f +
                                 clusterNoise * profile.grainCoarseness * 0.2f) * 
                                 profile.grain * noiseShape
                                 
                val chromaStrength = profile.chromaNoise * (0.005f + shadowNoiseMaskSquared * 0.16f)
                
                // Low light RGB instability (blotches of purple/green in extreme shadows)
                val instabilityR = (noise(seed, x / 4, y / 4, 1) - 0.5f) * chromaStrength * 1.4f
                val instabilityG = (noise(seed, x / 4, y / 4, 2) - 0.5f) * chromaStrength * 0.8f
                val instabilityB = (noise(seed, x / 4, y / 4, 3) - 0.5f) * chromaStrength * 1.6f

                r += monoNoise + instabilityR
                g += monoNoise + instabilityG
                b += monoNoise + instabilityB

                val clipPoint = (1f - profile.highlightClip * 0.18f).coerceIn(0.80f, 1f)
                r = clipHighlight(r, clipPoint)
                g = clipHighlight(g, clipPoint)
                b = clipHighlight(b, clipPoint)

                pixels[index] = pack(alpha, r, g, b)
            }
        }

        source.setPixels(pixels, 0, width, 0, 0, width, height)
        return source
    }

    private fun applyHighlightBloom(source: Bitmap, profile: CameraProfile): Bitmap {
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
            val highlight = smoothstep(profile.bloomThreshold, profile.bloomThreshold + 0.15f, luminance(r, g, b))
            val alpha = (highlight * profile.bloom * 185f).roundToInt().coerceIn(0, 220)
            val red = (246 + profile.bloomWarmth * 34f + profile.highlightRed * 24f - profile.coolHighlights * 12f)
                .roundToInt()
                .coerceIn(0, 255)
            val green = (244 + profile.bloomWarmth * 18f + profile.highlightGreen * 20f + profile.coolHighlights * 2f)
                .roundToInt()
                .coerceIn(0, 255)
            val blue = (232 - profile.bloomWarmth * 30f + profile.highlightBlue * 28f + profile.coolHighlights * 36f)
                .roundToInt()
                .coerceIn(0, 255)
            maskPixels[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }

        val mask = Bitmap.createBitmap(maskPixels, width, height, Bitmap.Config.ARGB_8888)
        val divisor = (12f - profile.bloomSpread * 8f).roundToInt().coerceIn(3, 12)
        val smallW = max(1, width / divisor)
        val smallH = max(1, height / divisor)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val small = Bitmap.createScaledBitmap(mask, smallW, smallH, true)
        val bloom = Bitmap.createScaledBitmap(small, width, height, true)
        Canvas(source).drawBitmap(bloom, 0f, 0f, paint)
        mask.recycle()
        small.recycle()
        bloom.recycle()
        return source
    }

    private fun applyCcdStreaks(source: Bitmap, profile: CameraProfile): Bitmap {
        if (profile.ccdStreak <= 0.01f) return source
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val decay = (0.88f + profile.ccdStreak * 0.09f).coerceIn(0.88f, 0.97f)

        for (y in 0 until height) {
            var trail = 0f
            val row = y * width
            for (x in 0 until width) {
                val index = row + x
                val color = pixels[index]
                val luma = luminance255((color ushr 16) and 0xff, (color ushr 8) and 0xff, color and 0xff)
                val energy = smoothstep(0.74f, 1.0f, luma) * profile.ccdStreak
                trail = max(energy, trail * decay)
                if (trail > 0.01f) pixels[index] = addGlow(color, trail * 0.18f, profile)
            }
            trail = 0f
            for (x in width - 1 downTo 0) {
                val index = row + x
                val color = pixels[index]
                val luma = luminance255((color ushr 16) and 0xff, (color ushr 8) and 0xff, color and 0xff)
                val energy = smoothstep(0.74f, 1.0f, luma) * profile.ccdStreak
                trail = max(energy, trail * decay)
                if (trail > 0.01f) pixels[index] = addGlow(color, trail * 0.14f, profile)
            }
        }

        source.setPixels(pixels, 0, width, 0, 0, width, height)
        return source
    }

    private fun applyCamcorderSmear(source: Bitmap, strength: Float): Bitmap {
        if (strength <= 0.01f) return source
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val amount = strength.coerceIn(0f, 1f) * 0.34f

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

    private fun applyScanlines(source: Bitmap, strength: Float): Bitmap {
        if (strength <= 0.01f) return source
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val darken = 1f - strength.coerceIn(0f, 1f) * 0.16f

        for (y in 1 until height step 2) {
            val row = y * width
            for (x in 0 until width) {
                val index = row + x
                val color = pixels[index]
                val alpha = color ushr 24
                val r = (((color ushr 16) and 0xff) * darken).roundToInt()
                val g = (((color ushr 8) and 0xff) * darken).roundToInt()
                val b = ((color and 0xff) * darken).roundToInt()
                pixels[index] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
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
        val amount = strength.coerceIn(0f, 1f) * 0.78f

        for (index in sourcePixels.indices) {
            sourcePixels[index] = unsharp(sourcePixels[index], blurPixels[index], amount)
        }

        source.setPixels(sourcePixels, 0, width, 0, 0, width, height)
        blurred.recycle()
        return source
    }

    private fun applyCompressionBreakup(source: Bitmap, profile: CameraProfile): Bitmap {
        if (profile.macroblockStrength <= 0.01f && profile.chromaSmear <= 0.01f && profile.mosquitoNoise <= 0.01f) {
            return source
        }
        var working = applyMacroblocks(source, profile)
        working = applyChromaSmear(working, profile.chromaSmear)
        working = applyMosquitoNoise(working, profile)
        return working
    }

    private fun applyMacroblocks(source: Bitmap, profile: CameraProfile): Bitmap {
        if (profile.macroblockStrength <= 0.01f) return source
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val blockSize = (8 + profile.macroblockStrength * 16f).roundToInt().coerceIn(8, 24)
        val blendAmount = (profile.macroblockStrength * 0.85f).coerceIn(0f, 0.95f)

        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                val blockW = minOf(blockSize, width - x)
                val blockH = minOf(blockSize, height - y)
                var sumR = 0
                var sumG = 0
                var sumB = 0
                var count = 0

                for (by in 0 until blockH) {
                    val row = (y + by) * width
                    for (bx in 0 until blockW) {
                        val color = pixels[row + x + bx]
                        sumR += (color ushr 16) and 0xff
                        sumG += (color ushr 8) and 0xff
                        sumB += color and 0xff
                        count++
                    }
                }

                val blockColor = (0xff shl 24) or
                    ((sumR / count) shl 16) or
                    ((sumG / count) shl 8) or
                    (sumB / count)

                for (by in 0 until blockH) {
                    val row = (y + by) * width
                    for (bx in 0 until blockW) {
                        val index = row + x + bx
                        pixels[index] = blend(pixels[index], blockColor, blendAmount)
                    }
                }
                x += blockSize
            }
            y += blockSize
        }

        source.setPixels(pixels, 0, width, 0, 0, width, height)
        return source
    }

    private fun applyChromaSmear(source: Bitmap, strength: Float): Bitmap {
        if (strength <= 0.01f) return source
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        val original = IntArray(width * height)
        source.getPixels(original, 0, width, 0, 0, width, height)
        val offset = (1 + strength * 8f).roundToInt().coerceIn(1, 12)
        val amount = (strength * 0.70f).coerceIn(0f, 0.90f)

        for (y in 0 until height) {
            val row = y * width
            for (x in 0 until width) {
                val index = row + x
                val base = original[index]
                val left = original[row + (x - offset).coerceAtLeast(0)]
                val right = original[row + (x + offset).coerceAtMost(width - 1)]
                val alpha = base ushr 24
                val r = lerp((base ushr 16) and 0xff, (right ushr 16) and 0xff, amount)
                val g = (base ushr 8) and 0xff
                val b = lerp(base and 0xff, left and 0xff, amount)
                pixels[index] = (alpha shl 24) or (r shl 16) or (g shl 8) or b
            }
        }

        source.setPixels(pixels, 0, width, 0, 0, width, height)
        return source
    }

    private fun applyMosquitoNoise(source: Bitmap, profile: CameraProfile): Bitmap {
        if (profile.mosquitoNoise <= 0.01f) return source
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val seed = (width * 31 + height * 17 + System.nanoTime()).toInt()
        val amount = profile.mosquitoNoise.coerceIn(0f, 1f) * 0.08f

        for (y in 1 until height - 1) {
            val row = y * width
            for (x in 1 until width - 1) {
                val index = row + x
                val color = pixels[index]
                val l = lumaOf(color)
                val edge = maxOf(
                    abs(l - lumaOf(pixels[index - 1])),
                    abs(l - lumaOf(pixels[index + 1])),
                    abs(l - lumaOf(pixels[index - width])),
                    abs(l - lumaOf(pixels[index + width])),
                )
                val edgeMask = smoothstep(0.04f, 0.20f, edge)
                if (edgeMask > 0f) {
                    val n = (noise(seed, x, y, 11) - 0.5f) * amount * edgeMask * 2.5f
                    val c = (noise(seed, x, y, 12) - 0.5f) * amount * edgeMask * 3.0f
                    val alpha = color ushr 24
                    val r = (((color ushr 16) and 0xff) / 255f) + n + c
                    val g = (((color ushr 8) and 0xff) / 255f) + n * 0.6f
                    val b = ((color and 0xff) / 255f) + n - c
                    pixels[index] = pack(alpha, r, g, b)
                }
            }
        }

        source.setPixels(pixels, 0, width, 0, 0, width, height)
        return source
    }

    private fun simulateJpegArtifacts(source: Bitmap, profile: CameraProfile): Bitmap {
        if (profile.jpegArtifacts <= 0.04f) return source
        val quality = (profile.jpegQuality - profile.jpegArtifacts * 34f)
            .roundToInt()
            .coerceIn(32, 94)
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

    private fun addGlow(color: Int, amount: Float, profile: CameraProfile): Int {
        val alpha = color ushr 24
        val r = (((color ushr 16) and 0xff) / 255f) + amount * (0.60f + profile.highlightRed)
        val g = (((color ushr 8) and 0xff) / 255f) + amount * (0.44f + profile.highlightGreen)
        val b = ((color and 0xff) / 255f) + amount * (0.50f + profile.highlightBlue + profile.coolHighlights * 0.4f)
        return pack(alpha, r, g, b)
    }

    private fun luminance(r: Float, g: Float, b: Float): Float {
        return r * 0.2126f + g * 0.7152f + b * 0.0722f
    }

    private fun luminance255(r: Int, g: Int, b: Int): Float {
        return (r * 0.2126f + g * 0.7152f + b * 0.0722f) / 255f
    }

    private fun lumaOf(color: Int): Float {
        return luminance255((color ushr 16) and 0xff, (color ushr 8) and 0xff, color and 0xff)
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

    private fun lerp(a: Int, b: Int, amount: Float): Int {
        return (a + (b - a) * amount).roundToInt().coerceIn(0, 255)
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
