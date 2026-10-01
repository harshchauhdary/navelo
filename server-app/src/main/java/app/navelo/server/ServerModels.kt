package app.navelo.server

import app.navelo.shared.MediaItem
import app.navelo.shared.MediaRoot
import app.navelo.shared.PendingPair
import app.navelo.shared.RootType
import app.navelo.shared.TrustedDevice
import kotlinx.serialization.Serializable

data class ServerState(
    val running: Boolean = false,
    val scanning: Boolean = false,
    val roots: List<MediaRoot> = emptyList(),
    val pending: List<PendingPair> = emptyList(),
    val devices: List<TrustedDevice> = emptyList(),
    val message: String? = null,
    val streamingTitle: String? = null,
    val port: Int = 8765,
    val serverId: String = "",
)

@Serializable
internal data class StoredRoot(
    val id: String,
    val name: String,
    val type: RootType,
    val uri: String,
    val available: Boolean = true,
    val itemCount: Int = 0,
) {
    fun publicValue() = MediaRoot(id, name, type, available, itemCount)
}

@Serializable
internal data class StoredMediaItem(
    val item: MediaItem,
    val uri: String,
)

@Serializable
internal data class PersistedLibrary(
    val revision: Long = 0,
    val roots: List<StoredRoot> = emptyList(),
    val items: List<StoredMediaItem> = emptyList(),
)

@Serializable
internal data class TrustedRecord(
    val clientId: String,
    val displayName: String,
    val pairedAt: Long,
    val tokenHash: String,
) {
    fun publicValue() = TrustedDevice(clientId, displayName, pairedAt)
}

internal data class LibrarySnapshot(
    val revision: Long,
    val roots: List<StoredRoot>,
    val items: List<StoredMediaItem>,
)
