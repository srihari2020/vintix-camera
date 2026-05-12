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
        private const val JPEG_QUALITY = 92
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
                        
                        var processed = RetroPhotoGlPipeline.processBitmap(oriented, cameraProfile, noisePhase)
                        if (oriented !== processed) oriented.recycle()
                        
                        if (timestampStyle != null) {
                            val stamped = com.srihari.vintix.rendering.timestamp.TimestampRenderer.applyTimestamp(processed, timestampStyle)
                            if (processed !== stamped) processed.recycle()
                            processed = stamped
                        }
                        
                        val uri = insertProcessedJpeg(processed)
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

    private fun insertProcessedJpeg(bitmap: Bitmap): Uri {
        val timestamp = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(Date())
        val fileName = "$FILENAME_PREFIX$timestamp"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
        }

        val resolver = context.contentResolver
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, contentValues)
            ?: throw IllegalStateException("MediaStore insert failed")

        resolver.openOutputStream(uri)?.use { out ->
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)) {
                throw IllegalStateException("JPEG compress failed")
            }
        } ?: throw IllegalStateException("Could not open output stream for $uri")

        return uri
    }
}
