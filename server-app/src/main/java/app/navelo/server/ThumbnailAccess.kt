package app.navelo.server

import android.content.ContentResolver
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import app.navelo.shared.ItemType
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

internal data class Thumbnail(
    val bytes: ByteArray,
    val entityTag: String,
)

internal fun interface ThumbnailAccess {
    @Throws(Exception::class)
    fun load(item: StoredMediaItem): Thumbnail?

    companion object {
        val NONE = ThumbnailAccess { null }
    }
}

/**
 * Extracts one representative frame directly from a SAF descriptor. The result is deliberately
 * small and cached in the app's disposable cache directory; video bytes are never copied or
 * buffered by this code.
 */
internal class AndroidThumbnailAccess(
    context: Context,
    private val resolver: ContentResolver = context.applicationContext.contentResolver,
) : ThumbnailAccess {
    private val cacheDirectory = File(context.applicationContext.cacheDir, "video-thumbnails-v1")

    override fun load(item: StoredMediaItem): Thumbnail? {
        if (item.item.type != ItemType.VIDEO) return null
        val key = cacheKey(item)
        val cached = File(cacheDirectory, "$key.jpg")
        readCached(cached)?.let { return Thumbnail(it, key) }

        if (!generationQueueSlots.tryAcquire()) throw ThumbnailBusyException()
        try {
            val locked = try {
                extractionLock.tryLock(EXTRACTION_WAIT_SECONDS, TimeUnit.SECONDS)
            } catch (failure: InterruptedException) {
                Thread.currentThread().interrupt()
                throw ThumbnailBusyException(failure)
            }
            if (!locked) throw ThumbnailBusyException()
            try {
                readCached(cached)?.let { return Thumbnail(it, key) }
                val bytes = extract(item) ?: return null
                cacheDirectory.mkdirs()
                val temporary = File(cacheDirectory, "$key.tmp")
                try {
                    temporary.outputStream().use { output ->
                        output.write(bytes)
                        output.flush()
                    }
                    if (!temporary.renameTo(cached)) {
                        cached.outputStream().use { it.write(bytes) }
                        temporary.delete()
                    }
                    cached.setLastModified(System.currentTimeMillis())
                    trimCache(cached)
                } finally {
                    temporary.delete()
                }
                return Thumbnail(bytes, key)
            } finally {
                extractionLock.unlock()
            }
        } finally {
            generationQueueSlots.release()
        }
    }

    private fun readCached(file: File): ByteArray? {
        if (!file.isFile || file.length() !in 1..MAX_THUMBNAIL_BYTES) return null
        return runCatching {
            file.setLastModified(System.currentTimeMillis())
            file.readBytes()
        }.getOrNull()
    }

    private fun extract(item: StoredMediaItem): ByteArray? = try {
        resolver.openFileDescriptor(Uri.parse(item.uri), "r")?.use { descriptor ->
            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(descriptor.fileDescriptor)
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull()
                    ?.coerceAtLeast(0)
                    ?: 0L
                val timeUs = representativeTimeUs(durationMs)
                val dimensions = videoDimensions(retriever)
                val frame = if (Build.VERSION.SDK_INT >= 27) {
                    scaledFrame(retriever, timeUs, dimensions)
                        ?: fullFrameIfSafe(retriever, timeUs, dimensions)
                } else {
                    fullFrameIfSafe(retriever, timeUs, dimensions)
                }
                if (frame == null) return@use null
                try {
                    val bounded = bound(frame)
                    try {
                        encode(bounded)
                    } finally {
                        if (bounded !== frame) bounded.recycle()
                    }
                } finally {
                    frame.recycle()
                }
            } finally {
                retriever.release()
            }
        }
    } catch (failure: SecurityException) {
        throw failure
    } catch (_: OutOfMemoryError) {
        null
    } catch (_: RuntimeException) {
        null
    }

    private fun videoDimensions(retriever: MediaMetadataRetriever): VideoDimensions? {
        val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        if (width == null || height == null || width <= 0 || height <= 0) return null
        val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
        return VideoDimensions(width, height, rotation)
    }

    private fun scaledFrame(
        retriever: MediaMetadataRetriever,
        timeUs: Long,
        dimensions: VideoDimensions?,
    ): Bitmap? {
        if (Build.VERSION.SDK_INT < 27 || dimensions == null) return null
        val displayWidth = if (dimensions.rotation % 180 == 0) dimensions.width else dimensions.height
        val displayHeight = if (dimensions.rotation % 180 == 0) dimensions.height else dimensions.width
        val (targetWidth, targetHeight) = ThumbnailPolicy.boundedSize(displayWidth, displayHeight)
        return try {
            retriever.getScaledFrameAtTime(
                timeUs,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                targetWidth,
                targetHeight,
            )
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun fullFrameIfSafe(
        retriever: MediaMetadataRetriever,
        timeUs: Long,
        dimensions: VideoDimensions?,
    ): Bitmap? {
        if (!ThumbnailPolicy.allowsFullFrame(dimensions)) return null
        return retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
    }

    private fun bound(bitmap: Bitmap): Bitmap {
        val (width, height) = ThumbnailPolicy.boundedSize(bitmap.width, bitmap.height)
        return if (width == bitmap.width && height == bitmap.height) bitmap
        else Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    private fun encode(bitmap: Bitmap): ByteArray {
        val output = ByteArrayOutputStream(128 * 1024)
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output))
        return output.toByteArray().also { check(it.size <= MAX_THUMBNAIL_BYTES) }
    }

    private fun trimCache(protectedFile: File) {
        val files = cacheDirectory.listFiles { file -> file.isFile && file.extension == "jpg" }
            ?.sortedBy(File::lastModified)
            ?: return
        var bytes = files.sumOf(File::length)
        for (file in files) {
            if (bytes <= MAX_CACHE_BYTES) break
            if (file == protectedFile) continue
            val length = file.length()
            if (file.delete()) bytes -= length
        }
    }

    private fun cacheKey(item: StoredMediaItem): String {
        val fingerprint = buildString {
            append(item.item.id)
            append('\u0000')
            append(item.item.size)
            append('\u0000')
            append(item.item.modifiedTime)
        }.toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256").digest(fingerprint)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun representativeTimeUs(durationMs: Long): Long {
        if (durationMs <= 0) return 0
        return (durationMs / 10).coerceIn(1_000L, 60_000L).coerceAtMost(durationMs) * 1_000L
    }

    private companion object {
        const val JPEG_QUALITY = 82
        const val MAX_THUMBNAIL_BYTES = 2L * 1024 * 1024
        const val MAX_CACHE_BYTES = 48L * 1024 * 1024
        const val EXTRACTION_WAIT_SECONDS = 2L
        val extractionLock = ReentrantLock()
        // At most one request extracts and one waits; all other cover requests fail fast with 503.
        val generationQueueSlots = Semaphore(2, true)
    }
}

internal class ThumbnailBusyException(cause: Throwable? = null) : IOException(
    "Thumbnail generation is busy",
    cause,
)
