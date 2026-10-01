package app.navelo.server

import kotlin.math.roundToInt

internal data class VideoDimensions(
    val width: Int,
    val height: Int,
    val rotation: Int = 0,
)

internal object ThumbnailPolicy {
    const val MAX_WIDTH = 640
    const val MAX_HEIGHT = 360
    private const val BYTES_PER_DECODED_PIXEL = 4L
    private const val MAX_FULL_FRAME_BYTES = 8L * 1024 * 1024

    /** Full-frame decoding is only a fallback for known, modestly sized video frames. */
    fun allowsFullFrame(dimensions: VideoDimensions?): Boolean {
        if (dimensions == null || dimensions.width <= 0 || dimensions.height <= 0) return false
        val pixels = dimensions.width.toLong() * dimensions.height.toLong()
        return pixels <= MAX_FULL_FRAME_BYTES / BYTES_PER_DECODED_PIXEL
    }

    fun boundedSize(width: Int, height: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1 to 1
        if (width <= MAX_WIDTH && height <= MAX_HEIGHT) return width to height
        val scale = minOf(MAX_WIDTH.toDouble() / width, MAX_HEIGHT.toDouble() / height)
        return (width * scale).roundToInt().coerceAtLeast(1) to
            (height * scale).roundToInt().coerceAtLeast(1)
    }
}
