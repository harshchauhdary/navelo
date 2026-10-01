package app.navelo.server

import app.navelo.shared.ItemType
import app.navelo.shared.MediaItem
import app.navelo.shared.MediaMetadata
import app.navelo.shared.MetadataMatchRequest
import app.navelo.shared.Protocol
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors
import java.util.concurrent.ExecutionException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMetadataOwnerTest {
    @Test
    fun manualMatchesPersistAcrossOwnerRecreationAndApplyToWholeShow() {
        val folder = temporaryFolder()
        val provider = FakeProvider()
        val scope = testScope()
        val first = owner(folder, provider, scope, auto = false)
        first.restore()
        val episodes = listOf(episode("e1", 1), episode("e2", 2))
        val movie = movie("movie")
        first.onLibraryChanged(episodes + movie)

        val before = first.snapshot().revision
        val showSnapshot = first.match(MetadataMatchRequest("e1", 77, "tv"))
        first.match(MetadataMatchRequest("movie", 77, "movie"))

        assertTrue(showSnapshot.revision > before)
        assertEquals(77, showSnapshot.items.getValue("e1").id)
        assertEquals(77, showSnapshot.items.getValue("e2").id)
        assertEquals("tv", showSnapshot.items.getValue("e2").mediaType)
        assertNull(showSnapshot.items.getValue("e2").episodeTitle)
        assertEquals("Episode 1", showSnapshot.items.getValue("e1").episodeTitle)

        val restored = owner(folder, provider, scope, auto = false)
        restored.restore()
        restored.onLibraryChanged(episodes + movie)
        val persisted = restored.snapshot()
        assertEquals("tv", persisted.items.getValue("e1").mediaType)
        assertEquals("movie", persisted.items.getValue("movie").mediaType)
        assertEquals(77, persisted.items.getValue("movie").id)
        scope.cancel()
    }

    @Test
    fun artworkRegistryAndDiskCacheSurviveRestartAndRejectUnknownIds() {
        val folder = temporaryFolder()
        val provider = FakeProvider()
        val scope = testScope()
        val first = owner(folder, provider, scope, auto = false)
        first.restore()
        val item = movie("movie")
        first.onLibraryChanged(listOf(item))
        val metadata = first.match(MetadataMatchRequest(item.id, 9, "movie")).items.getValue(item.id)
        val reference = requireNotNull(metadata.posterUrl)
        assertTrue(reference.matches(Regex("^/api/v1/artwork/[a-f0-9]{40}$")))
        assertFalse(reference.contains("image.tmdb.org"))
        val id = reference.substringAfterLast('/')

        assertNotNull(first.artwork(id))
        assertEquals(1, provider.artworkCalls.get())
        assertNotNull(first.artwork(id))
        assertEquals(1, provider.artworkCalls.get())
        assertNull(first.artwork("https://evil.example/poster.jpg"))

        val restoredProvider = FakeProvider()
        val restored = owner(folder, restoredProvider, scope, auto = false)
        restored.restore()
        restored.onLibraryChanged(listOf(item))
        assertEquals(reference, restored.snapshot().items.getValue(item.id).posterUrl)
        assertNotNull(restored.artwork(id))
        assertEquals(0, restoredProvider.artworkCalls.get())
        scope.cancel()
    }

    @Test
    fun blockedBackgroundSearchCannotOverwriteNewerManualMatch() {
        val folder = temporaryFolder()
        val searchStarted = CountDownLatch(1)
        val releaseSearch = CountDownLatch(1)
        val provider = FakeProvider(
            searchBlock = {
                searchStarted.countDown()
                assertTrue(releaseSearch.await(3, TimeUnit.SECONDS))
            },
            searchResult = MediaMetadata(1, "Example Show", mediaType = "tv"),
        )
        val scope = testScope()
        val owner = owner(folder, provider, scope, auto = true)
        owner.restore()
        val item = episode("e1", 1)
        owner.onLibraryChanged(listOf(item))
        assertTrue(searchStarted.await(3, TimeUnit.SECONDS))

        val manual = owner.match(MetadataMatchRequest(item.id, 2, "tv"))
        assertEquals(2, manual.items.getValue(item.id).id)
        releaseSearch.countDown()
        Thread.sleep(100)

        val restored = owner(folder, FakeProvider(), scope, auto = false)
        restored.restore()
        restored.onLibraryChanged(listOf(item))
        assertEquals(2, restored.snapshot().items.getValue(item.id).id)
        scope.cancel()
    }

    @Test
    fun clearWinsOverBlockedBackgroundHydrationAndSnapshotNeverWaitsForProvider() {
        val folder = temporaryFolder()
        val detailsStarted = CountDownLatch(1)
        val releaseDetails = CountDownLatch(1)
        val provider = FakeProvider(
            searchResult = MediaMetadata(1, "Example Show", mediaType = "tv"),
            detailsBlock = { id ->
                if (id == 1L) {
                    detailsStarted.countDown()
                    assertTrue(releaseDetails.await(3, TimeUnit.SECONDS))
                }
            },
        )
        val scope = testScope()
        val owner = owner(folder, provider, scope, auto = true)
        owner.restore()
        val item = episode("e1", 1)
        owner.onLibraryChanged(listOf(item))
        assertTrue(detailsStarted.await(3, TimeUnit.SECONDS))

        val startedAt = System.nanoTime()
        owner.snapshot()
        assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < 100)
        val cleared = owner.clear()
        assertTrue(cleared.items.isEmpty())
        releaseDetails.countDown()
        Thread.sleep(100)

        val restored = owner(folder, FakeProvider(), scope, auto = false)
        restored.restore()
        restored.onLibraryChanged(listOf(item))
        assertTrue(restored.snapshot().items.isEmpty())
        scope.cancel()
    }

    @Test
    fun duplicateArtworkFetchFailsFastAndClearPreventsLateCacheWrite() {
        val folder = temporaryFolder()
        val artworkStarted = CountDownLatch(1)
        val releaseArtwork = CountDownLatch(1)
        val provider = FakeProvider(artworkBlock = {
            artworkStarted.countDown()
            assertTrue(releaseArtwork.await(3, TimeUnit.SECONDS))
        })
        val scope = testScope()
        val owner = owner(folder, provider, scope, auto = false)
        owner.restore()
        val item = movie("movie")
        owner.onLibraryChanged(listOf(item))
        val id = requireNotNull(
            owner.match(MetadataMatchRequest(item.id, 9, "movie")).items.getValue(item.id).posterUrl,
        ).substringAfterLast('/')
        val executor = Executors.newSingleThreadExecutor()
        val first = executor.submit<Artwork> { requireNotNull(owner.artwork(id)) }
        assertTrue(artworkStarted.await(3, TimeUnit.SECONDS))

        val busy = runCatching { owner.artwork(id) }.exceptionOrNull()
        assertTrue(busy is MetadataBusyException)
        owner.clear()
        releaseArtwork.countDown()
        val lateFailure = runCatching { first.get(3, TimeUnit.SECONDS) }.exceptionOrNull()
        assertTrue((lateFailure as? ExecutionException)?.cause is MetadataNotFoundException)
        assertTrue(File(folder, "artwork").listFiles().orEmpty().isEmpty())
        executor.shutdownNow()
        scope.cancel()
    }

    @Test
    fun activeArtworkDoesNotEvictNewSearchOrMatchReferences() {
        val folder = temporaryFolder()
        val activeItems = LinkedHashMap<String, MediaMetadata>()
        val activeSources = LinkedHashMap<String, String>()
        val library = ArrayList<MediaItem>()
        repeat(1_025) { index ->
            val item = movie("active-$index")
            val artworkId = index.toString(16).padStart(40, '0')
            library += item
            activeItems[item.id] = MediaMetadata(
                id = index.toLong() + 1,
                title = "Active $index",
                posterUrl = "/api/v1/artwork/$artworkId",
                mediaType = "movie",
            )
            activeSources[artworkId] = "w500|/active-$index.jpg"
        }
        FileMetadataPersistence(File(folder, "metadata.json")).write(
            Protocol.json.encodeToString(
                PersistedMetadata(revision = 5, items = activeItems, artworkSources = activeSources),
            ).toByteArray(),
        )
        val newItem = movie("new-item")
        library += newItem
        val provider = FakeProvider(
            searchResult = MediaMetadata(
                id = 8_999,
                title = "Search Candidate",
                posterUrl = "w500|/candidate.jpg",
                mediaType = "movie",
            ),
        )
        val scope = testScope()
        val owner = owner(folder, provider, scope, auto = false)
        owner.restore()
        owner.onLibraryChanged(library)

        assertNotNull(owner.artwork(activeSources.keys.first()))
        val candidateReference = requireNotNull(owner.search(newItem.id, "Candidate").results.single().posterUrl)
        assertNotNull(owner.artwork(candidateReference.substringAfterLast('/')))
        val matchedReference = requireNotNull(
            owner.match(MetadataMatchRequest(newItem.id, 9_000, "movie"))
                .items.getValue(newItem.id).posterUrl,
        )
        assertNotNull(owner.artwork(matchedReference.substringAfterLast('/')))
        scope.cancel()
    }

    private fun owner(folder: File, provider: MetadataProvider, scope: CoroutineScope, auto: Boolean) =
        PhoneMetadataOwner(
            metadataFile = File(folder, "metadata.json"),
            artworkDir = File(folder, "artwork"),
            provider = provider,
            scope = scope,
            retryDelayMs = 10,
            autoMatchEnabled = auto,
        )

    private fun temporaryFolder(): File = kotlin.io.path.createTempDirectory("navelo-metadata-test").toFile()
        .apply { deleteOnExit() }

    private fun testScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun episode(id: String, number: Int) = MediaItem(
        id = id,
        filename = "Example.Show.S01E%02d.mkv".format(number),
        displayName = "Example Show $number",
        rootId = "shows",
        relativePath = "Example Show/Season 1/Example.Show.S01E%02d.mkv".format(number),
        type = ItemType.VIDEO,
        showHint = "Example Show",
        titleHint = "Example Show",
        season = 1,
        episode = number,
    )

    private fun movie(id: String) = MediaItem(
        id = id,
        filename = "Example.Movie.2024.mkv",
        displayName = "Example Movie",
        rootId = "movies",
        relativePath = "Example.Movie.2024.mkv",
        type = ItemType.VIDEO,
        titleHint = "Example Movie",
        year = 2024,
    )

    private class FakeProvider(
        private val searchBlock: (() -> Unit)? = null,
        private val searchResult: MediaMetadata? = null,
        private val detailsBlock: ((Long) -> Unit)? = null,
        private val artworkBlock: (() -> Unit)? = null,
    ) : MetadataProvider {
        override val configured = true
        val artworkCalls = AtomicInteger()

        override fun search(item: MediaItem, query: String): List<MediaMetadata> {
            searchBlock?.invoke()
            return listOfNotNull(searchResult)
        }

        override fun details(item: MediaItem, tmdbId: Long, mediaType: String): MediaMetadata {
            detailsBlock?.invoke(tmdbId)
            return MediaMetadata(
                id = tmdbId,
                title = if (mediaType == "tv") "Example Show" else "Example Movie",
                overview = "Details for $tmdbId",
                posterUrl = "w500|/poster-$tmdbId.jpg",
                backdropUrl = "w1280|/backdrop-$tmdbId.jpg",
                episodeTitle = item.episode?.let { "Episode $it" },
                mediaType = mediaType,
            )
        }

        override fun artwork(source: String): Artwork {
            artworkBlock?.invoke()
            artworkCalls.incrementAndGet()
            return Artwork(
                byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte()),
                "image/jpeg",
                "provider",
            )
        }
    }
}
