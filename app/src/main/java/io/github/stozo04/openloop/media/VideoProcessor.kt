package io.github.stozo04.openloop.media

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import androidx.core.net.toUri
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.effect.Presentation
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.math.roundToInt

/** How a boomerang loops its source clip. */
enum class BoomerangMode { FORWARD, REVERSE, FORWARD_THEN_REVERSE, REVERSE_THEN_FORWARD }

/**
 * Max short side (in px) of a rendered boomerang. Modern phones shoot up to 4K/8K; software encoding
 * those frame-by-frame, twice, would stall and can exceed an encoder's level limit. Downscaling to a
 * ≤1080p short side keeps the export fast, within codec limits, and is plenty for a shared loop. The
 * single source of truth for both the reverse pass ([VideoReverser]) and the render ([Presentation]),
 * so the forward and reversed halves are always the same resolution.
 */
internal const val MAX_OUTPUT_SHORT_SIDE = 1080

/**
 * Scale `(width, height)` down so the short side is ≤ [maxShortSide], preserving aspect ratio and
 * forcing even dimensions (AVC requires even). Never upscales — footage already at or below the cap
 * is returned unchanged (only evened). Pure + JVM-unit-tested ([MediaDimensionsTest]).
 */
internal fun cappedToShortSide(width: Int, height: Int, maxShortSide: Int = MAX_OUTPUT_SHORT_SIDE): Pair<Int, Int> {
    val shortSide = minOf(width, height)
    if (shortSide <= 0) return width to height
    if (shortSide <= maxShortSide) return evenDown(width) to evenDown(height)
    val scale = maxShortSide.toDouble() / shortSide
    return evenDown((width * scale).roundToInt()) to evenDown((height * scale).roundToInt())
}

/** Round down to the nearest even value (≥ 2); encoders reject odd dimensions in 4:2:0. */
internal fun evenDown(value: Int): Int = (value - (value % 2)).coerceAtLeast(2)

/**
 * Whether this mode needs the reversed clip generated (everything except a pure [BoomerangMode.FORWARD]).
 * Single source of truth shared by the editor preview, the ViewModel, and the render path.
 */
val BoomerangMode.needsReverse: Boolean get() = this != BoomerangMode.FORWARD

/**
 * Renders a boomerang MP4 from a trimmed source clip.
 *
 * Slice 02 drives this with a single hard-wired config (`FORWARD_THEN_REVERSE`, `speed = 2.0×`,
 * `repetitions = 1`); the full parameter surface exists so slices 03–05 (direction / speed / reps
 * tabs) can vary it without changing the contract.
 */
interface VideoProcessor {
    /**
     * Render a boomerang of [source] over `[trimStartMs, trimEndMs]` to [outputFile], returning it.
     * Suspending and cancellable; reports `0f..1f` via [onProgress]. Throws on a render failure
     * (the caller maps that to the user-facing "couldn't save" path).
     */
    suspend fun renderBoomerang(
        source: File,
        trimStartMs: Long,
        trimEndMs: Long,
        mode: BoomerangMode,
        speed: Float,
        filter: VideoFilter = VideoFilter.ORIGINAL,
        repetitions: Int,
        outputFile: File,
        onProgress: (Float) -> Unit = {},
    ): File

    /**
     * Produce (or cache-hit) the reversed clip for [source] over `[trimStartMs, trimEndMs]`, returning
     * the reversed [File]. The editor preview calls this so the reversed file is generated ONCE and
     * reused by [renderBoomerang] on save — both go through the same processor's reverser, so they hit
     * the same trim-keyed cache and never reverse the same window twice. Suspends and is cancellable.
     */
    suspend fun ensureReversed(
        source: File,
        trimStartMs: Long,
        trimEndMs: Long,
        onProgress: (Float) -> Unit = {},
    ): File
}

/**
 * Media3 [Transformer] implementation of [VideoProcessor].
 *
 * The reversed clip of a `*_REVERSE` mode is produced up front by [VideoReverser] (Media3 1.10.x has
 * no reverse effect), so every clip is just a plain forward [EditedMediaItem] — the reversed ones
 * built from the already-reversed file. [boomerangSequence] resolves the clip order and per-clip seam
 * drops; the clips are concatenated in that order into one [EditedMediaItemSequence] and exported with
 * a constant speed effect; audio is stripped.
 *
 * [Context] is injected via the ViewModel `Factory` (never passed to a ViewModel method — Lesson 004).
 *
 * Progress budget: the reverse pass owns `0f..0.8f`, the Composition encode owns `0.8f..1f`.
 */
