package com.example.videocompressor

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Log

/**
 * Resolves the real file name of a video picked via Photo Picker.
 * Order: MediaStore by `_ID` taken from the URI, then MediaStore by SIZE + DURATION, then OpenableColumns.
 */
object FileNameResolver {

    private const val TAG = "FileNameResolver"
    private const val FALLBACK_NAME = "video.mp4"

    fun resolve(context: Context, uri: Uri): String {
        lookupById(context, uri)?.let { return it }
        lookupBySizeAndDuration(context, uri)?.let { return it }
        queryOpenable(context, uri)?.let { return it }
        return FALLBACK_NAME
    }

    /** Last path segment of a MediaStore / Photo Picker URI is the numeric `_ID`. */
    private fun lookupById(context: Context, uri: Uri): String? {
        val id = uri.lastPathSegment?.toLongOrNull() ?: return null
        return try {
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media.DISPLAY_NAME),
                "${MediaStore.Video.Media._ID} = ?",
                arrayOf(id.toString()),
                null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: SecurityException) {
            Log.w(TAG, "No access to MediaStore by id", e)
            null
        }
    }

    private fun lookupBySizeAndDuration(context: Context, uri: Uri): String? {
        val retriever = MediaMetadataRetriever()
        return try {
            val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: return null
            if (size <= 0) return null
            retriever.setDataSource(context, uri)
            val duration = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: return null
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Video.Media.DISPLAY_NAME),
                "${MediaStore.Video.Media.SIZE} = ? AND ${MediaStore.Video.Media.DURATION} = ?",
                arrayOf(size.toString(), duration.toString()),
                null
            )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            Log.w(TAG, "SIZE/DURATION lookup failed", e)
            null
        } finally {
            try {
                retriever.release()
            } catch (e: Exception) {
                Log.w(TAG, "Retriever release failed", e)
            }
        }
    }

    private fun queryOpenable(context: Context, uri: Uri): String? {
        return try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            Log.w(TAG, "OpenableColumns query failed", e)
            null
        }
    }
}
