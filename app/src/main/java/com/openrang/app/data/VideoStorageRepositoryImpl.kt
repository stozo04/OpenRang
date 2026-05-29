package com.openrang.app.data

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.util.Log
import java.io.File
import java.util.UUID

/**
 * Production implementation of [VideoStorageRepository].
 *
 * Holds only raw [File] handles ([cacheDir] / [filesDir]) — never a
 * [android.content.Context]. The [OpenRangViewModel][com.openrang.app.ui.OpenRangViewModel]'s
 * `Factory` bridges Context to these paths once, in `MainActivity`.
 *
 * On-disk layout:
 * - scratch capture:  `cacheDir/scratch/raw_<uuid>.mp4` (per capture; cache-evictable)
 * - persisted raws:   `filesDir/videos/clip_<timestamp>.mp4`
 * - boomerangs:       `filesDir/boomerangs/boom_<timestamp>_from_<rawTimestamp>.mp4`
 * - thumbnails:       `filesDir/thumbnails/<same-stem>.jpg` (JPEG, 90% quality)
 */
class VideoStorageRepositoryImpl(
    private val cacheDir: File,
    private val filesDir: File,
) : VideoStorageRepository {

    private val videosDir = File(filesDir, "videos")
    private val boomerangsDir = File(filesDir, "boomerangs")
    private val thumbnailsDir = File(filesDir, "thumbnails")
    private val scratchDir = File(cacheDir, "scratch")

    override fun createScratchCapture(): ScratchCapture {
        scratchDir.mkdirs()
        val uuid = UUID.randomUUID().toString()
        return ScratchCapture(uuid, File(scratchDir, "raw_$uuid.mp4"))
    }

    override fun promoteScratchToRaw(scratch: ScratchCapture): RecordedVideo? {
        val timestamp = System.currentTimeMillis()
        val destVideo = File(videosDir.apply { mkdirs() }, "clip_$timestamp.mp4")
        val destThumb = File(thumbnailsDir.apply { mkdirs() }, "clip_$timestamp.jpg")

        return try {
            scratch.file.copyTo(destVideo, overwrite = true)
            extractThumbnail(destVideo, destThumb)
            RecordedVideo(
                id = timestamp,
                videoPath = destVideo.absolutePath,
                thumbnailPath = destThumb.absolutePath,
                kind = VideoKind.RAW,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to promote scratch capture ${scratch.uuid} to raw", e)
            null
        }
    }

    override fun discardScratch(scratch: ScratchCapture) {
        try {
            if (scratch.file.exists()) {
                scratch.file.delete()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to discard scratch ${scratch.uuid}", e)
        }
    }

    override fun allocateBoomerangFile(sourceRawId: Long): File {
        val timestamp = System.currentTimeMillis()
        return File(boomerangsDir.apply { mkdirs() }, "boom_${timestamp}_from_$sourceRawId.mp4")
    }

    override fun registerBoomerang(file: File, sourceRawId: Long): RecordedVideo? {
        return try {
            val id = parseTimestamp(file.name, prefix = "boom_") ?: System.currentTimeMillis()
            val destThumb = File(thumbnailsDir.apply { mkdirs() }, "${file.nameWithoutExtension}.jpg")
            extractThumbnail(file, destThumb)
            RecordedVideo(
                id = id,
                videoPath = file.absolutePath,
                thumbnailPath = destThumb.absolutePath,
                kind = VideoKind.BOOMERANG,
                sourceRawId = sourceRawId,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register boomerang ${file.name}", e)
            null
        }
    }

    override fun durationOf(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read duration for ${file.name}", e)
            0L
        } finally {
            retriever.release()
        }
    }

    override fun loadRecordedVideos(): List<RecordedVideo> {
        val raws = loadFrom(videosDir, prefix = "clip_", kind = VideoKind.RAW)
        val boomerangs = loadFrom(boomerangsDir, prefix = "boom_", kind = VideoKind.BOOMERANG)
        return (raws + boomerangs).sortedByDescending { it.id } // Newest first
    }

    /**
     * Scan [dir] for `<prefix>*.mp4` clips and map each to a [RecordedVideo] of [kind], lazily
     * extracting a missing thumbnail. A clip whose thumbnail can't be produced still appears in the
     * list (resilience — never a silently-dropped clip).
     */
    private fun loadFrom(dir: File, prefix: String, kind: VideoKind): List<RecordedVideo> {
        if (!dir.exists()) return emptyList()
        val files = dir.listFiles { _, name -> name.startsWith(prefix) && name.endsWith(".mp4") }
            ?: return emptyList()

        return files.mapNotNull { file ->
            val id = parseTimestamp(file.name, prefix) ?: 0L
            val sourceRawId = if (kind == VideoKind.BOOMERANG) parseSourceRawId(file.name) else null
            val thumbFile = File(thumbnailsDir, "${file.nameWithoutExtension}.jpg")

            if (!thumbFile.exists()) {
                try {
                    thumbnailsDir.mkdirs()
                    extractThumbnail(file, thumbFile)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to extract on-demand thumbnail for ${file.name}", e)
                    // Fall through: still list the clip, just with a (possibly missing) thumb path.
                }
            }
            RecordedVideo(id, file.absolutePath, thumbFile.absolutePath, kind, sourceRawId)
        }
    }

    override fun deleteVideo(video: RecordedVideo) {
        try {
            File(video.videoPath).takeIf { it.exists() }?.delete()
            File(video.thumbnailPath).takeIf { it.exists() }?.delete()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete video ${video.id}", e)
        }
    }

    /**
     * Extract the first frame of [video] to [dest] as a 90%-quality JPEG. The retriever is released
     * in a `finally` so a decode failure can't leak the native handle (the prior implementation
     * leaked it on the exception path).
     */
    private fun extractThumbnail(video: File, dest: File) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(video.absolutePath)
            val bitmap = retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            if (bitmap != null) {
                dest.outputStream().use { out ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
                }
            }
        } finally {
            retriever.release()
        }
    }

    /** Parse the leading epoch-millis timestamp out of `<prefix><ts>...mp4`. */
    private fun parseTimestamp(name: String, prefix: String): Long? =
        name.removePrefix(prefix).substringBefore('_').substringBefore('.').toLongOrNull()

    /** Parse `<rawTs>` out of a `boom_<ts>_from_<rawTs>.mp4` boomerang filename. */
    private fun parseSourceRawId(name: String): Long? =
        name.substringAfter("_from_", "").substringBefore(".mp4").toLongOrNull()

    private companion object {
        const val TAG = "VideoStorageRepository"
    }
}
