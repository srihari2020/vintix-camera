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

enum class ProcessingMode {
    /** Full sensor pipeline including JPEG round-trip. */
    CAPTURE,
    /** Fast path for filter selector thumbnails — skips heavy JPEG encode/decode. */
    PREVIEW,
}

object RetroBitmapProcessor {
    private val bloomPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val jpegStream = ThreadLocal.withInitial { ByteArrayOutputStream(256 * 1024) }

    fun process(
        input: Bitmap,
        profile: CameraProfile,
        timestampStyle: TimestampStyle?,
        captureDate: Date = Date(),
        mode: ProcessingMode = ProcessingMode.CAPTURE,
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
        working = applyCompressionBreakup(working, activeProfile, mode)

        if (timestampStyle != null && mode == ProcessingMode.CAPTURE) {
            next = TimestampRenderer.applyTimestamp(working, timestampStyle, captureDate)
            if (next !== working && working !== original) working.recycle()
            working = next
        }

        next = simulateJpegArtifacts(working, activeProfile, mode)
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
        val maxDistanceSq = (centerX * centerX + centerY * centerY).coerceAtLeast(1f)

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
                val distance = sqrt((dx * dx + dy * dy) / maxDistanceSq)

                val edgeSoftness = smoothstep(0.4f, 1.0f, distance)
                val highlightSoftness = smoothstep(0.65f, 1.0f, luma)
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
        val maxDistanceSq = (centerX * centerX + centerY * centerY).coerceAtLeast(1f)
        val seed = (System.nanoTime() xor (width.toLong() shl 21) xor height.toLong()).toInt()
        val coarseSize = (1 + profile.grainCoarseness * 7f).roundToInt().coerceIn(1, 8)
        val crawlPhase = ((seed ushr 8) and 0x7).toFloat()

        val vignetteFactors = FloatArray(width * height)
        val flashDistanceFactors = FloatArray(width * height)
        for (y in 0 until height) {
            val row = y * width
            val dy = y - centerY
            for (x in 0 until width) {
                val dx = x - centerX
                val distance = sqrt((dx * dx + dy * dy) / maxDistanceSq)
                val index = row + x
                vignetteFactors[index] = 1f - profile.vignette * smoothstep(0.42f, 0.98f, distance)
                flashDistanceFactors[index] = profile.flashBurn * (1f - smoothstep(0.0f, 0.72f, distance))
            }
        }

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

                val fadeLift = profile.fadedBlacks * shadow * 0.22f
                val hazeLift = profile.haze * (0.10f + shadow * 0.10f)
                val pastel = profile.pastelLift * highlight

                r = r - profile.shadowCrush * shadow * 0.16f + fadeLift + hazeLift
                g = g - profile.shadowCrush * shadow * 0.14f + fadeLift + hazeLift
                b = b - profile.shadowCrush * shadow * 0.12f + fadeLift + hazeLift

                // Dirty blacks — lift then crush for ugly rolloff
                val blackLift = profile.fadedBlacks * shadow * 0.08f
                r += blackLift - shadow * profile.shadowCrush * 0.04f
                g += blackLift - shadow * profile.shadowCrush * 0.03f
                b += blackLift - shadow * profile.shadowCrush * 0.02f

                r += profile.warmth * (0.058f + mid * 0.034f)
                g += profile.warmth * 0.008f
                b -= profile.warmth * 0.058f

                r += profile.magentaShift * 0.055f + shadow * profile.magentaShift * 0.04f
                b += profile.magentaShift * 0.068f + shadow * profile.magentaShift * 0.05f
                g += profile.cyanShift * 0.040f
                b += profile.cyanShift * 0.042f + highlight * profile.cyanShift * 0.03f

                r += shadow * profile.shadowBlue * -0.038f
                g += shadow * profile.shadowGreen * 0.040f
                b += shadow * profile.shadowBlue * 0.082f
                r += highlight * profile.highlightRed * 0.085f
                g += highlight * profile.highlightGreen * 0.068f
                b += highlight * profile.highlightBlue * 0.085f

                b += profile.coolHighlights * highlight * 0.105f
                r -= profile.coolHighlights * highlight * 0.048f
                r += pastel * 0.09f
                g += pastel * 0.09f
                b += pastel * 0.09f

                val vignette = vignetteFactors[index]
                val flash = flashDistanceFactors[index] * (0.18f + highlight * 0.82f)

                r = r * vignette + flash * (0.18f + profile.warmth * 0.05f)
                g = g * vignette + flash * 0.14f
                b = b * vignette + flash * 0.09f

                val crawlX = (x / 8) + crawlPhase.toInt()
                val crawlY = (y / 8)
                val fineNoise = noise(seed, x, y, 0) - 0.5f
                val coarseNoise = noise(seed, x / coarseSize, y / coarseSize, 4) - 0.5f
                val clusterNoise = noise(seed, crawlX, crawlY, 5) - 0.5f
                val crawlNoise = noise(seed, crawlX + 3, crawlY + 1, 6) - 0.5f

                val shadowNoiseMask = (1f - luma).coerceIn(0f, 1f)
                val shadowNoiseMaskSquared = shadowNoiseMask * shadowNoiseMask
                val noiseShape = 0.012f + shadowNoiseMaskSquared * 0.28f + (1f - highlight) * 0.018f

                val monoNoise = (
                    fineNoise * (1f - profile.grainCoarseness * 0.55f) +
                        coarseNoise * profile.grainCoarseness * 0.50f +
                        clusterNoise * profile.grainCoarseness * 0.28f +
                        crawlNoise * profile.grainCoarseness * 0.18f
                    ) * profile.grain * noiseShape

                val chromaStrength = profile.chromaNoise * (0.006f + shadowNoiseMaskSquared * 0.20f)
                val instabilityR = (noise(seed, crawlX, crawlY, 1) - 0.5f) * chromaStrength * 1.6f
                val instabilityG = (noise(seed, crawlX, crawlY, 2) - 0.5f) * chromaStrength * 0.9f
                val instabilityB = (noise(seed, crawlX, crawlY, 3) - 0.5f) * chromaStrength * 1.8f

                r += monoNoise + instabilityR
                g += monoNoise + instabilityG
                b += monoNoise + instabilityB

                val clipPoint = (1f - profile.highlightClip * 0.22f).coerceIn(0.72f, 1f)
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

        val threshold = profile.bloomThreshold
        val thresholdSpan = 0.12f

        for (index in sourcePixels.indices) {
            val color = sourcePixels[index]
            val r = ((color ushr 16) and 0xff) / 255f
            val g = ((color ushr 8) and 0xff) / 255f
            val b = (color and 0xff) / 255f
            val luma = luminance(r, g, b)
            // Bloom ONLY on bright highlights — never darken midtones/shadows
            val highlight = smoothstep(threshold, threshold + thresholdSpan, luma)
            if (highlight <= 0.001f) {
                maskPixels[index] = 0
                continue
            }
            val alpha = (highlight * highlight * profile.bloom * 200f).roundToInt().coerceIn(0, 235)
            val red = (246 + profile.bloomWarmth * 38f + profile.highlightRed * 28f - profile.coolHighlights * 16f)
                .roundToInt()
                .coerceIn(0, 255)
            val green = (244 + profile.bloomWarmth * 20f + profile.highlightGreen * 22f + profile.coolHighlights * 4f)
                .roundToInt()
                .coerceIn(0, 255)
            val blue = (228 - profile.bloomWarmth * 36f + profile.highlightBlue * 32f + profile.coolHighlights * 42f)
                .roundToInt()
                .coerceIn(0, 255)
            maskPixels[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }

        val mask = Bitmap.createBitmap(maskPixels, width, height, Bitmap.Config.ARGB_8888)
        val divisor = (10f - profile.bloomSpread * 7f).roundToInt().coerceIn(3, 10)
        val smallW = max(1, width / divisor)
        val smallH = max(1, height / divisor)
        val small = Bitmap.createScaledBitmap(mask, smallW, smallH, true)
        val bloom = Bitmap.createScaledBitmap(small, width, height, true)
        Canvas(source).drawBitmap(bloom, 0f, 0f, bloomPaint)
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
        val decay = (0.86f + profile.ccdStreak * 0.09f).coerceIn(0.86f, 0.97f)

        for (y in 0 until height) {
            var trail = 0f
            val row = y * width
            for (x in 0 until width) {
                val index = row + x
                val color = pixels[index]
                val luma = luminance255((color ushr 16) and 0xff, (color ushr 8) and 0xff, color and 0xff)
                val energy = smoothstep(0.72f, 1.0f, luma) * profile.ccdStreak
                trail = max(energy, trail * decay)
                if (trail > 0.01f) pixels[index] = addGlow(color, trail * 0.22f, profile)
            }
            trail = 0f
            for (x in width - 1 downTo 0) {
                val index = row + x
                val color = pixels[index]
                val luma = luminance255((color ushr 16) and 0xff, (color ushr 8) and 0xff, color and 0xff)
                val energy = smoothstep(0.72f, 1.0f, luma) * profile.ccdStreak
                trail = max(energy, trail * decay)
                if (trail > 0.01f) pixels[index] = addGlow(color, trail * 0.17f, profile)
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
        val amount = strength.coerceIn(0f, 1f) * 0.82f

        for (index in sourcePixels.indices) {
            sourcePixels[index] = unsharp(sourcePixels[index], blurPixels[index], amount)
        }

        source.setPixels(sourcePixels, 0, width, 0, 0, width, height)
        blurred.recycle()
        return source
    }

    private fun applyCompressionBreakup(
        source: Bitmap,
        profile: CameraProfile,
        mode: ProcessingMode,
    ): Bitmap {
        if (profile.macroblockStrength <= 0.01f && profile.chromaSmear <= 0.01f && profile.mosquitoNoise <= 0.01f) {
            return source
        }
        var working = applyMacroblocks(source, profile)
        working = applyChromaSmear(working, profile.chromaSmear)
        working = applyMosquitoNoise(working, profile)
        if (mode == ProcessingMode.PREVIEW && profile.jpegArtifacts > 0.2f) {
            working = applyFastJpegLook(working, profile)
        }
        return working
    }

    private fun applyFastJpegLook(source: Bitmap, profile: CameraProfile): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val blockSize = 8
        val strength = (profile.jpegArtifacts * 0.35f).coerceIn(0f, 0.5f)
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
                        val c = pixels[row + x + bx]
                        sumR += (c ushr 16) and 0xff
                        sumG += (c ushr 8) and 0xff
                        sumB += c and 0xff
                        count++
                    }
                }
                val avg = (0xff shl 24) or ((sumR / count) shl 16) or ((sumG / count) shl 8) or (sumB / count)
                for (by in 0 until blockH) {
                    val row = (y + by) * width
                    for (bx in 0 until blockW) {
                        val idx = row + x + bx
                        pixels[idx] = blend(pixels[idx], avg, strength)
                    }
                }
                x += blockSize
            }
            y += blockSize
        }
        source.setPixels(pixels, 0, width, 0, 0, width, height)
        return source
    }

    private fun applyMacroblocks(source: Bitmap, profile: CameraProfile): Bitmap {
        if (profile.macroblockStrength <= 0.01f) return source
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val blockSize = (8 + profile.macroblockStrength * 18f).roundToInt().coerceIn(8, 28)
        val blendAmount = (profile.macroblockStrength * 0.92f).coerceIn(0f, 0.96f)

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
        val offset = (1 + strength * 10f).roundToInt().coerceIn(1, 14)
        val amount = (strength * 0.78f).coerceIn(0f, 0.92f)

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
        val seed = (width * 31 + height * 17).toInt()
        val amount = profile.mosquitoNoise.coerceIn(0f, 1f) * 0.10f

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
                val edgeMask = smoothstep(0.03f, 0.18f, edge)
                if (edgeMask > 0f) {
                    val n = (noise(seed, x, y, 11) - 0.5f) * amount * edgeMask * 2.8f
                    val c = (noise(seed, x, y, 12) - 0.5f) * amount * edgeMask * 3.4f
                    val alpha = color ushr 24
                    val r = (((color ushr 16) and 0xff) / 255f) + n + c
                    val g = (((color ushr 8) and 0xff) / 255f) + n * 0.55f
                    val b = ((color and 0xff) / 255f) + n - c
                    pixels[index] = pack(alpha, r, g, b)
                }
            }
        }

        source.setPixels(pixels, 0, width, 0, 0, width, height)
        return source
    }

    private fun simulateJpegArtifacts(
        source: Bitmap,
        profile: CameraProfile,
        mode: ProcessingMode,
    ): Bitmap {
        if (profile.jpegArtifacts <= 0.04f) return source
        if (mode == ProcessingMode.PREVIEW) return source

        val quality = (profile.jpegQuality - profile.jpegArtifacts * 38f)
            .roundToInt()
            .coerceIn(28, 92)
        val stream = jpegStream.get()!!
        stream.reset()
        source.compress(Bitmap.CompressFormat.JPEG, quality, stream)
        val bytes = stream.toByteArray()
        val decoded = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        return decoded?.copy(Bitmap.Config.ARGB_8888, true) ?: source
    }

    private fun contrast(value: Float, contrast: Float): Float {
        return (value - 0.5f) * contrast + 0.5f
    }

    private fun clipHighlight(value: Float, clipPoint: Float): Float {
        if (value <= clipPoint) return value
        val excess = (value - clipPoint) / (1f - clipPoint).coerceAtLeast(0.001f)
        return clipPoint + smoothstep(0f, 1f, excess) * (1f - clipPoint) * 0.65f
    }

    private fun addGlow(color: Int, amount: Float, profile: CameraProfile): Int {
        val alpha = color ushr 24
        val r = (((color ushr 16) and 0xff) / 255f) + amount * (0.64f + profile.highlightRed)
        val g = (((color ushr 8) and 0xff) / 255f) + amount * (0.48f + profile.highlightGreen)
        val b = ((color and 0xff) / 255f) + amount * (0.54f + profile.highlightBlue + profile.coolHighlights * 0.45f)
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
