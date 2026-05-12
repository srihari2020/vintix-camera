package com.srihari.vintix.camera

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Handles photo capture and MediaStore persistence.
 * Decoupled from [CameraManager] so capture/save logic can be
 * independently swapped (e.g., raw DNG pipeline, retro post-processing).
 */
class PhotoCaptureManager(private val context: Context) {

    companion object {
        private const val FILENAME_PREFIX = "VINTIX_"
        private const val FILENAME_FORMAT = "yyyyMMdd_HHmmss"
        private const val MIME_TYPE = "image/jpeg"
        private const val RELATIVE_PATH = "Pictures/Vintix"
    }

    /**
     * Captures a photo using the provided [ImageCapture] use case
     * and saves it to MediaStore under Pictures/Vintix.
     *
     * @param imageCapture The CameraX ImageCapture use case instance
     * @param onSuccess Called with the saved image [Uri] on success
     * @param onError Called with the exception on failure
     */
    fun capturePhoto(
        imageCapture: ImageCapture,
        onSuccess: (Uri) -> Unit,
        onError: (Exception) -> Unit
    ) {
        val timestamp = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(Date())
        val fileName = "$FILENAME_PREFIX$timestamp"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
            put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(
            context.contentResolver,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            contentValues
        ).build()

        imageCapture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    output.savedUri?.let { uri ->
                        onSuccess(uri)
                    } ?: onError(Exception("Saved URI was null"))
                }

                override fun onError(exception: ImageCaptureException) {
                    onError(exception)
                }
            }
        )
    }
}
