package app.navelo.server

import android.content.Context
import android.os.ParcelFileDescriptor
import app.navelo.shared.ApiError
import app.navelo.shared.ByteRanges
import app.navelo.shared.PairRequest
import app.navelo.shared.PlaybackReport
import app.navelo.shared.Protocol
import app.navelo.shared.MetadataMatchRequest
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString

internal class MediaHttpServer(
    private val backend: ServerHttpFacade,
    private val mediaAccess: MediaAccess,
    private val resources: StreamResources = StreamResources.NONE,
    port: Int = Protocol.PORT,
    private val thumbnailAccess: ThumbnailAccess = ThumbnailAccess.NONE,
    private val metadataAccess: MetadataAccess = MetadataAccess.NONE,
) : NanoHTTPD(port) {
    constructor(context: Context, runtime: ServerRuntime, port: Int = Protocol.PORT) : this(
        RuntimeHttpFacade(runtime),
        AndroidMediaAccess(context.applicationContext.contentResolver),
        AndroidStreamResources(context),
        port,
        AndroidThumbnailAccess(context),
        RuntimeMetadataAccess(runtime),
    )
    private val streamSlots = java.util.concurrent.Semaphore(4)
    private val apiSlots = java.util.concurrent.Semaphore(8)
    init { setAsyncRunner(BoundedAsyncRunner(12)) }
    private val pairLimiter = RequestRateLimiter(maximum = 5, windowMs = 60_000)
    private val pollLimiter = RequestRateLimiter(maximum = 60, windowMs = 60_000)

    override fun serve(session: IHTTPSession): Response {
        val mediaGet = session.method == Method.GET && session.uri.trimEnd('/').startsWith("/media/")
        if (!mediaGet && !apiSlots.tryAcquire()) return busy()
        return try {
            route(session)
        } catch (_: BodyTooLargeException) {
            error(PAYLOAD_TOO_LARGE, "request_too_large", "The request is too large").apply { closeConnection(true) }
        } catch (_: LengthRequiredException) {
            error(LENGTH_REQUIRED, "content_length_required", "A valid Content-Length header is required")
                .apply { closeConnection(true) }
        } catch (_: IllegalArgumentException) {
            error(Response.Status.BAD_REQUEST, "invalid_request", "The request is invalid").apply { closeConnection(true) }
        } catch (_: SecurityException) {
            error(Response.Status.FORBIDDEN, "access_denied", "Access was denied")
        } catch (failure: MetadataNotFoundException) {
            error(Response.Status.NOT_FOUND, failure.code, failure.message.orEmpty())
        } catch (failure: MetadataBusyException) {
            error(Response.Status.SERVICE_UNAVAILABLE, failure.code, failure.message.orEmpty())
                .apply { addHeader("Retry-After", "2") }
        } catch (failure: MetadataUnavailableException) {
            error(Response.Status.SERVICE_UNAVAILABLE, failure.code, failure.message.orEmpty())
                .apply { addHeader("Retry-After", "5") }
        } catch (_: Exception) {
            error(Response.Status.INTERNAL_ERROR, "server_error", "Navelo could not complete the request")
        } finally {
            if (!mediaGet) apiSlots.release()
        }
    }

    override fun useGzipWhenAccepted(response: Response): Boolean = false

    private fun route(session: IHTTPSession): Response {
        val path = session.uri.trimEnd('/').ifEmpty { "/" }
        if (path == "/api/v1/server") {
            if (session.method != Method.GET) return methodNotAllowed("GET")
            return json(Response.Status.OK, backend.serverInfo())
        }
        if (path == "/api/v1/pair") {
            if (session.method != Method.POST) return methodNotAllowed("POST")
            val remote = session.remoteIpAddress ?: "unknown"
            if (!pairLimiter.allow(remote)) return error(TOO_MANY_REQUESTS, "rate_limited", "Please wait before trying again")
            val request = decodeBody<PairRequest>(session)
            return json(Response.Status.ACCEPTED, backend.requestPair(request))
        }
        if (path.startsWith("/api/v1/pair/") && path.length > "/api/v1/pair/".length) {
            if (session.method != Method.GET) return methodNotAllowed("GET")
            val requestId = path.substringAfterLast('/')
            val remote = session.remoteIpAddress ?: "unknown"
            if (!pollLimiter.allow("$remote:$requestId")) {
                return error(TOO_MANY_REQUESTS, "rate_limited", "Please wait before checking again")
            }
            val secret = session.headers["x-pair-secret"]
                ?: return error(Response.Status.UNAUTHORIZED, "missing_pair_secret", "Pairing secret required")
            val ticket = backend.pollPair(requestId, secret)
                ?: return error(Response.Status.NOT_FOUND, "pairing_not_found", "Pairing request not found")
            return json(Response.Status.OK, ticket)
        }

        authenticate(session) ?: return error(
            Response.Status.UNAUTHORIZED,
            "unauthorized",
            "A trusted Navelo device is required",
        ).also { it.addHeader("WWW-Authenticate", "Bearer") }

        if (path.startsWith("/media/") && path.length > "/media/".length) {
            return serveMedia(session, path.substringAfterLast('/'))
        }
        if (path.startsWith("/api/v1/thumbnail/") && path.length > "/api/v1/thumbnail/".length) {
            return serveThumbnail(session, path.substringAfterLast('/'))
        }
        if (path.startsWith("/api/v1/artwork/") && path.length > "/api/v1/artwork/".length) {
            return serveArtwork(session, path.substringAfterLast('/'))
        }
        return when (path) {
            "/api/v1/library" -> {
                if (session.method != Method.GET) methodNotAllowed("GET")
                else json(Response.Status.OK, backend.library())
            }
            "/api/v1/items" -> {
                if (session.method != Method.GET) return methodNotAllowed("GET")
                serveItems(session)
            }
            "/api/v1/rescan" -> {
                if (session.method != Method.POST) return methodNotAllowed("POST")
                json(Response.Status.ACCEPTED, backend.rescan())
            }
            "/api/v1/metadata" -> {
                if (session.method != Method.GET) methodNotAllowed("GET")
                else json(Response.Status.OK, metadataAccess.snapshot())
            }
            "/api/v1/metadata/search" -> {
                if (session.method != Method.GET) return methodNotAllowed("GET")
                val itemId = query(session, "itemId")?.takeIf(String::isNotBlank)
                    ?: return error(Response.Status.BAD_REQUEST, "invalid_request", "itemId is required")
                val search = query(session, "q")?.trim()?.takeIf { it.isNotEmpty() && it.length <= 200 }
                    ?: return error(Response.Status.BAD_REQUEST, "invalid_request", "A search query is required")
                json(Response.Status.OK, metadataAccess.search(itemId, search))
            }
            "/api/v1/metadata/match" -> {
                if (session.method != Method.POST) return methodNotAllowed("POST")
                json(Response.Status.OK, metadataAccess.match(decodeBody<MetadataMatchRequest>(session)))
            }
            "/api/v1/metadata/clear" -> {
                if (session.method != Method.POST) return methodNotAllowed("POST")
                json(Response.Status.OK, metadataAccess.clear())
            }
            "/api/v1/playback" -> {
                if (session.method != Method.POST) return methodNotAllowed("POST")
                val report = decodeBody<PlaybackReport>(session)
                if (backend.media(report.itemId) == null) {
                    error(Response.Status.NOT_FOUND, "item_not_found", "Media item not found")
                } else {
                    json(Response.Status.OK, backend.playback(report))
                }
            }
            else -> error(Response.Status.NOT_FOUND, "not_found", "Endpoint not found")
        }
    }

    private fun serveArtwork(session: IHTTPSession, id: String): Response {
        if (session.method != Method.GET) return methodNotAllowed("GET")
        val artwork = metadataAccess.artwork(id) ?: return error(
            Response.Status.NOT_FOUND,
            "artwork_not_found",
            "Artwork was not found",
        )
        val entityTag = "\"${artwork.entityTag}\""
        if (session.headers["if-none-match"] == entityTag) {
            return newFixedLengthResponse(Response.Status.NOT_MODIFIED, artwork.contentType, "").apply {
                addArtworkHeaders(entityTag)
            }
        }
        return newFixedLengthResponse(
            Response.Status.OK,
            artwork.contentType,
            ByteArrayInputStream(artwork.bytes),
            artwork.bytes.size.toLong(),
        ).apply {
            addHeader("Content-Length", artwork.bytes.size.toString())
            addArtworkHeaders(entityTag)
        }
    }

    private fun Response.addArtworkHeaders(entityTag: String) {
        addHeader("Cache-Control", "private, max-age=604800")
        addHeader("ETag", entityTag)
        addHeader("X-Content-Type-Options", "nosniff")
    }

    private fun serveThumbnail(session: IHTTPSession, id: String): Response {
        if (session.method != Method.GET) return methodNotAllowed("GET")
        val stored = backend.media(id)
            ?.takeIf { it.item.type == app.navelo.shared.ItemType.VIDEO }
            ?: return error(Response.Status.NOT_FOUND, "item_not_found", "Media item not found")
        val thumbnail = try {
            thumbnailAccess.load(stored)
        } catch (_: Exception) {
            return error(
                Response.Status.SERVICE_UNAVAILABLE,
                "thumbnail_unavailable",
                "A local thumbnail could not be generated",
            ).apply { addHeader("Retry-After", "5") }
        } ?: return error(
            Response.Status.NOT_FOUND,
            "thumbnail_not_available",
            "No local thumbnail is available for this video",
        )
        val entityTag = "\"${thumbnail.entityTag}\""
        if (session.headers["if-none-match"] == entityTag) {
            return newFixedLengthResponse(Response.Status.NOT_MODIFIED, "image/jpeg", "").apply {
                addThumbnailHeaders(entityTag)
            }
        }
        return newFixedLengthResponse(
            Response.Status.OK,
            "image/jpeg",
            ByteArrayInputStream(thumbnail.bytes),
            thumbnail.bytes.size.toLong(),
        ).apply {
            addHeader("Content-Length", thumbnail.bytes.size.toString())
            addThumbnailHeaders(entityTag)
        }
    }

    private fun Response.addThumbnailHeaders(entityTag: String) {
        addHeader("Cache-Control", "private, max-age=86400")
        addHeader("ETag", entityTag)
        addHeader("X-Content-Type-Options", "nosniff")
    }

    private fun serveItems(session: IHTTPSession): Response {
        val offsetText = query(session, "offset")
        val limitText = query(session, "limit")
        val revisionText = query(session, "revision")
        val offset = offsetText?.toIntOrNull() ?: if (offsetText == null) 0 else -1
        val limit = limitText?.toIntOrNull() ?: if (limitText == null) 500 else -1
        val revision = revisionText?.toLongOrNull()
        if (offset < 0 || limit !in 1..500) {
            return error(Response.Status.BAD_REQUEST, "invalid_page", "Invalid offset or limit")
        }
        if (revisionText != null && (revision == null || revision < 0)) {
            return error(Response.Status.BAD_REQUEST, "invalid_revision", "Invalid library revision")
        }
        return when (val result = backend.items(offset, limit, revision, query(session, "rootId"), query(session, "parentId"))) {
            is ServerRuntime.ItemsPageResult.Success -> json(Response.Status.OK, result.response)
            is ServerRuntime.ItemsPageResult.Stale -> error(
                CONFLICT,
                "stale_revision",
                "Library changed; refresh from the first page (revision ${result.currentRevision})",
            )
        }
    }

    private fun serveMedia(session: IHTTPSession, id: String): Response {
        if (session.method !in setOf(Method.GET, Method.HEAD, Method.OPTIONS)) {
            return methodNotAllowed("GET, HEAD, OPTIONS")
        }
        val stored = backend.media(id)
            ?: return error(Response.Status.NOT_FOUND, "item_not_found", "Media item not found")
        if (stored.item.type == app.navelo.shared.ItemType.DIRECTORY) {
            return error(Response.Status.NOT_FOUND, "item_not_found", "Media item not found")
        }
        if (session.method == Method.OPTIONS) {
            return newFixedLengthResponse(Response.Status.NO_CONTENT, MIME_PLAINTEXT, "").apply {
                addHeader("Allow", "GET, HEAD, OPTIONS")
                addHeader("Accept-Ranges", "bytes")
            }
        }
        val source = try { mediaAccess.open(stored) } catch (_: Exception) { return unavailable() }
        val size = source.size
        if (size < 0) { source.close(); return unavailable() }
        val rangeHeader = session.headers["range"]?.let { value ->
            if (value.startsWith("bytes=", ignoreCase = true)) "bytes=${value.substringAfter('=')}" else value
        }
        val range = try { ByteRanges.parse(rangeHeader, size) }
            catch (_: app.navelo.shared.InvalidRange) { source.close(); return rangeNotSatisfiable(size) }
        val start = range?.start ?: 0L
        val length = range?.length ?: size
        val status = if (range == null) Response.Status.OK else Response.Status.PARTIAL_CONTENT
        if (session.method == Method.HEAD) {
            try { source.verifySeek(start) } catch (_: Exception) { source.close(); return rangeNotSatisfiable(size) }
            source.close()
            return mediaResponse(status, stored.item.mimeType, ByteArrayInputStream(ByteArray(0)), length, size, range)
        }
        if (!streamSlots.tryAcquire()) { source.close(); return unavailable("All playback connections are busy") }
        var lease: java.io.Closeable? = null
        val input = try {
            lease = resources.acquire()
            val bounded = BoundedInputStream(source.openStream(start), length)
            object : FilterInputStream(bounded) {
                private val closed = java.util.concurrent.atomic.AtomicBoolean()
                override fun close() {
                    if (closed.compareAndSet(false, true)) try { super.close() } finally {
                        source.close(); lease?.close(); streamSlots.release()
                    }
                }
            }
        } catch (_: Exception) {
            source.close(); lease?.close(); streamSlots.release()
            return if (range != null) rangeNotSatisfiable(size) else unavailable()
        }
        return mediaResponse(status, stored.item.mimeType, input, length, size, range)
    }

    private fun mediaResponse(
        status: Response.IStatus,
        mimeType: String,
        input: InputStream,
        length: Long,
        totalSize: Long,
        range: app.navelo.shared.ByteRange?,
    ): Response = newFixedLengthResponse(status, safeMimeType(mimeType), input, length).apply {
        addHeader("Accept-Ranges", "bytes")
        addHeader("Content-Length", length.toString())
        if (range != null) addHeader("Content-Range", range.contentRange(totalSize))
        addHeader("Cache-Control", "private, no-transform")
        addHeader("X-Content-Type-Options", "nosniff")
    }

    private fun safeMimeType(value: String): String = value.takeIf(SAFE_MIME_TYPE::matches)
        ?: "application/octet-stream"

    private fun rangeNotSatisfiable(size: Long) = error(
        Response.Status.RANGE_NOT_SATISFIABLE,
        "invalid_range",
        "Requested byte range is not available",
    ).apply {
        addHeader("Content-Range", "bytes */$size")
        addHeader("Accept-Ranges", "bytes")
    }

    private fun unavailable(message: String = "The media file is temporarily unavailable") = error(
        Response.Status.SERVICE_UNAVAILABLE,
        "media_unavailable",
        message,
    )

    private fun busy() = error(
        Response.Status.SERVICE_UNAVAILABLE,
        "server_busy",
        "Navelo is handling other requests. Please retry shortly.",
    ).apply { addHeader("Retry-After", "2") }

    private fun authenticate(session: IHTTPSession): TrustedRecord? {
        val header = session.headers["authorization"] ?: return null
        val pieces = header.trim().split(Regex("\\s+"), limit = 2)
        if (pieces.size != 2 || !pieces[0].equals("Bearer", ignoreCase = true) || pieces[1].length > 512) return null
        return backend.authenticate(pieces[1])
    }

    private inline fun <reified T> decodeBody(session: IHTTPSession): T {
        if (session.headers["transfer-encoding"] != null) throw LengthRequiredException()
        val length = session.headers["content-length"]?.toLongOrNull() ?: throw LengthRequiredException()
        if (length <= 0) throw LengthRequiredException()
        if (length > MAX_JSON_BODY_BYTES) throw BodyTooLargeException()
        val body = ByteArray(length.toInt())
        var offset = 0
        while (offset < body.size) {
            val read = session.inputStream.read(body, offset, body.size - offset)
            require(read > 0) { "Incomplete body" }
            offset += read
        }
        return Protocol.json.decodeFromString(body.toString(Charsets.UTF_8))
    }

    private inline fun <reified T> json(status: Response.IStatus, value: T): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", Protocol.json.encodeToString(value))

    private fun error(status: Response.IStatus, code: String, message: String): Response =
        json(status, ApiError(message, code))

    private fun methodNotAllowed(allow: String) = error(
        Response.Status.METHOD_NOT_ALLOWED,
        "method_not_allowed",
        "Method not allowed",
    ).apply { addHeader("Allow", allow) }

    private fun query(session: IHTTPSession, name: String): String? = session.parameters[name]?.firstOrNull()

    private class BodyTooLargeException : RuntimeException()
    private class LengthRequiredException : RuntimeException()

    private companion object {
        const val MAX_JSON_BODY_BYTES = 16 * 1024L
        val SAFE_MIME_TYPE = Regex("^[A-Za-z0-9!#\$&^_.+-]+/[A-Za-z0-9!#\$&^_.+-]+$")
        val TOO_MANY_REQUESTS = FixedStatus(429, "Too Many Requests")
        val PAYLOAD_TOO_LARGE = FixedStatus(413, "Payload Too Large")
        val LENGTH_REQUIRED = FixedStatus(411, "Length Required")
        val CONFLICT = FixedStatus(409, "Conflict")
    }
}

internal class BoundedInputStream(
    input: InputStream,
    length: Long,
) : FilterInputStream(input) {
    private var remaining = length.coerceAtLeast(0)

    override fun read(): Int {
        if (remaining == 0L) return -1
        val value = super.read()
        if (value >= 0) remaining-- else remaining = 0
        return value
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return -1
        val requested = minOf(length.toLong(), remaining).toInt()
        val count = super.read(buffer, offset, requested)
        if (count > 0) remaining -= count else if (count < 0) remaining = 0
        return count
    }

    override fun skip(count: Long): Long {
        val skipped = super.skip(minOf(count, remaining))
        remaining -= skipped
        return skipped
    }

    override fun available(): Int = minOf(super.available().toLong(), remaining, Int.MAX_VALUE.toLong()).toInt()
}

private data class FixedStatus(
    private val code: Int,
    private val reason: String,
) : NanoHTTPD.Response.IStatus {
    override fun getRequestStatus(): Int = code
    override fun getDescription(): String = "$code $reason"
}
