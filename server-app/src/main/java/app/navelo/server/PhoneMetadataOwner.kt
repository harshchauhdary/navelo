package app.navelo.server

import app.navelo.shared.FilenameParser
import app.navelo.shared.ItemType
import app.navelo.shared.MediaItem
import app.navelo.shared.MediaMetadata
import app.navelo.shared.MetadataMatchRequest
import app.navelo.shared.MetadataSearchResponse
import app.navelo.shared.MetadataSnapshot
import app.navelo.shared.Protocol
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

internal class PhoneMetadataOwner(
    metadataFile: File,
    private val artworkDir: File,
    private val provider: MetadataProvider,
    private val scope: CoroutineScope,
    private val retryDelayMs: Long = 30_000L,
    private val persistence: MetadataPersistence = FileMetadataPersistence(metadataFile),
    private val autoMatchEnabled: Boolean = true,
) : MetadataAccess {
    private val lock = Any()
    private val workerRunning = AtomicBoolean(false)
    private var revision = 0L
    private var metadata = emptyMap<String, MediaMetadata>()
    private var selections = emptyMap<String, StoredSelection>()
    private var artworkSources = emptyMap<String, String>()
    private var pending = emptySet<String>()
    private var currentItems = emptyMap<String, MediaItem>()
    private val negativeUntil = HashMap<NegativeKey, Long>()
    private val manualVersions = HashMap<String, Long>()
    private val nextManualVersion = AtomicLong()
    private var clearEpoch = 0L
    private val artworkLocks = HashMap<String, ReentrantLock>()

    fun restore() {
        val saved = runCatching {
            persistence.read()?.let { Protocol.json.decodeFromString<PersistedMetadata>(it.toString(Charsets.UTF_8)) }
        }.getOrNull() ?: PersistedMetadata()
        synchronized(lock) {
            revision = saved.revision
            metadata = saved.items
            selections = saved.selections
            artworkSources = saved.artworkSources.filterKeys(OPAQUE_ID::matches)
            pending = saved.pending
        }
        artworkDir.mkdirs()
        trimArtworkCache()
    }

    fun onLibraryChanged(items: List<MediaItem>) {
        val videos = items.asSequence().filter { it.type == ItemType.VIDEO }.associateBy { it.id }
        synchronized(lock) {
            currentItems = videos
            negativeUntil.keys.removeAll { it.itemId !in videos }
            val next = metadata.filterKeys(videos::containsKey)
            val nextPending = pending.filterTo(LinkedHashSet(), videos::containsKey)
            val validGroups = videos.values.mapTo(HashSet(), ::groupKey)
            val nextSelections = selections.filterKeys(validGroups::contains)
            if (next != metadata || nextPending != pending || nextSelections != selections) {
                metadata = next
                pending = nextPending
                selections = nextSelections
                pruneArtworkSourcesLocked()
                revision++
                persistLocked()
            }
        }
        scheduleMissing()
    }

    override fun snapshot(): MetadataSnapshot {
        scheduleMissing()
        return synchronized(lock) { snapshotLocked() }
    }

    override fun search(itemId: String, query: String): MetadataSearchResponse {
        val item = synchronized(lock) { currentItems[itemId] }
            ?: throw MetadataNotFoundException("item_not_found", "Media item not found.")
        val normalized = query.trim()
        require(normalized.isNotEmpty() && normalized.length <= 200)
        if (!provider.configured) throw MetadataUnavailableException()
        val results = try {
            provider.search(item, normalized).map(::localize)
        } catch (failure: MetadataException) {
            throw failure
        } catch (_: Exception) {
            throw MetadataUnavailableException()
        }
        return MetadataSearchResponse(results)
    }

    override fun match(request: MetadataMatchRequest): MetadataSnapshot {
        require(request.tmdbId > 0 && request.mediaType in MEDIA_TYPES)
        val item = synchronized(lock) { currentItems[request.itemId] }
            ?: throw MetadataNotFoundException("item_not_found", "Media item not found.")
        if (!provider.configured) throw MetadataUnavailableException()
        val key = groupKey(item)
        val requestVersion: Long
        val requestClearEpoch: Long
        synchronized(lock) {
            requestVersion = nextManualVersion.incrementAndGet()
            manualVersions[key] = requestVersion
            requestClearEpoch = clearEpoch
        }
        val rawSelected = try {
            provider.details(item, request.tmdbId, request.mediaType)
        } catch (failure: MetadataException) {
            throw failure
        } catch (_: Exception) {
            throw MetadataUnavailableException()
        }
        synchronized(lock) {
            if (clearEpoch == requestClearEpoch && manualVersions[key] == requestVersion && currentItems[item.id] == item) {
                val selected = localize(rawSelected)
                applySelectionLocked(item, StoredSelection(request.tmdbId, request.mediaType), selected)
                pending = pending - item.id
                persistLocked()
            }
        }
        scheduleMissing()
        return synchronized(lock) { snapshotLocked() }
    }

    override fun clear(): MetadataSnapshot {
        synchronized(lock) {
            metadata = emptyMap()
            selections = emptyMap()
            artworkSources = emptyMap()
            pending = emptySet()
            negativeUntil.clear()
            manualVersions.clear()
            clearEpoch++
            revision++
            persistLocked()
        }
        artworkDir.listFiles()?.forEach { runCatching { it.delete() } }
        scheduleMissing()
        return synchronized(lock) { snapshotLocked() }
    }

    override fun artwork(id: String): Artwork? {
        if (!OPAQUE_ID.matches(id)) return null
        val source = synchronized(lock) { artworkSources[id] } ?: return null
        val requestEpoch = synchronized(lock) { clearEpoch }
        val artworkLock = synchronized(lock) { artworkLocks.getOrPut(id) { ReentrantLock() } }
        if (!artworkLock.tryLock()) throw MetadataBusyException()
        return try {
            val cached = File(artworkDir, id)
            readCached(cached)?.let { return it }
            if (!provider.configured) throw MetadataUnavailableException()
            val fetched = try {
                provider.artwork(source)
            } catch (failure: MetadataException) {
                throw failure
            } catch (_: Exception) {
                throw MetadataUnavailableException()
            }
            val detectedType = detectedContentType(fetched.bytes)
            if (fetched.bytes.isEmpty() || fetched.bytes.size > MAX_ARTWORK_BYTES || detectedType == null) {
                throw MetadataUnavailableException()
            }
            synchronized(lock) {
                if (clearEpoch != requestEpoch || artworkSources[id] != source) {
                    throw MetadataNotFoundException("artwork_not_found", "Artwork was cleared.")
                }
                artworkDir.mkdirs()
                val temporary = File.createTempFile("$id-", ".tmp", artworkDir)
                runCatching {
                    temporary.outputStream().use { it.write(fetched.bytes) }
                    if (cached.exists()) cached.delete()
                    check(temporary.renameTo(cached))
                }.onFailure {
                    temporary.delete()
                    throw MetadataUnavailableException()
                }
                trimArtworkCache()
            }
            Artwork(fetched.bytes, detectedType, id)
        } finally {
            artworkLock.unlock()
        }
    }

    private fun scheduleMissing() {
        if (!autoMatchEnabled || !provider.configured || !workerRunning.compareAndSet(false, true)) return
        scope.launch {
            try {
                repeat(MAX_BACKGROUND_ATTEMPTS) { attempt ->
                    val networkFailed = runBackgroundPass()
                    if (!networkFailed) return@launch
                    if (attempt == MAX_BACKGROUND_ATTEMPTS - 1) return@launch
                    delay(retryDelayMs)
                }
            } finally {
                workerRunning.set(false)
            }
        }
    }

    private fun runBackgroundPass(): Boolean {
        val items = synchronized(lock) { currentItems.values.toList() }
        for (item in items) {
            val work = synchronized(lock) {
                val key = groupKey(item)
                val chosen = selections[key]
                val needsHydration = item.id in pending
                val missing = item.id !in metadata
                if (!needsHydration && !missing) return@synchronized null
                BackgroundWork(key, chosen, clearEpoch, manualVersions[key] ?: 0L, item.id)
            } ?: continue
            var chosen = work.selection
            if (chosen == null) {
                val parsed = FilenameParser.parse(item.filename, item.relativePath)
                val query = item.showHint ?: parsed.show ?: item.titleHint ?: parsed.title
                if (query.isBlank()) continue
                val negativeKey = NegativeKey(item.id, MetadataMatcher.normalize(query))
                val now = System.currentTimeMillis()
                if (synchronized(lock) { (negativeUntil[negativeKey] ?: 0L) > now }) continue
                val candidates = try {
                    provider.search(item, query)
                } catch (failure: Throwable) {
                    if (failure is CancellationException) throw failure
                    return true
                }
                val match = MetadataMatcher.confidentMatch(query, item.year ?: parsed.year, candidates)
                if (match == null) {
                    synchronized(lock) { negativeUntil[negativeKey] = now + NEGATIVE_TTL_MS }
                    continue
                }
                val selection = StoredSelection(match.id, match.mediaType.ifBlank { mediaType(item) })
                var applied = false
                synchronized(lock) {
                    if (work.isCurrentLocked()) {
                        applySelectionLocked(item, selection, localize(match), hydrationPending = true)
                        persistLocked()
                        applied = true
                    }
                }
                if (!applied) continue
                chosen = selection
            }
            val selected = chosen ?: continue
            val rawHydrated = try {
                provider.details(item, selected.id, selected.mediaType)
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                synchronized(lock) {
                    if (currentItems.containsKey(item.id) && selections[work.groupKey] == selected &&
                        clearEpoch == work.clearEpoch && (manualVersions[work.groupKey] ?: 0L) == work.manualVersion
                    ) {
                        pending = pending + item.id
                        persistLocked()
                    }
                }
                return true
            }
            synchronized(lock) {
                if (currentItems.containsKey(item.id) && selections[work.groupKey] == selected &&
                    clearEpoch == work.clearEpoch && (manualVersions[work.groupKey] ?: 0L) == work.manualVersion
                ) {
                    val hydrated = localize(rawHydrated)
                    metadata = metadata + (item.id to hydrated)
                    pending = pending - item.id
                    revision++
                    persistLocked()
                }
            }
        }
        return false
    }

    private fun applySelectionLocked(
        item: MediaItem,
        selection: StoredSelection,
        selected: MediaMetadata,
        hydrationPending: Boolean = true,
    ) {
        val key = groupKey(item)
        val targets = currentItems.values.filter { groupKey(it) == key }
        selections = selections + (key to selection)
        val showLevel = selected.copy(
            seasonPosterUrl = null,
            episodeTitle = null,
            episodeOverview = null,
            episodeImageUrl = null,
            episodeAirDate = null,
            runtimeMinutes = 0,
        )
        metadata = metadata + targets.associate { target -> target.id to if (target.id == item.id) selected else showLevel }
        if (hydrationPending) pending = pending + targets.map(MediaItem::id)
        revision++
    }

    private fun localize(value: MediaMetadata): MediaMetadata = value.copy(
        posterUrl = registerArtwork(value.posterUrl),
        backdropUrl = registerArtwork(value.backdropUrl),
        seasonPosterUrl = registerArtwork(value.seasonPosterUrl),
        episodeImageUrl = registerArtwork(value.episodeImageUrl),
    )

    private fun registerArtwork(source: String?): String? {
        val normalized = source?.takeIf { it.matches(ARTWORK_SOURCE) } ?: return null
        val id = sha256(normalized).take(40)
        synchronized(lock) {
            if (artworkSources[id] != normalized) {
                artworkSources = artworkSources + (id to normalized)
                pruneArtworkSourcesLocked()
                persistLocked()
            }
        }
        return "/api/v1/artwork/$id"
    }

    private fun readCached(file: File): Artwork? {
        if (!file.isFile || file.length() !in 1..MAX_ARTWORK_BYTES.toLong()) return null
        file.setLastModified(System.currentTimeMillis())
        val bytes = runCatching { file.readBytes() }.getOrNull() ?: return null
        val contentType = detectedContentType(bytes) ?: return null
        return Artwork(bytes, contentType, file.name)
    }

    private fun detectedContentType(bytes: ByteArray): String? = when {
            bytes.size >= 3 && bytes[0] == 0xff.toByte() && bytes[1] == 0xd8.toByte() -> "image/jpeg"
            bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_MAGIC) -> "image/png"
            bytes.size >= 12 && bytes.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "RIFF" &&
                bytes.copyOfRange(8, 12).toString(Charsets.US_ASCII) == "WEBP" -> "image/webp"
            else -> null
        }

    private fun trimArtworkCache() = synchronized(lock) {
        val files = artworkDir.listFiles()?.filter { it.isFile && OPAQUE_ID.matches(it.name) }
            ?.sortedByDescending(File::lastModified).orEmpty()
        var total = 0L
        files.forEach { cached ->
            total += cached.length()
            if (cached.length() > MAX_ARTWORK_BYTES || total > MAX_ARTWORK_CACHE_BYTES) cached.delete()
        }
    }

    private fun pruneArtworkSourcesLocked() {
        val referenced = metadata.values.flatMap { value ->
            listOf(value.posterUrl, value.backdropUrl, value.seasonPosterUrl, value.episodeImageUrl)
        }.mapNotNull { it?.substringAfterLast('/')?.takeIf(OPAQUE_ID::matches) }.toSet()
        val unreferenced = artworkSources.keys.filterNot(referenced::contains)
        if (unreferenced.size <= MAX_RECENT_ARTWORK_SOURCES) return
        val keepRecent = unreferenced.takeLast(MAX_RECENT_ARTWORK_SOURCES).toSet()
        artworkSources = artworkSources.filterKeys { it in referenced || it in keepRecent }
        artworkLocks.keys.retainAll(artworkSources.keys)
    }

    private fun BackgroundWork.isCurrentLocked(): Boolean =
        currentItems.containsKey(itemId) && selections[groupKey] == selection && clearEpoch == this.clearEpoch &&
            (manualVersions[groupKey] ?: 0L) == manualVersion

    private fun persistLocked() {
        val encoded = Protocol.json.encodeToString(
            PersistedMetadata(revision, metadata, selections, artworkSources, pending),
        ).toByteArray(Charsets.UTF_8)
        persistence.write(encoded)
    }

    private fun snapshotLocked() = MetadataSnapshot(revision, provider.configured, metadata)

    private fun groupKey(item: MediaItem): String {
        val parsed = FilenameParser.parse(item.filename, item.relativePath)
        val show = item.showHint ?: parsed.show
        return if (item.season != null || item.episode != null || parsed.season != null || parsed.episode != null) {
            "show:${MetadataMatcher.normalize(show ?: item.titleHint ?: parsed.title)}"
        } else {
            "item:${item.id}"
        }
    }

    private fun mediaType(item: MediaItem): String {
        val parsed = FilenameParser.parse(item.filename, item.relativePath)
        return if (item.season != null || item.episode != null || parsed.season != null || parsed.episode != null) "tv" else "movie"
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private companion object {
        val MEDIA_TYPES = setOf("movie", "tv")
        val OPAQUE_ID = Regex("^[a-f0-9]{40}$")
        val ARTWORK_SOURCE = Regex("^(w500|w780|w1280)\\|/[A-Za-z0-9_./-]{1,240}$")
        val PNG_MAGIC = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        const val MAX_ARTWORK_BYTES = 8 * 1024 * 1024
        const val MAX_ARTWORK_CACHE_BYTES = 128L * 1024 * 1024
        const val NEGATIVE_TTL_MS = 6 * 60 * 60_000L
        const val MAX_RECENT_ARTWORK_SOURCES = 256
        const val MAX_BACKGROUND_ATTEMPTS = 3
    }

    private data class NegativeKey(val itemId: String, val query: String)

    private data class BackgroundWork(
        val groupKey: String,
        val selection: StoredSelection?,
        val clearEpoch: Long,
        val manualVersion: Long,
        val itemId: String = "",
    )
}

