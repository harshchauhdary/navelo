package app.navelo.server

import app.navelo.shared.FilenameParser
import app.navelo.shared.MediaItem
import app.navelo.shared.MediaMetadata
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.Collections
import java.util.LinkedHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

internal class TmdbMetadataProvider(
    token: String,
    private val client: OkHttpClient = defaultClient(),
) : MetadataProvider {
    private val readToken = token.trim()
    private val slots = Semaphore(2)
    private val jsonCache = boundedCache<String, JsonElement>(128)
    override val configured: Boolean = readToken.isNotEmpty()

    override fun search(item: MediaItem, query: String): List<MediaMetadata> {
        ensureConfigured()
        val parsed = FilenameParser.parse(item.filename, item.relativePath)
        val episode = item.season != null || item.episode != null || parsed.season != null || parsed.episode != null
        val endpoint = if (episode) "search/tv" else "search/multi"
        val url = "$API/$endpoint".toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("include_adult", "false")
            .build()
        return json(url.toString(), "search:$endpoint:${MetadataMatcher.normalize(query)}").jsonObject
            .array("results").mapNotNull { element ->
            val value = element.jsonObject
            val type = (if (episode) "tv" else value.string("media_type")) ?: return@mapNotNull null
            if (type !in MEDIA_TYPES) return@mapNotNull null
            val id = value.long("id") ?: return@mapNotNull null
            val title = value.string(if (type == "tv") "name" else "title").orEmpty()
            if (title.isBlank()) return@mapNotNull null
            val date = value.string(if (type == "tv") "first_air_date" else "release_date").orEmpty()
            MediaMetadata(
                id = id,
                title = title,
                overview = value.string("overview").orEmpty(),
                posterUrl = source(value.string("poster_path"), "w500"),
                backdropUrl = source(value.string("backdrop_path"), "w1280"),
                year = date.take(4),
                rating = value.double("vote_average") ?: 0.0,
                mediaType = type,
            )
        }
    }

    override fun details(item: MediaItem, tmdbId: Long, mediaType: String): MediaMetadata {
        ensureConfigured()
        require(tmdbId > 0 && mediaType in MEDIA_TYPES)
        val details = json(
            "$API/$mediaType/$tmdbId?append_to_response=credits",
            "details:$mediaType:$tmdbId",
        ).jsonObject
        val title = details.string(if (mediaType == "tv") "name" else "title")
            ?.takeIf(String::isNotBlank) ?: throw MetadataNotFoundException()
        val date = details.string(if (mediaType == "tv") "first_air_date" else "release_date").orEmpty()
        val cast = runCatching { details.getValue("credits").jsonObject.array("cast") }
            .getOrDefault(JsonArray(emptyList()))
            .mapNotNull { it.jsonObject.string("name") }
            .distinct()
            .take(8)
        var result = MediaMetadata(
            id = tmdbId,
            title = title,
            overview = details.string("overview").orEmpty(),
            posterUrl = source(details.string("poster_path"), "w500"),
            backdropUrl = source(details.string("backdrop_path"), "w1280"),
            year = date.take(4),
            runtimeMinutes = if (mediaType == "tv") {
                details.array("episode_run_time").firstOrNull()?.jsonPrimitive?.intOrNull ?: 0
            } else {
                details.int("runtime") ?: 0
            },
            genres = details.array("genres").mapNotNull { it.jsonObject.string("name") },
            cast = cast,
            rating = details.double("vote_average") ?: 0.0,
            mediaType = mediaType,
        )
        val parsed = FilenameParser.parse(item.filename, item.relativePath)
        val season = item.season ?: parsed.season
        val episode = item.episode ?: parsed.episode
        if (mediaType == "tv" && season != null && episode != null) {
            val seasonDetails = json(
                "$API/tv/$tmdbId/season/$season",
                "season:$tmdbId:$season",
            ).jsonObject
            val episodeDetails = seasonDetails.array("episodes").asSequence()
                .map(JsonElement::jsonObject)
                .firstOrNull { it.int("episode_number") == episode }
            result = result.copy(
                seasonPosterUrl = source(seasonDetails.string("poster_path"), "w500"),
                episodeTitle = episodeDetails?.string("name"),
                episodeOverview = episodeDetails?.string("overview"),
                episodeImageUrl = source(episodeDetails?.string("still_path"), "w780"),
                episodeAirDate = episodeDetails?.string("air_date"),
                runtimeMinutes = episodeDetails?.int("runtime") ?: result.runtimeMinutes,
            )
        }
        return result
    }

    override fun artwork(source: String): Artwork {
        ensureConfigured()
        val separator = source.indexOf('|')
        require(separator in 1 until source.lastIndex)
        val size = source.substring(0, separator)
        val path = source.substring(separator + 1)
        require(size in IMAGE_SIZES && path.matches(IMAGE_PATH))
        val request = Request.Builder()
            .url("$IMAGE/$size$path")
            .header("Accept", "image/jpeg,image/png,image/webp")
            .get()
            .build()
        return withSlot {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw statusFailure(response.code)
                val contentType = response.header("Content-Type")?.substringBefore(';')?.trim().orEmpty()
                if (contentType !in SUPPORTED_IMAGE_TYPES) throw IOException("Invalid artwork response")
                val body = response.body ?: throw IOException("Empty artwork response")
                val advertised = body.contentLength()
                if (advertised > MAX_ARTWORK_BYTES) throw IOException("Artwork is too large")
                val output = ByteArrayOutputStream(minOf(advertised.coerceAtLeast(0), 256 * 1024L).toInt())
                val buffer = ByteArray(32 * 1024)
                body.byteStream().use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > MAX_ARTWORK_BYTES) throw IOException("Artwork is too large")
                        output.write(buffer, 0, count)
                    }
                }
                Artwork(output.toByteArray(), contentType, "")
            }
        }
    }

    private fun json(url: String, cacheKey: String): JsonElement {
        jsonCache[cacheKey]?.let { return it }
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $readToken")
            .header("Accept", "application/json")
            .get()
            .build()
        return withSlot {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw statusFailure(response.code)
                val body = response.body ?: throw IOException("Empty metadata response")
                val advertised = body.contentLength()
                if (advertised > MAX_JSON_BYTES) throw IOException("Metadata response is too large")
                val output = ByteArrayOutputStream(minOf(advertised.coerceAtLeast(0), 128 * 1024L).toInt())
                val buffer = ByteArray(16 * 1024)
                body.byteStream().use { input ->
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (output.size() + count > MAX_JSON_BYTES) throw IOException("Metadata response is too large")
                        output.write(buffer, 0, count)
                    }
                }
                ProtocolJson.decode(output.toString(Charsets.UTF_8.name())).also { jsonCache[cacheKey] = it }
            }
        }
    }

    private fun <T> withSlot(block: () -> T): T {
        if (!slots.tryAcquire()) throw MetadataBusyException()
        return try { block() } finally { slots.release() }
    }

    private fun statusFailure(code: Int): Exception = when (code) {
        404 -> MetadataNotFoundException()
        429 -> MetadataBusyException()
        else -> MetadataUnavailableException()
    }

    private fun ensureConfigured() {
        if (!configured) throw MetadataUnavailableException()
    }

    private fun source(path: String?, size: String): String? =
        path?.takeIf { it.matches(IMAGE_PATH) }?.let { "$size|$it" }

    private companion object {
        const val API = "https://api.themoviedb.org/3"
        const val IMAGE = "https://image.tmdb.org/t/p"
        const val MAX_JSON_BYTES = 2 * 1024 * 1024
        const val MAX_ARTWORK_BYTES = 8 * 1024 * 1024
        val MEDIA_TYPES = setOf("movie", "tv")
        val IMAGE_SIZES = setOf("w500", "w780", "w1280")
        val SUPPORTED_IMAGE_TYPES = setOf("image/jpeg", "image/png", "image/webp")
        val IMAGE_PATH = Regex("^/[A-Za-z0-9_./-]{1,240}$")

        fun defaultClient() = OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(6, TimeUnit.SECONDS)
            .callTimeout(12, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .build()
    }
}

private object ProtocolJson {
    fun decode(value: String): JsonElement = app.navelo.shared.Protocol.json.parseToJsonElement(value)
}

private fun JsonObject.string(key: String): String? = get(key)?.jsonPrimitive?.contentOrNull
private fun JsonObject.int(key: String): Int? = get(key)?.jsonPrimitive?.intOrNull
private fun JsonObject.long(key: String): Long? = get(key)?.jsonPrimitive?.contentOrNull?.toLongOrNull()
private fun JsonObject.double(key: String): Double? = get(key)?.jsonPrimitive?.doubleOrNull
private fun JsonObject.array(key: String): JsonArray = get(key)?.let {
    runCatching { it.jsonArray }.getOrNull()
} ?: JsonArray(emptyList())

private fun <K, V> boundedCache(capacity: Int): MutableMap<K, V> = Collections.synchronizedMap(
    object : LinkedHashMap<K, V>(capacity, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean = size > capacity
    },
)
