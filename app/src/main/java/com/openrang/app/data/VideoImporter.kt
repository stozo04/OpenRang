package com.openrang.app.data

import android.content.ContentResolver
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Pulls an external video (from the Android Photo Picker) into the app's own scratch storage so the
 * existing capture pipeline can consume it as if it had just been recorded (slice 07).
 *
 * This is a deliberately thin, `Context`-bearing helper kept *outside* the `Context`-free
 * [VideoStorageRepository] (Lesson 004): reading a `content://` URI needs a [ContentResolver], which
 * only a `Context` can supply, and the repository must never hold one. The
 * [OpenRangViewModel][com.openrang.app.ui.OpenRangViewModel]'s `Factory` bridges
 * `applicationContext.contentResolver` into [VideoImporterImpl] once, in `MainActivity`.
 */
interface VideoImporter {
    /**
     * Best-effort source duration in milliseconds (via [MediaMetadataRetriever] over the content
     * [source]), or `0L` if it can't be read. Used to enforce the ≤30 s import rule *before* copying,
     * so a too-long clip is never copied just to be rejected.
     */
    suspend fun probeDurationMs(source: Uri): Long

    /**
     * Copy the picked [source] content into [dest] off the main thread. Returns `false` on any I/O
     * failure (unreadable/revoked URI, low storage) — never throws to the caller, so the ViewModel
     * can fall back to a friendly snackbar rather than crashing.
     */
    suspend fun importToFile(source: Uri, dest: File): Boolean
}

class VideoImporterImpl(private val contentResolver: ContentResolver) : VideoImporter {

    override suspend fun probeDurationMs(source: Uri): Long = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            // openAssetFileDescriptor keeps this class Context-free (vs setDataSource(context, uri)).
            contentResolver.openAssetFileDescriptor(source, "r")?.use { afd ->
                retriever.setDataSource(afd.fileDescriptor)
                retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            } ?: 0L
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "probe failed", e); 0L
        } catch (e: IOException) {
            Log.e(TAG, "probe open failed", e); 0L
        } catch (e: SecurityException) {
            Log.e(TAG, "probe not permitted", e); 0L
        } finally {
            retriever.release()
        }
    }

    override suspend fun importToFile(source: Uri, dest: File): Boolean = withContext(Dispatchers.IO) {
        try {
            contentResolver.openInputStream(source)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } != null
        } catch (e: IOException) {
            Log.e(TAG, "import copy failed", e); false
        } catch (e: SecurityException) {
            Log.e(TAG, "import URI not readable", e); false
        }
    }

    private companion object {
        const val TAG = "VideoImporter"
    }
}
