package app.navelo.shared

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object Protocol {
    const val VERSION = 1
    const val SERVICE_TYPE = "_navelo._tcp."
    const val PORT = 8765
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
}
@Serializable enum class RootType { MOVIES, TV_SHOWS, OTHER }
@Serializable enum class ItemType { DIRECTORY, VIDEO, SUBTITLE }
@Serializable data class MediaRoot(val id: String, val name: String, val type: RootType, val available: Boolean = true, val itemCount: Int = 0)
@Serializable data class MediaItem(
    val id: String, val filename: String, val displayName: String, val parentId: String? = null,
    val rootId: String, val relativePath: String, val size: Long = 0, val mimeType: String = "video/mp4",
    val extension: String = "", val modifiedTime: Long = 0, val type: ItemType = ItemType.VIDEO,
    val titleHint: String? = null, val year: Int? = null, val showHint: String? = null,
    val season: Int? = null, val episode: Int? = null
)
@Serializable data class ServerInfo(val serverId: String, val displayName: String, val apiVersion: Int = Protocol.VERSION,
    val capabilities: List<String> = listOf("range", "pairing", "subtitles", "thumbnails", "phone-metadata-v1", "rescan-status-v1"),
    val libraryRevision: Long = 0, val scanGeneration: Long = 0,
    val completedScanGeneration: Long = 0, val scanError: String? = null)
@Serializable data class LibraryResponse(val revision: Long, val roots: List<MediaRoot>, val scanGeneration: Long? = null)
@Serializable data class ItemsResponse(val revision: Long, val items: List<MediaItem>, val nextOffset: Int? = null, val unchanged: Boolean = false)
@Serializable data class PairRequest(val clientId: String, val displayName: String, val clientSecret: String)
@Serializable data class PairTicket(val requestId: String, val status: String = "pending", val pin: String = "", val token: String? = null, val serverId: String? = null)
@Serializable data class ApiError(val message: String, val code: String)
@Serializable data class TrustedDevice(val clientId: String, val displayName: String, val pairedAt: Long)
@Serializable data class PendingPair(val requestId: String, val clientId: String, val displayName: String, val pin: String, val expiresAt: Long)
@Serializable data class PlaybackReport(val itemId: String, val positionMs: Long = 0, val durationMs: Long = 0, val playing: Boolean = false)
