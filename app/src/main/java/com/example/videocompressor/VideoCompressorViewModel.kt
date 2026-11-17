package com.example.videocompressor

import android.app.Application
import android.content.ContentUris
import android.content.ContentValues
import android.media.MediaMetadataRetriever
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.videocompressor.utils.ProcessingResult
import com.example.videocompressor.utils.VideoProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.Locale

/** One video as listed in the in-app picker sheet, read straight from MediaStore. */
data class GalleryVideo(
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val durationLabel: String,
    val sizeBytes: Long,
    val width: Int,
    val height: Int
)

class VideoCompressorViewModel(application: Application) : AndroidViewModel(application) {

    sealed class UiState {
        object Idle : UiState()
        data class Compressing(val progress: Float, val current: Int, val total: Int) : UiState()
        data class Success(val files: List<File>) : UiState()
        object Saved : UiState()
        data class Error(val message: String) : UiState()
    }

    data class VideoInfo(
        val uri: Uri,
        val name: String,
        val resolution: String,
        val duration: String,
        val size: String
    )

    private val _uiState = MutableStateFlow<UiState>(UiState.Idle)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _keepAudio = MutableStateFlow(true)
    val keepAudio: StateFlow<Boolean> = _keepAudio.asStateFlow()

    private val _targetSizeMb = MutableStateFlow(50)
    val targetSizeMb: StateFlow<Int> = _targetSizeMb.asStateFlow()

    private val _selectedVideos = MutableStateFlow<List<VideoInfo>>(emptyList())
    val selectedVideos: StateFlow<List<VideoInfo>> = _selectedVideos.asStateFlow()

    private val _galleryVideos = MutableStateFlow<List<GalleryVideo>>(emptyList())
    val galleryVideos: StateFlow<List<GalleryVideo>> = _galleryVideos.asStateFlow()

    private val _pickerSelection = MutableStateFlow<Set<Uri>>(emptySet())
    val pickerSelection: StateFlow<Set<Uri>> = _pickerSelection.asStateFlow()

    private var compressionJob: Job? = null

    fun setKeepAudio(keep: Boolean) {
        _keepAudio.value = keep
    }

    fun setTargetSize(sizeMb: Int) {
        _targetSizeMb.value = sizeMb
    }