@Serializable
internal data class PersistedMetadata(
    val revision: Long = 0,
    val items: Map<String, MediaMetadata> = emptyMap(),
    val selections: Map<String, StoredSelection> = emptyMap(),
    val artworkSources: Map<String, String> = emptyMap(),
    val pending: Set<String> = emptySet(),
)

@Serializable
internal data class StoredSelection(
    val id: Long,
    val mediaType: String,
)

internal interface MetadataPersistence {
    fun read(): ByteArray?
    fun write(bytes: ByteArray)
}

internal class FileMetadataPersistence(
    private val file: File,
) : MetadataPersistence {
    override fun read(): ByteArray? = file.takeIf(File::isFile)?.readBytes()

    override fun write(bytes: ByteArray) {
        file.parentFile?.mkdirs()
        val temporary = File.createTempFile("${file.name}-", ".new", file.parentFile)
        try {
            temporary.outputStream().use { output ->
                output.write(bytes)
                output.flush()
                (output as? java.io.FileOutputStream)?.fd?.sync()
            }
            runCatching {
                Files.move(
                    temporary.toPath(),
                    file.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.getOrElse {
                Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }
}

internal object MetadataMatcher {
    fun confidentMatch(title: String, year: Int?, candidates: List<MediaMetadata>): MediaMetadata? {
        val ranked = candidates.map { it to score(title, year, it) }.sortedByDescending { it.second }
        val best = ranked.firstOrNull() ?: return null
        val runnerUp = ranked.getOrNull(1)?.second ?: 0.0
        return best.first.takeIf { best.second >= 0.86 && best.second - runnerUp >= 0.08 }
    }

    fun score(title: String, year: Int?, candidate: MediaMetadata): Double {
        val left = normalize(title)
        val right = normalize(candidate.title)
        if (left.isBlank() || right.isBlank()) return 0.0
        val titleScore = when {
            left == right -> 1.0
            left.contains(right) || right.contains(left) -> 0.9
            else -> diceCoefficient(left, right)
        }
        val candidateYear = candidate.year.take(4).toIntOrNull()
        val yearFactor = when {
            year == null -> 1.0
            candidateYear == null -> 0.90
            year == candidateYear -> 1.0
            kotlin.math.abs(year - candidateYear) == 1 -> 0.90
            else -> 0.72
        }
        return titleScore * yearFactor
    }

    fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value.lowercase(Locale.ROOT).replace("&", " and "), Normalizer.Form.NFD)
        val folded = StringBuilder(decomposed.length)
        var previousBaseWasLatin = false
        var index = 0
        while (index < decomposed.length) {
            val codePoint = decomposed.codePointAt(index)
            val type = Character.getType(codePoint)
            val isMark = type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()
            if (!isMark || !previousBaseWasLatin) folded.appendCodePoint(codePoint)
            if (!isMark) previousBaseWasLatin = Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN
            index += Character.charCount(codePoint)
        }
        return Normalizer.normalize(folded, Normalizer.Form.NFC)
            .replace(Regex("[^\\p{L}\\p{N}\\p{M}]+"), " ").trim()
    }

    private fun diceCoefficient(left: String, right: String): Double {
        fun bigrams(value: String) = value.replace(" ", "").windowed(2).toMutableList()
        val first = bigrams(left)
        val second = bigrams(right)
        if (first.isEmpty() || second.isEmpty()) return if (left == right) 1.0 else 0.0
        val originalSecondSize = second.size
        var matches = 0
        first.forEach { pair ->
            val index = second.indexOf(pair)
            if (index >= 0) { matches++; second.removeAt(index) }
        }
        return 2.0 * matches / (first.size + originalSecondSize)
    }
}
