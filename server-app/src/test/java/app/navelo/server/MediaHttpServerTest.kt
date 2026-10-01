package app.navelo.server

import app.navelo.shared.ItemType
import app.navelo.shared.ItemsResponse
import app.navelo.shared.LibraryResponse
import app.navelo.shared.MediaItem
import app.navelo.shared.MediaMetadata
import app.navelo.shared.MetadataMatchRequest
import app.navelo.shared.MetadataSearchResponse
import app.navelo.shared.MetadataSnapshot
import app.navelo.shared.PairRequest
import app.navelo.shared.PairTicket
import app.navelo.shared.PlaybackReport
import app.navelo.shared.Protocol
import app.navelo.shared.ServerInfo
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MediaHttpServerTest {
    private lateinit var backend: FakeBackend
    private lateinit var access: PatternMediaAccess
    private lateinit var thumbnails: FakeThumbnailAccess
    private lateinit var resources: CountingResources
    private lateinit var metadata: FakeMetadataAccess
    private lateinit var server: MediaHttpServer
    private var port: Int = 0

    @Before
    fun startServer() {
        backend = FakeBackend()
        access = PatternMediaAccess()
        thumbnails = FakeThumbnailAccess()
        resources = CountingResources()
        metadata = FakeMetadataAccess()
        server = MediaHttpServer(backend, access, resources, 0, thumbnails, metadata)
        server.start(5_000, false)
        port = server.listeningPort
    }

    @After
    fun stopServer() {
        server.stop()
    }

    @Test
    fun serverInfoIsPublicButLibraryMediaAndOptionsRequireBearer() {
        assertEquals(200, request("GET", "/api/v1/server").status)
        assertEquals(401, request("GET", "/api/v1/library").status)
        assertEquals(401, request("GET", "/api/v1/items").status)
        assertEquals(401, request("POST", "/api/v1/rescan").status)
        assertEquals(401, request("POST", "/api/v1/playback").status)
        assertEquals(401, request("GET", "/media/huge").status)
        assertEquals(401, request("GET", "/media/huge?token=trusted-token").status)
        assertEquals(401, request("OPTIONS", "/media/huge").status)
        assertEquals(401, request("GET", "/api/v1/thumbnail/huge").status)
        assertEquals(401, request("GET", "/api/v1/thumbnail/huge?token=trusted-token").status)
        assertEquals(401, request("GET", "/api/v1/metadata").status)
        assertEquals(401, request("GET", "/api/v1/metadata/search?itemId=small&q=Small").status)
        assertEquals(401, request("POST", "/api/v1/metadata/clear").status)
        assertEquals(401, request("GET", "/api/v1/artwork/opaque").status)
        assertEquals(0, access.opens.get())
        assertEquals(0, thumbnails.loads.get())

        val library = request("GET", "/api/v1/library", authHeaders())
        assertEquals(200, library.status)
        assertTrue(library.body.toString(Charsets.UTF_8).contains("\"revision\":7"))
    }

    @Test
    fun headReturnsExactLongLengthAndNoBody() {
        val response = request("HEAD", "/media/huge", authHeaders())

        assertEquals(200, response.status)
        assertEquals(MEDIA_SIZE.toString(), response.header("content-length"))
        assertEquals("bytes", response.header("accept-ranges"))
        assertEquals(0, response.body.size)
        assertEquals(listOf(0L), access.verifiedStarts)
        assertEquals(0, resources.active.get())
    }

    @Test
    fun fullGetReturnsOriginalBytesWithFixedLength() {
        val response = request("GET", "/media/small", authHeaders())

        assertEquals(200, response.status)
        assertEquals("32", response.header("content-length"))
        assertEquals("bytes", response.header("accept-ranges"))
        assertArrayEquals(pattern(0, 32), response.body)
        assertEquals(32, access.bytesRead.get())
        assertEquals(0, resources.active.get())
    }

    @Test
    fun allSingleRangeFormsSeekAndReturnOnlyRequestedOriginalBytes() {
        val nearEnd = MEDIA_SIZE - 16
        val bounded = request(
            "GET",
            "/media/huge",
            authHeaders("Range" to "bytes=$nearEnd-${MEDIA_SIZE - 1}"),
        )
        assertRange(bounded, nearEnd, MEDIA_SIZE - 1)

        val suffix = request("GET", "/media/huge", authHeaders("Range" to "bytes=-4"))
        assertRange(suffix, MEDIA_SIZE - 4, MEDIA_SIZE - 1)

        val openEnded = request(
            "GET",
            "/media/huge",
            authHeaders("Range" to "bytes=${MEDIA_SIZE - 5}-"),
        )
        assertRange(openEnded, MEDIA_SIZE - 5, MEDIA_SIZE - 1)

        val ordinary = request("GET", "/media/huge", authHeaders("Range" to "bytes=10-19"))
        assertRange(ordinary, 10, 19)

        assertEquals(listOf(nearEnd, MEDIA_SIZE - 4, MEDIA_SIZE - 5, 10L), access.openedStarts)
        assertEquals(35, access.bytesRead.get())
        assertTrue("The provider must never receive a large read for a tiny range", access.largestRead.get() <= 16)
        assertEquals(4, resources.acquired.get())
        assertEquals(4, resources.released.get())
        assertEquals(0, resources.active.get())
    }

    @Test
    fun invalidAndMultipartRangesReturn416WithFileSize() {
        val pastEnd = request(
            "GET",
            "/media/huge",
            authHeaders("Range" to "bytes=$MEDIA_SIZE-"),
        )
        assertEquals(416, pastEnd.status)
        assertEquals("bytes */$MEDIA_SIZE", pastEnd.header("content-range"))

        val multipart = request(
            "GET",
            "/media/huge",
            authHeaders("Range" to "bytes=0-1,4-5"),
        )
        assertEquals(416, multipart.status)
        assertEquals("bytes */$MEDIA_SIZE", multipart.header("content-range"))
        assertEquals(0, access.bytesRead.get())
    }

    @Test
    fun nonSeekableProviderFailsRangeInsteadOfReadingFromTheBeginning() {
        access.seekable = false
        val response = request("GET", "/media/huge", authHeaders("Range" to "bytes=1000-1009"))

        assertEquals(416, response.status)
        assertEquals("bytes */$MEDIA_SIZE", response.header("content-range"))
        assertEquals(0, access.bytesRead.get())
        assertEquals(0, resources.active.get())
    }

    @Test
    fun authenticatedOptionsAndRangeHeadExposePlaybackContract() {
        val options = request("OPTIONS", "/media/huge", authHeaders())
        assertEquals(204, options.status)
        assertEquals("GET, HEAD, OPTIONS", options.header("allow"))

        val start = MEDIA_SIZE - 9
        val head = request("HEAD", "/media/huge", authHeaders("Range" to "BYTES=$start-"))
        assertEquals(206, head.status)
        assertEquals("bytes $start-${MEDIA_SIZE - 1}/$MEDIA_SIZE", head.header("content-range"))
        assertEquals("9", head.header("content-length"))
        assertEquals(0, head.body.size)
        assertTrue(access.verifiedStarts.contains(start))
    }

    @Test
    fun jsonPostsRequireSmallExplicitContentLength() {
        val noLength = rawRequest(
            "POST /api/v1/pair HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n",
        )
        assertEquals(411, noLength.status)

        val tooLarge = rawRequest(
            "POST /api/v1/pair HTTP/1.1\r\nHost: localhost\r\nContent-Length: 20000\r\nConnection: close\r\n\r\n",
        )
        assertEquals(413, tooLarge.status)

        val secret = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32))
        val body = Protocol.json.encodeToString(
            PairRequest.serializer(),
            PairRequest("tv", "Living Room TV", secret),
        ).toByteArray()
        val accepted = request("POST", "/api/v1/pair", body = body)
        assertEquals(202, accepted.status)
        assertEquals(1, backend.pairRequests.get())
    }

    @Test
    fun unknownMediaDoesNotOpenProvider() {
        assertEquals(404, request("GET", "/media/missing", authHeaders()).status)
        assertEquals(0, access.opens.get())
    }

    @Test
    fun authenticatedThumbnailIsJpegAndSupportsEntityTagRevalidation() {
        val response = request("GET", "/api/v1/thumbnail/small", authHeaders())

        assertEquals(200, response.status)
        assertEquals("image/jpeg", response.header("content-type"))
        assertEquals("private, max-age=86400", response.header("cache-control"))
        assertEquals("nosniff", response.header("x-content-type-options"))
        assertArrayEquals(FakeThumbnailAccess.JPEG, response.body)
        val entityTag = response.header("etag")
        assertEquals("\"small-etag\"", entityTag)
        assertEquals(1, thumbnails.loads.get())

        val notModified = request(
            "GET",
            "/api/v1/thumbnail/small",
            authHeaders("If-None-Match" to requireNotNull(entityTag)),
        )
        assertEquals(304, notModified.status)
        assertEquals(0, notModified.body.size)
        assertEquals(entityTag, notModified.header("etag"))
    }

    @Test
    fun thumbnailRejectsUnsupportedMethodsAndUnknownIdsWithoutExtraction() {
        assertEquals(405, request("HEAD", "/api/v1/thumbnail/small", authHeaders()).status)
        assertEquals(404, request("GET", "/api/v1/thumbnail/missing", authHeaders()).status)
        assertEquals(0, thumbnails.loads.get())
    }

    @Test
    fun metadataEndpointsRequireExpectedMethodsAndReturnOnlyLocalArtworkReferences() {
        val snapshot = request("GET", "/api/v1/metadata", authHeaders())
        assertEquals(200, snapshot.status)
        assertTrue(snapshot.body.toString(Charsets.UTF_8).contains("\"configured\":true"))

        val search = request("GET", "/api/v1/metadata/search?itemId=small&q=Small", authHeaders())
        assertEquals(200, search.status)
        val searchJson = search.body.toString(Charsets.UTF_8)
        assertTrue(searchJson.contains("/api/v1/artwork/opaque"))
        assertFalse(searchJson.contains("https://"))

        val matchBody = Protocol.json.encodeToString(
            MetadataMatchRequest.serializer(),
            MetadataMatchRequest("small", 42, "movie"),
        ).toByteArray()
        assertEquals(200, request("POST", "/api/v1/metadata/match", authHeaders(), matchBody).status)
        assertEquals(1, metadata.matches.get())
        assertEquals(200, request("POST", "/api/v1/metadata/clear", authHeaders(), byteArrayOf(1)).status)
        assertEquals(1, metadata.clears.get())

        assertEquals(405, request("POST", "/api/v1/metadata", authHeaders(), byteArrayOf(1)).status)
        assertEquals(405, request("POST", "/api/v1/metadata/search", authHeaders(), byteArrayOf(1)).status)
        assertEquals(405, request("GET", "/api/v1/metadata/match", authHeaders()).status)
        assertEquals(405, request("GET", "/api/v1/metadata/clear", authHeaders()).status)
    }

    @Test
    fun artworkIsAuthenticatedCacheableAndRejectsUnknownReferences() {
        val response = request("GET", "/api/v1/artwork/opaque", authHeaders())
        assertEquals(200, response.status)
        assertEquals("image/jpeg", response.header("content-type"))
        assertEquals("nosniff", response.header("x-content-type-options"))
        assertArrayEquals(FakeThumbnailAccess.JPEG, response.body)
        val tag = response.header("etag")

        val cached = request("GET", "/api/v1/artwork/opaque", authHeaders("If-None-Match" to requireNotNull(tag)))
        assertEquals(304, cached.status)
        assertEquals(404, request("GET", "/api/v1/artwork/https:%2F%2Fevil.example%2Fx", authHeaders()).status)
        assertEquals(405, request("HEAD", "/api/v1/artwork/opaque", authHeaders()).status)
    }

    @Test
    fun providerMimeTypeCannotInjectResponseHeaders() {
        val response = request("GET", "/media/untrusted-mime", authHeaders())

        assertEquals(200, response.status)
        assertEquals("application/octet-stream", response.header("content-type"))
        assertEquals("nosniff", response.header("x-content-type-options"))
        assertEquals(null, response.header("x-injected"))
    }

    private fun assertRange(response: HttpResult, start: Long, end: Long) {
        assertEquals(206, response.status)
        assertEquals("bytes $start-$end/$MEDIA_SIZE", response.header("content-range"))
        assertEquals((end - start + 1).toString(), response.header("content-length"))
        assertArrayEquals(pattern(start, (end - start + 1).toInt()), response.body)
    }

    private fun authHeaders(vararg extra: Pair<String, String>): Map<String, String> =
        linkedMapOf("Authorization" to "Bearer trusted-token", *extra)

    private fun request(
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0),
    ): HttpResult {
        val request = buildString {
            append("$method $path HTTP/1.1\r\n")
            append("Host: localhost\r\n")
            append("Connection: close\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            if (body.isNotEmpty()) append("Content-Length: ${body.size}\r\n")
            append("\r\n")
        }.toByteArray(Charsets.ISO_8859_1) + body
        return rawRequest(request)
    }

    private fun rawRequest(text: String): HttpResult = rawRequest(text.toByteArray(Charsets.ISO_8859_1))

    private fun rawRequest(request: ByteArray): HttpResult {
        val bytes = Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            socket.getOutputStream().write(request)
            socket.getOutputStream().flush()
            socket.shutdownOutput()
            val output = ByteArrayOutputStream()
            socket.getInputStream().copyTo(output)
            output.toByteArray()
        }
        val boundary = bytes.indexOfSequence("\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
        require(boundary >= 0) { "Invalid HTTP response: ${bytes.toString(Charsets.ISO_8859_1)}" }
        val headerText = bytes.copyOfRange(0, boundary).toString(Charsets.ISO_8859_1)
        val lines = headerText.split("\r\n")
        val status = lines.first().split(' ')[1].toInt()
        val headers = LinkedHashMap<String, MutableList<String>>()
        lines.drop(1).forEach { line ->
            val separator = line.indexOf(':')
            if (separator > 0) {
                headers.getOrPut(line.substring(0, separator).trim().lowercase()) { ArrayList() }
                    .add(line.substring(separator + 1).trim())
            }
        }
        return HttpResult(status, headers, bytes.copyOfRange(boundary + 4, bytes.size))
    }

    private data class HttpResult(
        val status: Int,
        val headers: Map<String, List<String>>,
        val body: ByteArray,
    ) {
        fun header(name: String): String? = headers[name.lowercase()]?.lastOrNull()
    }

    private class FakeBackend : ServerHttpFacade {
        val pairRequests = AtomicInteger()
        private val item = MediaItem(
            id = "huge",
            filename = "Huge.Movie.2160p.mkv",
            displayName = "Huge Movie",
            rootId = "root",
            relativePath = "Huge.Movie.2160p.mkv",
            size = MEDIA_SIZE,
            mimeType = "video/x-matroska",
            extension = "mkv",
            type = ItemType.VIDEO,
        )
        private val smallItem = item.copy(
            id = "small",
            filename = "Small.mp4",
            displayName = "Small",
            relativePath = "Small.mp4",
            size = 32,
            mimeType = "video/mp4",
            extension = "mp4",
        )
        private val untrustedMimeItem = smallItem.copy(
            id = "untrusted-mime",
            mimeType = "video/mp4\r\nX-Injected: yes",
        )
        override fun serverInfo() = ServerInfo("server", "Media Phone", libraryRevision = 7)
        override fun library() = LibraryResponse(7, emptyList())
        override fun items(offset: Int, limit: Int, revision: Long?, rootId: String?, parentId: String?) =
            ServerRuntime.ItemsPageResult.Success(ItemsResponse(7, listOf(item)))
        override fun rescan() = library()
        override fun media(id: String) = when (id) {
            item.id -> StoredMediaItem(item, "fake://huge")
            smallItem.id -> StoredMediaItem(smallItem, "fake://small")
            untrustedMimeItem.id -> StoredMediaItem(untrustedMimeItem, "fake://untrusted-mime")
            else -> null
        }
        override fun requestPair(request: PairRequest): PairTicket {
            pairRequests.incrementAndGet()
            return PairTicket("request", pin = "123456")
        }
        override fun pollPair(requestId: String, secret: String): PairTicket? = null
        override fun authenticate(token: String): TrustedRecord? = if (token == "trusted-token") {
            TrustedRecord("tv", "Living Room TV", 1, "hash")
        } else {
            null
        }
        override fun playback(report: PlaybackReport) = report
    }

    private class PatternMediaAccess : MediaAccess {
        val opens = AtomicInteger()
        val bytesRead = AtomicLong()
        val largestRead = AtomicInteger()
        val openedStarts = java.util.Collections.synchronizedList(ArrayList<Long>())
        val verifiedStarts = java.util.Collections.synchronizedList(ArrayList<Long>())
        @Volatile var seekable = true

        override fun open(item: StoredMediaItem): OpenMedia {
            opens.incrementAndGet()
            val mediaSize = item.item.size
            return object : OpenMedia {
                private val closed = AtomicBoolean()
                override val size: Long = mediaSize

                override fun openStream(start: Long): InputStream {
                    if (!seekable && start > 0) throw MediaSeekException(IllegalStateException("not seekable"))
                    openedStarts += start
                    return object : InputStream() {
                        private var position = start
                        override fun read(): Int {
                            if (position >= mediaSize) return -1
                            bytesRead.incrementAndGet()
                            return ((position++) and 0xff).toInt()
                        }
                        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                            if (position >= mediaSize) return -1
                            val count = minOf(length.toLong(), mediaSize - position).toInt()
                            largestRead.accumulateAndGet(count) { current, candidate -> maxOf(current, candidate) }
                            for (index in 0 until count) buffer[offset + index] = ((position + index) and 0xff).toByte()
                            position += count
                            bytesRead.addAndGet(count.toLong())
                            return count
                        }
                    }
                }

                override fun verifySeek(start: Long) {
                    if (!seekable && start > 0) throw MediaSeekException(IllegalStateException("not seekable"))
                    verifiedStarts += start
                }

                override fun close() {
                    closed.compareAndSet(false, true)
                }
            }
        }
    }

    private class CountingResources : StreamResources {
        val acquired = AtomicInteger()
        val released = AtomicInteger()
        val active = AtomicInteger()
        override fun acquire(): Closeable {
            acquired.incrementAndGet()
            active.incrementAndGet()
            val closed = AtomicBoolean()
            return Closeable {
                if (closed.compareAndSet(false, true)) {
                    active.decrementAndGet()
                    released.incrementAndGet()
                }
            }
        }
    }

    private class FakeThumbnailAccess : ThumbnailAccess {
        val loads = AtomicInteger()

        override fun load(item: StoredMediaItem): Thumbnail? {
            loads.incrementAndGet()
            return Thumbnail(JPEG, "${item.item.id}-etag")
        }

        companion object {
            val JPEG = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())
        }
    }

    private class FakeMetadataAccess : MetadataAccess {
        val matches = AtomicInteger()
        val clears = AtomicInteger()
        private val value = MediaMetadata(
            id = 42,
            title = "Small",
            posterUrl = "/api/v1/artwork/opaque",
            mediaType = "movie",
        )

        override fun snapshot() = MetadataSnapshot(3, configured = true, items = mapOf("small" to value))
        override fun search(itemId: String, query: String) = MetadataSearchResponse(listOf(value))
        override fun match(request: MetadataMatchRequest): MetadataSnapshot {
            matches.incrementAndGet()
            return snapshot()
        }
        override fun clear(): MetadataSnapshot {
            clears.incrementAndGet()
            return MetadataSnapshot(4, configured = true)
        }
        override fun artwork(id: String): Artwork? = if (id == "opaque") {
            Artwork(FakeThumbnailAccess.JPEG, "image/jpeg", "opaque-etag")
        } else {
            null
        }
    }

    private fun ByteArray.indexOfSequence(needle: ByteArray): Int {
        outer@ for (index in 0..size - needle.size) {
            for (needleIndex in needle.indices) if (this[index + needleIndex] != needle[needleIndex]) continue@outer
            return index
        }
        return -1
    }

    private fun pattern(start: Long, length: Int) = ByteArray(length) { ((start + it) and 0xff).toByte() }

    private companion object {
        const val MEDIA_SIZE = 45L * 1024 * 1024 * 1024
    }
}