@UnstableApi
class Media3VideoProcessor(
    private val context: Context,
    private val reverser: VideoReverser,
) : VideoProcessor {

    override suspend fun renderBoomerang(
        source: File,
        trimStartMs: Long,
        trimEndMs: Long,
        mode: BoomerangMode,
        speed: Float,
        filter: VideoFilter,
        repetitions: Int,
        outputFile: File,
        onProgress: (Float) -> Unit,
    ): File {
        // The ordered clip plan, with seam-frame drops resolved by sequence POSITION (see
        // boomerangSequence): only a clip that turns direction from the one before it drops its
        // duplicated leading frame — a lone clip and same-direction repeats never drop.
        val specs = boomerangSequence(mode, repetitions)
        val needsReverse = specs.any { it.direction == ClipDirection.REVERSED }
        val reversedFile: File? = if (needsReverse) {
            reverser.reverse(source, trimStartMs, trimEndMs) { frac -> onProgress(frac * REVERSE_BUDGET) }
        } else {
            null
        }
        currentCoroutineContext().ensureActive()
        onProgress(REVERSE_BUDGET)

        // Header reads (frame duration for the seam, short side for the resolution cap) are blocking
        // MediaExtractor work — keep them off the main thread (renderBoomerang runs on viewModelScope's
        // Main dispatcher; runTransformer hops to Main itself).
        val seamMs = withContext(Dispatchers.IO) { frameDurationMs(source) }
        val srcShortSide = withContext(Dispatchers.IO) { sourceShortSide(source) }
        // Speed (SpeedChangeEffect) + the chosen color look (RgbFilter / RgbAdjustment / HslAdjustment)
        // + a resolution cap for large sources compose in one videoEffects list, applied identically to
        // every clip in the sequence.
        val clipEffects = videoEffects(speed, filter, srcShortSide)
        val items = specs.map { spec ->
            val dropMs = if (spec.dropLeadingFrame) seamMs else 0L
            when (spec.direction) {
                ClipDirection.FORWARD -> forwardItem(source, trimStartMs, trimEndMs, dropMs, clipEffects)
                ClipDirection.REVERSED -> reverseItem(reversedFile!!, dropMs, clipEffects)
            }
        }

        val sequence = EditedMediaItemSequence.withVideoFrom(items)
        // Imported clips can be 10-bit HDR (HLG/PQ); FORWARD clips still ingest that HDR source (and a
        // pure FORWARD boomerang never goes through the SDR reverse pass at all). Our encoder is
        // SDR-only, so tone-map HDR→SDR in the OpenGL pipeline. OpenGL mode is API 29+, widely
        // supported, and falls back gracefully instead of throwing like the MediaCodec variant.
        // developer.android.com/media/media3/transformer/tone-mapping
        val composition = Composition.Builder(sequence)
            .setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL)
            .build()

        runTransformer(composition, outputFile) { frac ->
            onProgress(REVERSE_BUDGET + frac * (1f - REVERSE_BUDGET))
        }
        onProgress(1f)
        return outputFile
    }

    override suspend fun ensureReversed(
        source: File,
        trimStartMs: Long,
        trimEndMs: Long,
        onProgress: (Float) -> Unit,
    ): File = reverser.reverse(source, trimStartMs, trimEndMs, onProgress)

    /** Forward clip over `[startMs, endMs]`; [dropLeadingMs] (>0 at a turn seam) skips its first frame. */
    private fun forwardItem(source: File, startMs: Long, endMs: Long, dropLeadingMs: Long, effects: Effects): EditedMediaItem {
        val clip = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(startMs + dropLeadingMs)
            .setEndPositionMs(endMs)
            .build()
        val item = MediaItem.Builder().setUri(source.toUri()).setClippingConfiguration(clip).build()
        return EditedMediaItem.Builder(item).setRemoveAudio(true).setEffects(effects).build()
    }

    /**
     * Reversed clip; the file already spans only the trim window, so [dropLeadingMs] (>0 at a turn
     * seam) skips its first frame and `0` plays it whole (e.g. a standalone `REVERSE`).
     */
    private fun reverseItem(reversedFile: File, dropLeadingMs: Long, effects: Effects): EditedMediaItem {
        val clip = MediaItem.ClippingConfiguration.Builder()
            .setStartPositionMs(dropLeadingMs)
            .build()
        val item = MediaItem.Builder().setUri(reversedFile.toUri()).setClippingConfiguration(clip).build()
        return EditedMediaItem.Builder(item).setRemoveAudio(true).setEffects(effects).build()
    }

    private fun videoEffects(speed: Float, filter: VideoFilter, sourceShortSide: Int): Effects {
        // SpeedChangingVideoEffect does not exist in Media3 1.10.1; the (deprecated) float-constructor
        // SpeedChangeEffect is the only constant-speed video effect — there is no public constant
        // SpeedProvider factory. Verified against the 1.10.1 source tag.
        @Suppress("DEPRECATION")
        val speedEffect: Effect = androidx.media3.effect.SpeedChangeEffect(speed)
        val videoEffects = buildList {
            // Resolution cap FIRST (a geometry op): downscale a 4K/8K source to ≤1080p short side so the
            // export is fast, within encoder level limits, and consistent with the already-capped
            // reversed clip. Only when the source exceeds the cap — never upscale smaller footage.
            if (sourceShortSide > MAX_OUTPUT_SHORT_SIDE) {
                add(Presentation.createForShortSide(MAX_OUTPUT_SHORT_SIDE))
            }
            // Then speed, then the color look (order is cosmetic — the look is a per-pixel matrix).
            // ORIGINAL contributes no effects, so a no-filter ≤1080p render is the prior slice-04 path.
            add(speedEffect)
            addAll(filter.toMediaEffects())
        }
        return Effects(/* audioProcessors = */ emptyList(), /* videoEffects = */ videoEffects)
    }

    /** Short side (min of width/height) of [source]'s video track in px, or 0 if it can't be read. */
    private fun sourceShortSide(source: File): Int {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(source.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    val w = if (format.containsKey(MediaFormat.KEY_WIDTH)) format.getInteger(MediaFormat.KEY_WIDTH) else 0
                    val h = if (format.containsKey(MediaFormat.KEY_HEIGHT)) format.getInteger(MediaFormat.KEY_HEIGHT) else 0
                    if (w > 0 && h > 0) return minOf(w, h)
                }
            }
            0
        } catch (e: IOException) {
            0 // unreadable header → skip the cap (Transformer picks a default); never crash
        } catch (e: IllegalArgumentException) {
            0
        } finally {
            extractor.release()
        }
    }

    /** Run [composition] → [outputFile], bridging the async [Transformer] callbacks into a suspend call. */
    private suspend fun runTransformer(
        composition: Composition,
        outputFile: File,
        onProgress: (Float) -> Unit,
    ) = withContext(Dispatchers.Main) {
        // Transformer requires a Looper thread; build/start/poll/cancel all happen on Main.
        val done = CompletableDeferred<Unit>()
        val transformer = Transformer.Builder(context)
            .addListener(object : Transformer.Listener {
                override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                    done.complete(Unit)
                }

                override fun onError(
                    composition: Composition,
                    exportResult: ExportResult,
                    exportException: ExportException,
                ) {
                    done.completeExceptionally(exportException)
                }
            })
            .build()

        coroutineScope {
            val poller = launch {
                val holder = ProgressHolder()
                while (isActive) {
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) {
                        onProgress((holder.progress / 100f).coerceIn(0f, 1f))
                    }
                    delay(PROGRESS_POLL_MS)
                }
            }
            try {
                transformer.start(composition, outputFile.absolutePath)
                done.await()
            } finally {
                poller.cancel()
                transformer.cancel() // safe after completion; aborts the export if we were canceled mid-flight
            }
        }
    }

    /** One source frame's duration in ms (for the seam offset), from the track frame rate; 30fps fallback. */
    private fun frameDurationMs(source: File): Long {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(source.absolutePath)
            var fps = DEFAULT_FRAME_RATE
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                if (format.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true) {
                    // frameRateOrDefault() is type-tolerant: KEY_FRAME_RATE is sometimes a Float, and
                    // getInteger() would throw ClassCastException (which the broad catch below would
                    // then mask by defaulting the WHOLE function). Reuse the shared, unit-tested util.
                    fps = format.frameRateOrDefault()
                    break
                }
            }
            (1000L / fps.coerceAtLeast(1)).coerceAtLeast(1L)
        } catch (e: IOException) {
            // setDataSource() couldn't read the file — fall back to the 30fps seam offset.
            1000L / DEFAULT_FRAME_RATE
        } catch (e: IllegalArgumentException) {
            // Malformed data source / track format — same safe fallback (never mask a real crash
            // behind a broad catch, ANDROID_STANDARDS §3).
            1000L / DEFAULT_FRAME_RATE
        } finally {
            extractor.release()
        }
    }

    private companion object {
        const val REVERSE_BUDGET = 0.8f
        const val PROGRESS_POLL_MS = 100L
        const val DEFAULT_FRAME_RATE = 30
    }
}
