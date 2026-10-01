package app.navelo.shared

import kotlinx.serialization.Serializable

@Serializable
data class MediaMetadata(
    val id: Long,
    val title: String,
    val overview: String = "",
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
    val year: String = "",
    val runtimeMinutes: Int = 0,
    val genres: List<String> = emptyList(),
    val cast: List<String> = emptyList(),
    val rating: Double = 0.0,
    val seasonPosterUrl: String? = null,
    val episodeTitle: String? = null,
    val episodeOverview: String? = null,
    val episodeImageUrl: String? = null,
    val episodeAirDate: String? = null,
    val mediaType: String = "",
)

@Serializable
data class MetadataSnapshot(
    val revision: Long = 0,
    val configured: Boolean = false,
    val items: Map<String, MediaMetadata> = emptyMap(),
)

@Serializable
data class MetadataSearchResponse(
    val results: List<MediaMetadata> = emptyList(),
)

@Serializable
data class MetadataMatchRequest(
    val itemId: String,
    val tmdbId: Long,
    val mediaType: String,
)
