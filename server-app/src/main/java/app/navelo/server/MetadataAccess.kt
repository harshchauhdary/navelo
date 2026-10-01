package app.navelo.server

import app.navelo.shared.MediaMetadata
import app.navelo.shared.MetadataMatchRequest
import app.navelo.shared.MetadataSearchResponse
import app.navelo.shared.MetadataSnapshot

internal data class Artwork(
    val bytes: ByteArray,
    val contentType: String,
    val entityTag: String,
)

internal open class MetadataException(
    val code: String,
    message: String,
) : Exception(message)

internal class MetadataBusyException : MetadataException(
    "metadata_busy",
    "Metadata provider is busy. Please retry shortly.",
)

internal class MetadataUnavailableException : MetadataException(
    "metadata_unavailable",
    "Metadata is temporarily unavailable.",
)

internal class MetadataNotFoundException(
    code: String = "metadata_not_found",
    message: String = "Metadata was not found.",
) : MetadataException(code, message)

internal interface MetadataAccess {
    fun snapshot(): MetadataSnapshot
    fun search(itemId: String, query: String): MetadataSearchResponse
    fun match(request: MetadataMatchRequest): MetadataSnapshot
    fun clear(): MetadataSnapshot
    fun artwork(id: String): Artwork?

    companion object {
        val NONE = object : MetadataAccess {
            override fun snapshot() = MetadataSnapshot()
            override fun search(itemId: String, query: String): MetadataSearchResponse =
                throw MetadataUnavailableException()
            override fun match(request: MetadataMatchRequest): MetadataSnapshot =
                throw MetadataUnavailableException()
            override fun clear() = MetadataSnapshot()
            override fun artwork(id: String): Artwork? = null
        }
    }
}

internal interface MetadataProvider {
    val configured: Boolean
    fun search(item: app.navelo.shared.MediaItem, query: String): List<MediaMetadata>
    fun details(item: app.navelo.shared.MediaItem, tmdbId: Long, mediaType: String): MediaMetadata
    fun artwork(source: String): Artwork
}