    /**
     * Loads videos visible to the app from MediaStore. Under "Allow limited access" the system returns only
     * the user-selected items; under "Allow all" it returns the whole library. Sorted by DATE_ADDED DESC.
     */
    fun loadAvailableGalleryVideos() {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val list = mutableListOf<GalleryVideo>()
            try {
                val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                val projection = arrayOf(
                    MediaStore.Video.Media._ID,
                    MediaStore.Video.Media.DISPLAY_NAME,
                    MediaStore.Video.Media.DURATION,
                    MediaStore.Video.Media.SIZE,
                    MediaStore.Video.Media.WIDTH,
                    MediaStore.Video.Media.HEIGHT,
                    MediaStore.Video.Media.DATE_ADDED
                )
                context.contentResolver.query(
                    collection, projection, null, null, "${MediaStore.Video.Media.DATE_ADDED} DESC"
                )?.use { c ->
                    val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                    val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                    val durCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                    val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                    val wCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                    val hCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                    while (c.moveToNext()) {
                        val uri = ContentUris.withAppendedId(collection, c.getLong(idCol))
                        val durationMs = c.getLong(durCol)
                        list.add(
                            GalleryVideo(
                                uri = uri,
                                displayName = c.getString(nameCol) ?: "video.mp4",
                                durationMs = durationMs,
                                durationLabel = formatDuration(durationMs),
                                sizeBytes = c.getLong(sizeCol),
                                width = c.getInt(wCol),
                                height = c.getInt(hCol)
                            )
                        )
                    }
                }
            } catch (e: SecurityException) {
                Log.w("VideoCompressorViewModel", "MediaStore access denied", e)
            }
            withContext(Dispatchers.Main) {
                _galleryVideos.value = list
                // Keep only items that are still visible, so a revoked item cannot stay selected.
                val visible = list.map { it.uri }.toSet()
                _pickerSelection.value = _pickerSelection.value.filter { it in visible }.toSet()
            }
        }
    }

    fun togglePickerSelection(uri: Uri) {
        _pickerSelection.value = _pickerSelection.value.let { if (uri in it) it - uri else it + uri }
    }

    /** Converts the sheet selection into the Step 1 list, keeping the sheet order. */
    fun confirmGallerySelection() {
        val picked = _galleryVideos.value.filter { it.uri in _pickerSelection.value }
        viewModelScope.launch(Dispatchers.IO) {
            val infos = picked.map { v ->
                VideoInfo(
                    uri = v.uri,
                    name = v.displayName,
                    resolution = "${v.width}x${v.height}",
                    duration = v.durationLabel,
                    size = "${v.sizeBytes / (1024 * 1024)}MB"
                )
            }
            withContext(Dispatchers.Main) {
                _selectedVideos.value = infos
                _uiState.value = UiState.Idle
            }
        }
    }

    private fun formatDuration(ms: Long): String =
        String.format(Locale.ROOT, "%d:%02d", (ms / 1000) / 60, (ms / 1000) % 60)

    fun saveToGallery(files: List<File>) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            val resolver = context.contentResolver
            var count = 0
            try {
                files.forEach { f ->
                    val cv = ContentValues().apply {
                        put(MediaStore.Video.Media.DISPLAY_NAME, f.name)
                        put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                        if (Build.VERSION.SDK_INT >= 29) {
                            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/VideoCompressor")
                            put(MediaStore.Video.Media.IS_PENDING, 1)
                        }
                    }
                    val coll = if (Build.VERSION.SDK_INT >= 29)
                        MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                    else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    resolver.insert(coll, cv)?.let { uri ->
                        resolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                        if (Build.VERSION.SDK_INT >= 29) {
                            cv.clear()
                            cv.put(MediaStore.Video.Media.IS_PENDING, 0)
                            resolver.update(uri, cv, null, null)
                        }
                        MediaScannerConnection.scanFile(context, arrayOf(f.absolutePath), arrayOf("video/mp4")) { _, _ -> }
                        count++
                    }
                }
                withContext(Dispatchers.Main) {
                    if (count > 0) {
                        _uiState.value = UiState.Saved
                        Toast.makeText(context, "Uloženo $count videí", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) { Log.w("VideoCompressorViewModel", "ignored", e)
                withContext(Dispatchers.Main) { _uiState.value = UiState.Error("Chyba uložení") }
            }
        }
    }

    fun saveAllToTree(treeUri: Uri, files: List<File>) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            try {
                val dir = DocumentFile.fromTreeUri(context, treeUri)
                if (dir == null || !dir.canWrite()) {
                    withContext(Dispatchers.Main) { _uiState.value = UiState.Error("Do složky nelze zapisovat") }
                    return@launch
                }
                files.forEach { f ->
                    dir.createFile("video/mp4", f.name)?.uri?.let { uri ->
                        context.contentResolver.openOutputStream(uri)?.use { out -> f.inputStream().use { it.copyTo(out) } }
                    }
                }
                withContext(Dispatchers.Main) {
                    _uiState.value = UiState.Saved
                    Toast.makeText(context, "Uloženo", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) { Log.w("VideoCompressorViewModel", "ignored", e)
                withContext(Dispatchers.Main) { _uiState.value = UiState.Error("Chyba uložení") }
            }
        }
    }

    fun saveToUri(file: File, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val context = getApplication<Application>()
            try {
                context.contentResolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } }
                withContext(Dispatchers.Main) {
                    Toast.makeText(context, "Uloženo", Toast.LENGTH_SHORT).show()
                    _uiState.value = UiState.Saved
                }
            } catch (e: Exception) { Log.w("VideoCompressorViewModel", "ignored", e)
                withContext(Dispatchers.Main) { _uiState.value = UiState.Error("Chyba uložení") }
            }
        }
    }

    fun compressVideo() {
        val videos = _selectedVideos.value
        if (videos.isEmpty()) return
        compressionJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val outFiles = mutableListOf<File>()
                val context = getApplication<Application>()
                val targetMb = _targetSizeMb.value
                val targetSizeBytes = targetMb * 1024 * 1024L
                val keepAudio = _keepAudio.value
                for (i in videos.indices) {
                    val v = videos[i]
                    withContext(Dispatchers.Main) { _uiState.value = UiState.Compressing(0f, i + 1, videos.size) }

                    val probe = VideoProcessor.probe(context, v.uri)
                    if (probe == null || probe.durationMs <= 0L) {
                        withContext(Dispatchers.Main) { _uiState.value = UiState.Error("Nelze zjistit délku videa.") }
                        return@launch
                    }

                    val finalName = "Compressed_${v.name.substringBeforeLast(".")}.${v.name.substringAfterLast(".", "mp4")}"
                    val outFile = File(context.cacheDir, finalName)
                    val firstBitrate = VideoProcessor.firstPassBitrate(probe, targetSizeBytes, keepAudio)
                    val firstCap = VideoProcessor.firstPassCap(targetMb, probe.durationMs)
                    val audioBitrate = if (keepAudio) 128_000L else 0L
                    Log.i(
                        "CompressionBudget",
                        "file=${v.name} sourceBytes=${probe.sizeBytes} targetBytes=$targetSizeBytes " +
                            "durationMs=${probe.durationMs} sourceBitrate=${probe.sourceBitrate} " +
                            "videoBitrate=$firstBitrate audioBitrate=$audioBitrate cap=$firstCap"
                    )

                    // Pass 1: bitrate budget and resolution cap chosen up front.
                    val pass1 = VideoProcessor.compressVideo(context, v.uri, outFile, keepAudio, firstBitrate, firstCap, probe) { p ->
                        viewModelScope.launch(Dispatchers.Main) { _uiState.value = UiState.Compressing(p, i + 1, videos.size) }
                    }
                    if (pass1 is ProcessingResult.Error) {
                        withContext(Dispatchers.Main) { _uiState.value = UiState.Error(pass1.message) }
                        return@launch
                    }
                    if (outFile.length() <= targetSizeBytes) {
                        outFiles.add(outFile)
                        continue
                    }

                    // Pass 2 (the last one): bitrate rescaled from the measured pass-1 size, resolution stepped down once.
                    val pass1Bytes = outFile.length()
                    val secondBitrate = VideoProcessor.secondPassBitrate(
                        probe, pass1Bytes, firstBitrate, targetSizeBytes, keepAudio
                    )
                    val secondCap = VideoProcessor.nextCap(firstCap)
                    Log.i(
                        "CompressionBudget",
                        "pass1Bytes=$pass1Bytes targetBytes=$targetSizeBytes secondVideoBitrate=$secondBitrate cap=$secondCap"
                    )
                    if (outFile.exists()) outFile.delete()
                    withContext(Dispatchers.Main) { _uiState.value = UiState.Compressing(0f, i + 1, videos.size) }

                    val pass2 = VideoProcessor.compressVideo(context, v.uri, outFile, keepAudio, secondBitrate, secondCap, probe) { p ->
                        viewModelScope.launch(Dispatchers.Main) { _uiState.value = UiState.Compressing(p, i + 1, videos.size) }
                    }
                    if (pass2 is ProcessingResult.Error) {
                        withContext(Dispatchers.Main) { _uiState.value = UiState.Error(pass2.message) }
                        return@launch
                    }

                    // Final guard: a file above the chosen limit is never delivered.
                    val finalBytes = outFile.length()
                    Log.i("CompressionBudget", "pass2Bytes=$finalBytes targetBytes=$targetSizeBytes")
                    if (finalBytes > targetSizeBytes) {
                        outFile.delete()
                        withContext(Dispatchers.Main) {
                            _uiState.value = UiState.Error("Video se nepodařilo zmenšit pod $targetMb MB ani po druhém průchodu.")
                        }
                        return@launch
                    }
                    outFiles.add(outFile)
                }
                withContext(Dispatchers.Main) { _uiState.value = UiState.Success(outFiles) }
            } catch (e: Throwable) {
                Log.e("CompressionCrash", "Error", e)
                withContext(Dispatchers.Main) { _uiState.value = UiState.Error("Chyba: ${e.localizedMessage}") }
            }
        }
    }



    fun cancelCompression() {
        compressionJob?.cancel()
        compressionJob = null
        _uiState.value = UiState.Idle
    }

    fun reset() {
        _uiState.value = UiState.Idle
        _selectedVideos.value = emptyList()
        _pickerSelection.value = emptySet()
    }

    /** Opened from the pencil: pre-check the videos that are already chosen for compression. */
    fun preselectCurrentVideos() {
        _pickerSelection.value = _selectedVideos.value.map { it.uri }.toSet()
    }

    /** Opened from the main tap: start with nothing checked, unless a previous choice is still active. */
    fun clearPickerSelection() {
        _pickerSelection.value = emptySet()
    }
}
