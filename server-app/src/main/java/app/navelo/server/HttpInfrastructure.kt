package app.navelo.server

import android.content.ContentResolver
import android.net.Uri
import android.os.ParcelFileDescriptor
import app.navelo.shared.LibraryResponse
import app.navelo.shared.PairRequest
import app.navelo.shared.PairTicket
import app.navelo.shared.PlaybackReport
import app.navelo.shared.ServerInfo
import app.navelo.shared.MetadataMatchRequest
import app.navelo.shared.MetadataSearchResponse
import app.navelo.shared.MetadataSnapshot
import fi.iki.elonen.NanoHTTPD
import java.io.Closeable
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking

internal interface ServerHttpFacade {
    fun serverInfo(): ServerInfo
    fun library(): LibraryResponse
    fun items(
        offset: Int,
        limit: Int,
        revision: Long?,
        rootId: String?,
        parentId: String?,
    ): ServerRuntime.ItemsPageResult
    fun rescan(): LibraryResponse
    fun media(id: String): StoredMediaItem?
    fun requestPair(request: PairRequest): PairTicket
    fun pollPair(requestId: String, secret: String): PairTicket?
    fun authenticate(token: String): TrustedRecord?
    fun playback(report: PlaybackReport): PlaybackReport
}

internal class RuntimeHttpFacade(
    private val runtime: ServerRuntime,
) : ServerHttpFacade {
    override fun serverInfo() = runBlocking { runtime.serverInfo() }
    override fun library() = runBlocking { runtime.libraryResponse() }
    override fun items(offset: Int, limit: Int, revision: Long?, rootId: String?, parentId: String?) =
        runBlocking { runtime.itemsResponse(offset, limit, revision, rootId, parentId) }
    override fun rescan(): LibraryResponse = runBlocking {
        val generation = runtime.requestRescan()
        runtime.libraryResponse().copy(scanGeneration = generation)
    }
    override fun media(id: String) = runBlocking { runtime.media(id) }
    override fun requestPair(request: PairRequest) = runtime.requestPair(request)
    override fun pollPair(requestId: String, secret: String) = runtime.pollPair(requestId, secret)
    override fun authenticate(token: String) = runtime.authenticate(token)
    override fun playback(report: PlaybackReport) = runtime.acceptPlayback(report)
}

internal class RuntimeMetadataAccess(
    private val runtime: ServerRuntime,
) : MetadataAccess {
    override fun snapshot(): MetadataSnapshot = runtime.metadataSnapshot()
    override fun search(itemId: String, query: String): MetadataSearchResponse = runtime.searchMetadata(itemId, query)
    override fun match(request: MetadataMatchRequest): MetadataSnapshot = runtime.matchMetadata(request)
    override fun clear(): MetadataSnapshot = runtime.clearMetadata()
    override fun artwork(id: String): Artwork? = runtime.artwork(id)
}

internal interface MediaAccess {
    @Throws(Exception::class)
    fun open(item: StoredMediaItem): OpenMedia
}

internal interface OpenMedia : Closeable {
    val size: Long
    @Throws(Exception::class)
    fun openStream(start: Long): InputStream
    @Throws(Exception::class)
    fun verifySeek(start: Long)
}

internal class AndroidMediaAccess(
    private val resolver: ContentResolver,
) : MediaAccess {
    override fun open(item: StoredMediaItem): OpenMedia {
        val descriptor = resolver.openFileDescriptor(Uri.parse(item.uri), "r")
            ?: throw MediaUnavailableException()
        val descriptorSize = runCatching { descriptor.statSize }.getOrDefault(-1)
        val size = descriptorSize.takeIf { it >= 0 } ?: item.item.size.takeIf { it > 0 }
        if (size == null) {
            descriptor.close()
            throw MediaUnavailableException("The storage provider did not report a file size")
        }
        return DescriptorMedia(descriptor, size)
    }

    private class DescriptorMedia(
        private val descriptor: ParcelFileDescriptor,
        override val size: Long,
    ) : OpenMedia {
        private var transferred = false

        override fun openStream(start: Long): InputStream {
            check(!transferred)
            val stream = ParcelFileDescriptor.AutoCloseInputStream(descriptor)
            try {
                if (start > 0) stream.channel.position(start)
                transferred = true
                return stream
            } catch (failure: Exception) {
                stream.close()
                transferred = true
                throw MediaSeekException(failure)
            }
        }

        override fun verifySeek(start: Long) {
            if (start <= 0) return
            check(!transferred)
            try {
                ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { it.channel.position(start) }
                transferred = true
            } catch (failure: Exception) {
                transferred = true
                throw MediaSeekException(failure)
            }
        }

        override fun close() {
            if (!transferred) descriptor.close()
            transferred = true
        }
    }
}

internal class MediaUnavailableException(
    message: String = "The media file is temporarily unavailable",
) : Exception(message)

internal class MediaSeekException(cause: Throwable) : Exception(cause)

internal class BoundedAsyncRunner(
    maximumConnections: Int,
) : NanoHTTPD.AsyncRunner {
    private val sequence = AtomicInteger()
    private val clients = Collections.synchronizedSet(mutableSetOf<NanoHTTPD.ClientHandler>())
    private val executor = ThreadPoolExecutor(
        0,
        maximumConnections,
        30,
        TimeUnit.SECONDS,
        SynchronousQueue(),
        ThreadFactory { task -> Thread(task, "Navelo-http-${sequence.incrementAndGet()}").apply { isDaemon = true } },
    )

    override fun exec(code: NanoHTTPD.ClientHandler) {
        clients += code
        try {
            executor.execute(code)
        } catch (_: RejectedExecutionException) {
            clients -= code
            code.close()
        }
    }

    override fun closed(clientHandler: NanoHTTPD.ClientHandler) {
        clients -= clientHandler
    }

    override fun closeAll() {
        val snapshot = synchronized(clients) { clients.toList() }
        snapshot.forEach { it.close() }
        clients.clear()
        executor.shutdownNow()
    }
}
