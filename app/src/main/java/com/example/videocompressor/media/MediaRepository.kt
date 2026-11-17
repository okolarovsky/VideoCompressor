package com.example.videocompressor.media

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class MediaType { IMAGE, VIDEO }

data class MediaItem(
    val uri: Uri,
    val displayName: String,
    val type: MediaType
)

/**
 * Queries MediaStore for images and videos. Visibility is decided by the system:
 * with full access it returns the whole library, with limited access only the items the user picked.
 * All queries run on Dispatchers.IO.
 */
class MediaRepository(private val context: Context) {

    suspend fun loadAll(): List<MediaItem> = withContext(Dispatchers.IO) {
        queryImages() + queryVideos()
    }

    private fun queryImages(): List<MediaItem> = queryCollection(
        collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        type = MediaType.IMAGE
    )

    private fun queryVideos(): List<MediaItem> = queryCollection(
        collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
        type = MediaType.VIDEO
    )

    private fun queryCollection(collection: Uri, type: MediaType): List<MediaItem> {
        val items = mutableListOf<MediaItem>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME
        )
        val sort = "${MediaStore.MediaColumns.DATE_ADDED} DESC"
        try {
            context.contentResolver.query(collection, projection, null, null, sort)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    items.add(
                        MediaItem(
                            uri = ContentUris.withAppendedId(collection, id),
                            displayName = cursor.getString(nameCol) ?: "neznámý soubor",
                            type = type
                        )
                    )
                }
            }
        } catch (e: SecurityException) {
            // Access was revoked between the permission check and the query.
            return emptyList()
        }
        return items
    }
}
