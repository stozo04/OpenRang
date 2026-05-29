package com.openrang.app.media

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.IOException

/**
 * Instrumented tests for [VideoReverser] — requires a real [android.media.MediaCodec], so it cannot
 * run on the JVM. Uses a fixture MP4 from `app/src/androidTest/assets/trim_fixture.mp4`; if that
 * asset is absent the tests SKIP (via [assumeTrue]) rather than fail, so CI without the fixture is
 * green while a device run with the fixture exercises the real two-pass pipeline.
 *
 * Drop any short (~1–3 s) H.264 MP4 at that asset path to enable these. The end-to-end reverse
 * correctness (first frame ≈ last source frame) is otherwise validated by the manual QA walkthrough
 * in the slice doc (capture → Trim → NEXT on a device).
 */
@RunWith(AndroidJUnit4::class)
class VideoReverserTest {

    private lateinit var scratchDir: File
    private lateinit var reverser: VideoReverser
    private lateinit var fixture: File
    private var durationMs: Long = 0L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        scratchDir = File(context.cacheDir, "test_reversed_${System.nanoTime()}").apply { mkdirs() }
        reverser = VideoReverser(scratchDir)
        fixture = copyFixtureOrSkip(context)
        durationMs = MediaMetadataRetriever().use { r ->
            r.setDataSource(fixture.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }
        assumeTrue("fixture has no readable duration", durationMs > 0L)
    }

    @After
    fun tearDown() {
        scratchDir.deleteRecursively()
    }

    @Test
    fun reverse_producesOutput_andLeavesNoIntermediate() = runBlocking {
        val output = reverser.reverse(fixture, 0L, durationMs)

        assertTrue("reversed output should exist", output.exists())
        assertTrue("reversed output should be non-empty", output.length() > 0L)
        // The keyframe-only intermediate must be deleted regardless of outcome.
        assertTrue(
            "no _intermediate_ files should remain",
            scratchDir.listFiles()?.none { it.name.startsWith("_intermediate_") } ?: true,
        )
    }

    @Test
    fun reverse_durationMatchesTrimWindow_withinOneFrame() = runBlocking {
        val output = reverser.reverse(fixture, 0L, durationMs)

        val outMs = MediaMetadataRetriever().use { r ->
            r.setDataSource(output.absolutePath)
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        }
        // Allow ~2 frames (≈66 ms @30fps) of codec round-trip slack.
        assertTrue("reversed duration $outMs ≉ source $durationMs", kotlin.math.abs(outMs - durationMs) <= 70L)
    }

    @Test
    fun reverse_isCached_onIdenticalSourceAndTrim() = runBlocking {
        val first = reverser.reverse(fixture, 0L, durationMs)
        val firstModified = first.lastModified()

        val second = reverser.reverse(fixture, 0L, durationMs)

        assertEquals("identical request should return the cached path", first.absolutePath, second.absolutePath)
        assertEquals("cache hit must not re-encode", firstModified, second.lastModified())
    }

    /** Copy the fixture asset into the cache dir; SKIP the test if the asset isn't bundled. */
    private fun copyFixtureOrSkip(context: Context): File {
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val dest = File(context.cacheDir, "trim_fixture.mp4")
        try {
            testAssets.open("trim_fixture.mp4").use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            }
        } catch (e: IOException) {
            assumeTrue("trim_fixture.mp4 not bundled in androidTest/assets — skipping", false)
        }
        return dest
    }
}
