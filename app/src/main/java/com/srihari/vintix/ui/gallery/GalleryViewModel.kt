package com.srihari.vintix.ui.gallery

import android.app.Application
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class VintixPhoto(
    val uri: Uri,
    val dateTaken: Long,
    val profileName: String
)

class GalleryViewModel(application: Application) : AndroidViewModel(application) {

    private val _photos = MutableStateFlow<List<VintixPhoto>>(emptyList())
    val photos: StateFlow<List<VintixPhoto>> = _photos.asStateFlow()

    fun loadPhotos() {
        viewModelScope.launch(Dispatchers.IO) {
            val photoList = mutableListOf<VintixPhoto>()
            val context = getApplication<Application>()
            val resolver = context.contentResolver

            val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.TITLE
            )

            val selection: String?
            val selectionArgs: Array<String>?
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                selection = "${MediaStore.Images.Media.RELATIVE_PATH} LIKE ?"
                selectionArgs = arrayOf("%Pictures/Vintix%")
            } else {
                @Suppress("DEPRECATION")
                selection = "${MediaStore.Images.Media.DATA} LIKE ?"
                selectionArgs = arrayOf("%/Pictures/Vintix/%")
            }
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            resolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val titleColumn = cursor.getColumnIndex(MediaStore.Images.Media.TITLE)

                while (cursor.moveToNext() && photoList.size < MAX_PHOTOS) {
                    val id = cursor.getLong(idColumn)
                    val dateAdded = cursor.getLong(dateColumn) * 1000 // Convert to MS
                    
                    val contentUri = Uri.withAppendedPath(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        id.toString()
                    )

                    // Prefer MediaStore metadata written by new exports; EXIF fallback keeps old shots readable.
                    var profileName = "Unknown"
                    if (titleColumn >= 0) {
                        val title = cursor.getString(titleColumn)
                        if (title != null && title.startsWith("Vintix - ")) {
                            profileName = title.substringAfter("Vintix - ")
                        }
                    }
                    try {
                        if (profileName == "Unknown") {
                            resolver.openInputStream(contentUri)?.use { stream ->
                                val exif = ExifInterface(stream)
                                val model = exif.getAttribute(ExifInterface.TAG_MODEL)
                                if (model != null && model.startsWith("Vintix - ")) {
                                    profileName = model.substringAfter("Vintix - ")
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Ignore EXIF errors
                    }

                    photoList.add(VintixPhoto(contentUri, dateAdded, profileName))
                }
            }

            _photos.value = photoList
        }
    }

    fun deletePhoto(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            runCatching {
                context.contentResolver.delete(uri, null, null)
            }
            loadPhotos()
        }
    }

    private companion object {
        private const val MAX_PHOTOS = 500
    }
}
