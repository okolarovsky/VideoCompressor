package com.example.videocompressor.utils

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.effect.Presentation
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

sealed class ProcessingResult {
    data class Success(val usedBitrate: Int) : ProcessingResult()
    data class Error(val message: String) : ProcessingResult()
}

/** Source facts read once per video and reused by both passes. */
data class VideoProbe(val durationMs: Long, val width: Int, val height: Int, val sizeBytes: Long) {
    /** Average total bitrate of the source file (video + audio) in bits per second. */
    val sourceBitrate: Long
        get() = if (durationMs > 0) sizeBytes * 8L * 1000L / durationMs else 0L
}

@androidx.media3.common.util.UnstableApi
object VideoProcessor {
    private const val TAG = "VideoProcessor"
    private const val AUDIO_BITRATE = 128_000L
    private const val MIN_VIDEO_BITRATE = 120_000L
    private const val MIN_SECOND_PASS_BITRATE = 100_000L

    /** Reads duration and display size (rotation-aware). Returns null when metadata is unreadable. */
    fun probe(context: Context, uri: Uri): VideoProbe? {
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(context, uri)
            val duration = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val size = context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: 0L
            if (rotation == 90 || rotation == 270) VideoProbe(duration, h, w, size)
            else VideoProbe(duration, w, h, size)
        } catch (e: Exception) { Log.w(TAG, "ignored", e)
            null
        } finally {
            try { r.release() } catch (e: Exception) { Log.w(TAG, "ignored", e); }
        }
    }

    /**
     * Video bitrate budget for a target file size:
     * 7 % reserve for MP4 container (moov/metadata) and encoder overshoot, then the audio bitrate is subtracted.
     * Clamped to 85 % of the source bitrate so a small source is never re-encoded to something larger.
     */
    fun videoBitrateBudget(probe: VideoProbe, targetSizeBytes: Long, keepAudio: Boolean): Long {
        val durationSec = maxOf(probe.durationMs / 1000.0, 1.0)
        val audioBitrate = if (keepAudio) AUDIO_BITRATE else 0L
        val budget = ((targetSizeBytes * 0.93 * 8) / durationSec).toLong() - audioBitrate
        val sourceCap = if (probe.sourceBitrate > 0) (probe.sourceBitrate * 0.85).toLong() else Long.MAX_VALUE
        return minOf(maxOf(budget, MIN_VIDEO_BITRATE), sourceCap)
    }

    /** Pass 1 bitrate. Pass 2 uses [secondPassBitrate] with the measured output. */
    fun firstPassBitrate(probe: VideoProbe, targetSizeBytes: Long, keepAudio: Boolean): Long =
        videoBitrateBudget(probe, targetSizeBytes, keepAudio)

    /**
     * Pass 2 bitrate from the measured output of pass 1. Only the video part is rescaled; the audio bitrate
     * is fixed, so it is subtracted before scaling. A 10 % safety margin is added on top.
     */
    fun secondPassBitrate(
        probe: VideoProbe,
        pass1Bytes: Long,
        pass1Bitrate: Long,
        targetSizeBytes: Long,
        keepAudio: Boolean
    ): Long {
        val durationSec = maxOf(probe.durationMs / 1000.0, 1.0)
        val audioBitrate = if (keepAudio) AUDIO_BITRATE else 0L
        val measuredTotal = pass1Bytes * 8.0 / durationSec
        val measuredVideo = (measuredTotal - audioBitrate).coerceAtLeast(1.0)
        val desiredVideo = (targetSizeBytes * 0.90 * 8.0 / durationSec) - audioBitrate
        val scaled = (pass1Bitrate * (desiredVideo / measuredVideo) * 0.95).toLong()
        val sourceCap = if (probe.sourceBitrate > 0) (probe.sourceBitrate * 0.85).toLong() else Long.MAX_VALUE
        return minOf(maxOf(scaled, MIN_SECOND_PASS_BITRATE), sourceCap)
    }

    /** Pass-1 resolution cap (max short side in px) so the encoder never runs at 4K on a tiny budget. */
    fun firstPassCap(targetMb: Int, durationMs: Long): Int {
        val sec = durationMs / 1000.0
        return when {
            targetMb == 10 && sec > 30 -> 720
            targetMb == 25 && sec > 60 -> 720
            else -> 1080
        }
    }

    /** Next step down for pass 2: 1080 -> 720 -> 480. */
    fun nextCap(current: Int): Int = when (current) {
        1080 -> 720
        else -> 480
    }

    suspend fun compressVideo(
        context: Context,
        inputUri: Uri,
        outputFile: File,
        keepAudio: Boolean,
        videoBitrate: Long,
        maxShortSide: Int,
        probe: VideoProbe,
        onProgress: (Float) -> Unit
    ): ProcessingResult = withContext(Dispatchers.Main) {
        try {
            val finalBitrate = videoBitrate.coerceIn(MIN_SECOND_PASS_BITRATE, 15_000_000L).toInt()
            val encoderFactory = DefaultEncoderFactory.Builder(context)
                .setRequestedVideoEncoderSettings(
                    VideoEncoderSettings.Builder().setBitrate(finalBitrate).build()
                )
                .setEnableFallback(true)
                .build()

            // Downscale only when the source short side exceeds the cap; never upscale.
            val videoEffects = mutableListOf<Effect>()
            if (minOf(probe.width, probe.height) > maxShortSide) {
                // Presentation scales by the target height; for portrait sources cap the short side (width) instead.
                videoEffects.add(
                    if (probe.width >= probe.height) Presentation.createForHeight(maxShortSide)
                    else Presentation.createForWidthAndHeight(
                        maxShortSide,
                        maxShortSide * probe.height / probe.width,
                        Presentation.LAYOUT_SCALE_TO_FIT
                    )
                )
            }

            suspendCancellableCoroutine<ProcessingResult> { continuation ->
                val progressHolder = ProgressHolder()
                var progressJob: kotlinx.coroutines.Job? = null

                val listener = object : Transformer.Listener {
                    override fun onCompleted(composition: androidx.media3.transformer.Composition, exportResult: ExportResult) {
                        progressJob?.cancel()
                        onProgress(1.0f)
                        if (continuation.isActive) continuation.resume(ProcessingResult.Success(finalBitrate))
                    }

                    override fun onError(
                        composition: androidx.media3.transformer.Composition,
                        exportResult: ExportResult,
                        exportException: ExportException
                    ) {
                        progressJob?.cancel()
                        Log.e(TAG, "Detail chyby komprese", exportException)
                        val raw = exportException.message ?: "Chyba transformace"
                        val msg = if (raw.contains("GL_EXT_YUV_target", ignoreCase = true) || raw.contains("shader", ignoreCase = true)) {
                            "Hardware zařízení nepodporuje přímé zpracování tohoto HDR formátu. Zkuste video s běžným barevným rozsahem (SDR)."
                        } else raw
                        if (continuation.isActive) continuation.resume(ProcessingResult.Error(msg))
                    }
                }

                val transformer = Transformer.Builder(context)
                    .setVideoMimeType(MimeTypes.VIDEO_H264)
                    .setEncoderFactory(encoderFactory)
                    .addListener(listener)
                    .build()

                val editedMediaItem = EditedMediaItem.Builder(MediaItem.fromUri(inputUri))
                    .setRemoveAudio(!keepAudio)
                    .setEffects(Effects(emptyList(), videoEffects))
                    .build()

                progressJob = launch {
                    while (isActive) {
                        val state = transformer.getProgress(progressHolder)
                        if (state == Transformer.PROGRESS_STATE_AVAILABLE) {
                            onProgress(progressHolder.progress / 100f)
                        } else if (state == Transformer.PROGRESS_STATE_NOT_STARTED) {
                            break
                        }
                        delay(500)
                    }
                }

                try {
                    transformer.start(editedMediaItem, outputFile.absolutePath)
                } catch (e: Exception) {
                    Log.e(TAG, "Start failed", e)
                    progressJob?.cancel()
                    if (continuation.isActive) continuation.resume(ProcessingResult.Error(e.message ?: "Start failed"))
                }

                continuation.invokeOnCancellation {
                    transformer.cancel()
                    progressJob?.cancel()
                    if (outputFile.exists()) outputFile.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error", e)
            ProcessingResult.Error(e.message ?: "Neočekávaná chyba")
        }
    }
}
