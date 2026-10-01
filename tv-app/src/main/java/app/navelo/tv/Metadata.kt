package app.navelo.tv

import app.navelo.shared.MediaMetadata
import kotlinx.serialization.Serializable

/** Kept as a local name so existing TV UI and persisted JSON migrate without a schema fork. */
typealias Metadata = MediaMetadata

@Serializable
data class WatchProgress(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val lastPlayed: Long = 0,
    val watched: Boolean = false,
)

@Serializable
data class TvSettings(
    val autoNext: Boolean = true,
    val subtitles: Boolean = true,
    val tmdbConfigured: Boolean = false,
)
