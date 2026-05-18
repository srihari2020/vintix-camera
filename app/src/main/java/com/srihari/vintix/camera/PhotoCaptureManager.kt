package com.srihari.vintix.camera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import com.srihari.vintix.effects.CameraProfile
import com.srihari.vintix.effects.ProcessingMode
import com.srihari.vintix.effects.RetroBitmapProcessor
import com.srihari.vintix.effects.timestamp.TimestampStyle
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PhotoCaptureManager(private val context: Context) {
    private val captureCallbackExecutor = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val captureInFlight = AtomicBoolean(false)

    fun capturePhoto(
        imageCapture: ImageCapture,
        cameraProfile: CameraProfile,
        profileName: String,
        timestampStyle: TimestampStyle?,
        onSuccess: (Uri) -> Unit,
        onError: (Exception) -> Unit,
        onFinished: () -> Unit,
    ) {
        if (!captureInFlight.compareAndSet(false, true)) {
            postError(onError, IllegalStateException("Capture already in progress"))
            postFinished(onFinished)
            return
        }

        val tempFile = try {
            File.createTempFile("vintix_capture_", ".jpg", context.cacheDir)
        } catch (t: Throwable) {
            captureInFlight.set(false)
            postError(onError, if (t is Exception) t else Exception(t))
            postFinished(onFinished)
            return
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(tempFile)
            .setMetadata(ImageCapture.Metadata().apply { isReversedHorizontal = false })
            .build()

        try {
            imageCapture.takePicture(
                outputOptions,
                captureCallbackExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        scope.launch {
                            try {
                                processAndPublish(
                                    tempFile = tempFile,
                                    cameraProfile = cameraProfile,
                                    profileName = profileName,
                                    timestampStyle = timestampStyle,
                                    onSuccess = onSuccess,
                                    onError = onError,
                                )
                            } catch (t: Throwable) {
                                Log.e(TAG, "Unexpected capture failure", t)
                                postError(onError, if (t is Exception) t else Exception(t))
                            } finally {
                                tempFile.delete()
                                captureInFlight.set(false)
                                postFinished(onFinished)
                            }
                        }
                    }

                    override fun onError(exception: ImageCaptureException) {
                        tempFile.delete()
                        captureInFlight.set(false)
                        postError(onError, exception)
                        postFinished(onFinished)
                    }
                },
            )
        } catch (t: Throwable) {
            tempFile.delete()
            captureInFlight.set(false)
            postError(onError, if (t is Exception) t else Exception(t))
            postFinished(onFinished)
        }
    }

    fun shutdown() {
        scope.cancel()
        captureInFlight.set(false)
        captureCallbackExecutor.shutdown()
    }

    private suspend fun processAndPublish(
        tempFile: File,
        cameraProfile: CameraProfile,
        profileName: String,
        timestampStyle: TimestampStyle?,
        onSuccess: (Uri) -> Unit,
        onError: (Exception) -> Unit,
    ) {
        var decoded: Bitmap? = null
        var oriented: Bitmap? = null
        var cropped: Bitmap? = null
        var processed: Bitmap? = null

        try {
            val prepared = withContext(Dispatchers.Default) {
                decoded = BitmapFactory.decodeFile(tempFile.absolutePath)
                    ?: throw IllegalStateException("Failed to decode captured JPEG")

                oriented = applyExifRotation(tempFile.absolutePath, decoded!!)
                if (oriented !== decoded) {
                    decoded?.recycle()
                    decoded = null
                }

                cropped = cropAndScale(oriented!!, cameraProfile)
                if (cropped !== oriented) {
                    oriented?.recycle()
                    oriented = null
                }

                processed = RetroBitmapProcessor.process(
                    input = cropped!!,
                    profile = cameraProfile,
                    timestampStyle = timestampStyle,
                    captureDate = Date(),
                    mode = ProcessingMode.CAPTURE,
                )
                if (processed !== cropped) {
                    cropped?.recycle()
                    cropped = null
                }
                processed!!
            }

            val finalUri = withContext(Dispatchers.IO) {
                insertProcessedJpeg(
                    bitmap = prepared,
                    quality = cameraProfile.jpegQuality,
                    profileName = profileName,
                )
            }
            postSuccess(onSuccess, finalUri)
        } catch (t: Throwable) {
            Log.e(TAG, "Capture processing failed", t)
            postError(onError, if (t is Exception) t else Exception(t))
        } finally {
            decoded?.recycle()
            oriented?.recycle()
            cropped?.recycle()
            processed?.recycle()
        }
    }

    private fun cropAndScale(bitmap: Bitmap, profile: CameraProfile): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val targetRatio = if (width < height) {
            1f / profile.aspectRatio.ratio
        } else {
            profile.aspectRatio.ratio
        }

        var cropWidth = width
        var cropHeight = height
        val currentRatio = width.toFloat() / height.toFloat()

        if (currentRatio > targetRatio + 0.01f) {
            cropWidth = (height * targetRatio).toInt()
        } else if (currentRatio < targetRatio - 0.01f) {
            cropHeight = (width / targetRatio).toInt()
        }

        val cropX = (width - cropWidth) / 2
        val cropY = (height - cropHeight) / 2
        var outputWidth = cropWidth
        var outputHeight = cropHeight

        val maxResolution = profile.exportResolution
        if (maxResolution != null) {
            val longSide = maxOf(cropWidth, cropHeight)
            if (longSide > maxResolution) {
                val scale = maxResolution.toFloat() / longSide
                outputWidth = maxOf(1, (cropWidth * scale).toInt())
                outputHeight = maxOf(1, (cropHeight * scale).toInt())
            }
        }

        if (cropWidth == width && cropHeight == height && outputWidth == cropWidth && outputHeight == cropHeight) {
            return bitmap
        }

        val matrix = Matrix()
        if (outputWidth != cropWidth || outputHeight != cropHeight) {
            matrix.postScale(outputWidth.toFloat() / cropWidth, outputHeight.toFloat() / cropHeight)
        }
        return Bitmap.createBitmap(bitmap, cropX, cropY, cropWidth, cropHeight, matrix, true)
    }

    private fun applyExifRotation(path: String, bitmap: Bitmap): Bitmap {
        val rotation = runCatching { ExifInterface(path).rotationDegrees }.getOrDefault(0)
        if (rotation == 0) return bitmap
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun insertProcessedJpeg(
        bitmap: Bitmap,
        quality: Int,
        profileName: String,
    ): Uri {
        val now = Date()
        val timestamp = SimpleDateFormat(FILENAME_FORMAT, Locale.US).format(now)
        val fileName = "$FILENAME_PREFIX$timestamp.jpg"

        val contentValues = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, MIME_TYPE)
            put(MediaStore.Images.Media.TITLE, "Vintix - $profileName")
            put(MediaStore.Images.Media.DATE_TAKEN, now.time)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            } else {
                val directory = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "Vintix",
                )
                if (!directory.exists()) directory.mkdirs()
                put(MediaStore.Images.Media.DATA, File(directory, fileName).absolutePath)
            }
        }

        val tempExifFile = File.createTempFile("vintix_export_", ".jpg", context.cacheDir)
        try {
            val exportQuality = quality.coerceIn(35, 96)
            FileOutputStream(tempExifFile).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.JPEG, exportQuality, output)) {
                    throw IllegalStateException("JPEG compression failed")
                }
            }

            runCatching {
                val exif = ExifInterface(tempExifFile.absolutePath)
                exif.setAttribute(ExifInterface.TAG_MAKE, "Vintix")
                exif.setAttribute(ExifInterface.TAG_MODEL, "Vintix - $profileName")
                exif.saveAttributes()
            }

            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw IllegalStateException("MediaStore insert failed")

            try {
                resolver.openOutputStream(uri)?.use { output ->
                    FileInputStream(tempExifFile).use { input ->
                        input.copyTo(output)
                    }
                } ?: throw IllegalStateException("Unable to open MediaStore output stream")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val publishedValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.IS_PENDING, 0)
                    }
                    resolver.update(uri, publishedValues, null, null)
                }
                return uri
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
        } finally {
            tempExifFile.delete()
        }
    }

    private fun postSuccess(onSuccess: (Uri) -> Unit, uri: Uri) {
        ContextCompat.getMainExecutor(context).execute { onSuccess(uri) }
    }

    private fun postError(onError: (Exception) -> Unit, exception: Exception) {
        ContextCompat.getMainExecutor(context).execute { onError(exception) }
    }

    private fun postFinished(onFinished: () -> Unit) {
        ContextCompat.getMainExecutor(context).execute { onFinished() }
    }

    private companion object {
        private const val TAG = "PhotoCaptureManager"
        private const val FILENAME_PREFIX = "VINTIX_"
        private const val FILENAME_FORMAT = "yyyyMMdd_HHmmss_SSS"
        private const val MIME_TYPE = "image/jpeg"
        private const val RELATIVE_PATH = "Pictures/Vintix"
    }
}
