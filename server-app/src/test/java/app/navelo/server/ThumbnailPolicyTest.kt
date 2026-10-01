package app.navelo.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailPolicyTest {
    @Test
    fun fullFrameFallbackRequiresKnownDimensionsWithinEightMiB() {
        assertTrue(ThumbnailPolicy.allowsFullFrame(VideoDimensions(1920, 1080)))
        assertFalse(ThumbnailPolicy.allowsFullFrame(VideoDimensions(3840, 2160)))
        assertFalse(ThumbnailPolicy.allowsFullFrame(VideoDimensions(Int.MAX_VALUE, Int.MAX_VALUE)))
        assertFalse(ThumbnailPolicy.allowsFullFrame(VideoDimensions(0, 1080)))
        assertFalse(ThumbnailPolicy.allowsFullFrame(null))
    }

    @Test
    fun boundedSizePreservesLandscapeAndPortraitAspect() {
        assertEquals(640 to 360, ThumbnailPolicy.boundedSize(3840, 2160))
        assertEquals(203 to 360, ThumbnailPolicy.boundedSize(1080, 1920))
        assertEquals(320 to 180, ThumbnailPolicy.boundedSize(320, 180))
    }
}
