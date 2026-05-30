package io.github.stozo04.openloop.media

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure-JVM tests for the output-resolution cap shared by [VideoReverser] and the render path
 * ([Media3VideoProcessor]). Verifies a 4K/8K source downscales to a ≤1080p short side, footage at or
 * below the cap is left alone (never upscaled), and dimensions are always even (AVC requires it).
 */
class MediaDimensionsTest {

    @Test
    fun `4K landscape caps to 1080p short side`() {
        assertEquals(1920 to 1080, cappedToShortSide(3840, 2160))
    }

    @Test
    fun `4K portrait caps to 1080p short side`() {
        assertEquals(1080 to 1920, cappedToShortSide(2160, 3840))
    }

    @Test
    fun `8K landscape caps to 1080p short side`() {
        assertEquals(1920 to 1080, cappedToShortSide(7680, 4320))
    }

    @Test
    fun `1080p is unchanged`() {
        assertEquals(1920 to 1080, cappedToShortSide(1920, 1080))
        assertEquals(1080 to 1920, cappedToShortSide(1080, 1920))
    }

    @Test
    fun `720p is not upscaled`() {
        assertEquals(1280 to 720, cappedToShortSide(1280, 720))
    }

    @Test
    fun `tall phone aspect caps by the short side and keeps a tall long side`() {
        // 1440p-class portrait: short side 1440 → 1080; long side scales proportionally (and evens).
        val (w, h) = cappedToShortSide(1440, 3120)
        assertEquals(1080, w)
        assertEquals(2340, h) // 3120 * (1080/1440) = 2340, already even
    }

    @Test
    fun `result dimensions are always even`() {
        val (w, h) = cappedToShortSide(1081, 2401) // odd short side just over the cap
        assertEquals(0, w % 2)
        assertEquals(0, h % 2)
        assertEquals(1080, w)
    }

    @Test
    fun `odd sub-cap dimensions are evened down`() {
        assertEquals(1280 to 718, cappedToShortSide(1281, 719))
    }

    @Test
    fun `zero or unknown dimensions are returned as-is`() {
        assertEquals(0 to 0, cappedToShortSide(0, 0))
    }

    @Test
    fun `evenDown floors odd values and never goes below two`() {
        assertEquals(1080, evenDown(1080))
        assertEquals(1080, evenDown(1081))
        assertEquals(2, evenDown(1))
        assertEquals(2, evenDown(0))
    }
}
