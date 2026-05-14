package com.srihari.vintix.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import android.util.Log
import com.srihari.vintix.rendering.CameraProfile
import com.srihari.vintix.rendering.RetroPhotoGlPipeline
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Handles photo capture and MediaStore persistence.
 * Stills are decoded from CameraX JPEG output, processed through the same OpenGL retro pipeline
 * as preview ([RetroPhotoGlPipeline]), then re-encoded to JPEG — no SurfaceView screenshot.
 */
class PhotoCaptureManager(private val context: Context) {

    private val photoExecutor = Executors.newSingleThreadExecutor()

    companion object {
        private const val FILENAME_PREFIX = "VINTIX_"
        private const val FILENAME_FORMAT = "yyyyMMdd_HHmmss"
        private const val MIME_TYPE = "image/jpeg"
        private const val RELATIVE_PATH = "Pictures/Vintix"
    }

    /**
     * Captures a full-resolution JPEG via CameraX, applies [cameraProfile] using the OpenGL FBO
     * path ([RetroPhotoGlPipeline]), and inserts the result into MediaStore.
     *
     * @param noisePhase Same semantic as preview shader [uNoisePhase]; pass
     *        [com.srihari.vintix.rendering.VintixRenderer.noisePhaseSnapshot] for closest match to live view.
     */
    fun capturePhoto(
        imageCapture: ImageCapture,
        cameraProfile: CameraProfile,
        profileName: String,
        noisePhase: Float,
        timestampStyle: com.srihari.vintix.rendering.timestamp.TimestampStyle?,
        onSuccess: (Uri) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        val temp = File.createTempFile("vintix_cap_", ".jpg", context.cacheDir)
        val outputOptions = ImageCapture.OutputFileOptions.Builder(temp).build()

        imageCapture.takePicture(
            outputOptions,
            photoExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    try {
                        val decoded = BitmapFactory.decodeFile(temp.absolutePath)
                            ?: throw IllegalStateException("Bitmap decode failed")
                        val oriented = applyExifRotation(temp.absolutePath, decoded)
                        if (oriented !== decoded) decoded.recycle()
                        
                        var leakIntensity = 0f
                        var leakOriginX = 0f
                        var leakOriginY = 0f
                        
                        // Apply single-capture randomized variations
                        val captureProfile = cameraProfile.applyInstability()
                        val croppedAndScaled = applyCropAndScale(oriented, captureProfile)
                        if (oriented !== croppedAndScaled) oriented.recycle()
                        
                        val leakConfig = captureProfile.lightLeakBehavior
                        if (leakConfig.probability > 0f && Math.random() < leakConfig.probability) {
                            leakIntensity = leakConfig.maxIntensity * (0.6f + 0.4f * Math.random().toFloat())
                            val edge = (Math.random() * 4).toInt()
                            when (edge) {
                                0 -> { leakOriginX = -0.1f - Math.random().toFloat() * 0.2f; leakOriginY = Math.random().toFloat() }
                                1 -> { leakOriginX = 1.1f + Math.random().toFloat() * 0.2f; leakOriginY = Math.random().toFloat() }
                                2 -> { leakOriginX = Math.random().toFloat(); leakOriginY = -0.1f - Math.random().toFloat() * 0.2f }
                                3 -> { leakOriginX = Math.random().toFloat(); leakOriginY = 1.1f + Math.random().toFloat() * 0.2f }
                            }
                        }
                        
                        var processed = RetroPhotoGlPipeline.processBitmap(
                            croppedAndScaled, 
                            captureProfile, 
                            noisePhase,
                            leakIntensity,
                            leakOriginX,
                            leakOriginY
                        )
                        if (croppedAndScaled !== processed) croppedAndScaled.recycle()
                        
                        if (timestampStyle != null) {
                            val stamped = com.srihari.vintix.rendering.timestamp.TimestampRenderer.applyTimestamp(processed, timestampStyle)
                            if (processed !== stamped) processed.recycle()
                            processed = stamped
                        }
                        
                        val uri = insertProcessedJpeg(processed, cameraProfile.jpegQuality, profileName)
                        processed.recycle()
                        temp.delete()
                        ContextCompat.getMainExecutor(context).execute { onSuccess(uri) }
                    } catch (e: Exception) {
                        temp.delete()
                        ContextCompat.getMainExecutor(context).execute { onError(e) }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    temp.delete()
                    ContextCompat.getMainExecutor(context).execute { onError(exception) }
                }
            }
        )
    }

    private fun applyCropAndScale(bitmap: Bitmap, profile: CameraProfile): Bitmap {
        val w = bitmap.width
        val h = bitmap.height
        val isPortrait = w < h
        val targetRatio = if (isPortrait) 1f / profile.aspectRatio.ratio else profile.aspectRatio.ratio
        
        var cropW = w
        var cropH = h
        
        val currentRatio = w.toFloat() / h.toFloat()
        if (currentRatio > targetRatio + 0.01f) {
            cropW = (h * targetRatio).toInt()
        } else if (currentRatio < targetRatio - 0.01f) {
            cropH = (w / targetRatio).toInt()
        }
        
        val cropX = (w - cropW) / 2
        val cropY = (h - cropH) / 2
        
        var finalW = cropW
        var finalH = cropH
        
        val maxResolution = profile.exportResolution
        if (maxResolution != null) {
            val longSide = Math.max(cropW, cropH)
            if (longSide > maxResolution) {
                val scale = maxResolution.toFloat() / longSide
                finalW = (cropW * scale).toInt()
                finalH = (cropH * scale).toInt()
            }
        }
        
        if (cropW == w && cropH == h && finalW == cropW && finalH == cropH) return bitmap
        
        val matrix = Matrix()
        if (finalW != cropW || finalH != cropH) {
            matrix.postScale(finalW.toFloat() / cropW, finalH.toFloat() / cropH)
        }
        
        return Bitmap.createBitmap(bitmap, cropX, cropY, cropW, cropH, matrix, true)
    }

    private fun applyExifRotation(path: String, bitmap: Bitmap): Bitmap {
        val rotation = try {
            ExifInterface(path).rotationDegrees
        } catch (_: Exception) {
            0
        }
        if (rotation == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun insertProcessedJpeg(bitmap: Bitmap, quality: Int, profileName: String): Uri {
        val timestamp = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(Date())
        val fileName = "$FILENAME_PREFIX$timestamp"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
        }

        val tempExifFile = File.createTempFile("vintix_exif_", ".jpg", context.cacheDir)
        java.io.FileOutputStream(tempExifFile).use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)) {
                throw IllegalStateException("JPEG compress to temp file failed")
            }
        }

        try {
            val exif = ExifInterface(tempExifFile.absolutePath)
            exif.setAttribute(ExifInterface.TAG_MODEL, "Vintix - $profileName")
            exif.saveAttributes()
        } catch (e: Exception) {
            Log.w("PhotoCaptureManager", "Failed to write EXIF data", e)
        }

        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, contentValues)
            ?: throw IllegalStateException("MediaStore insert failed")

        resolver.openOutputStream(uri)?.use { out ->
            java.io.FileInputStream(tempExifFile).copyTo(out)
        } ?: throw IllegalStateException("Could not open output stream for $uri")

        tempExifFile.delete()
        return uri
    }
}
