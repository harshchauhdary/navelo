package app.navelo.tv

import app.navelo.shared.ItemType
import app.navelo.shared.MediaItem
import app.navelo.shared.Protocol
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepositoryPolicyTest {
    @Test
    fun reconnectBackoffIsBounded() {
        assertEquals(2_000, ReconnectBackoff.delayMs(1))
        assertEquals(16_000, ReconnectBackoff.delayMs(4))
        assertEquals(30_000, ReconnectBackoff.delayMs(20))
    }

    @Test
    fun shortClipIsNotMarkedWatchedJustBecauseItIsNearTheEnd() {
        assertFalse(WatchPolicy.create(positionMs = 15_000, durationMs = 20_000, now = 1).watched)
        assertFalse(WatchPolicy.create(positionMs = 60_000, durationMs = 600_000, now = 1).watched)
        assertTrue(WatchPolicy.create(positionMs = 550_000, durationMs = 600_000, now = 1).watched)
    }

    @Test
    fun progressAndSettingsSurviveThePersistedUserStateRoundTrip() {
        val expected = StoredUser(
            clientId = "tv-client",
            watch = mapOf("episode" to WatchProgress(125_000, 600_000, 99, watched = false)),
            settings = TvSettings(autoNext = false, subtitles = true, tmdbConfigured = true),
        )

        val restored = Protocol.json.decodeFromString<StoredUser>(Protocol.json.encodeToString(expected))

        assertEquals(expected, restored)
    }
}

class SubtitleAssociationTest {
    private val video = MediaItem(
        id = "video",
        filename = "Show.S01E02.mkv",
        displayName = "Show S01E02",
        parentId = "folder-a",
        rootId = "root",
        relativePath = "Show/Season 1/Show.S01E02.mkv",
        extension = "mkv",
        type = ItemType.VIDEO,
    )

    @Test
    fun associatesLanguageAndForcedSubtitlesInSameFolder() {
        val english = subtitle("en", "Show.S01E02.en.srt", "folder-a")
        val forced = subtitle("forced", "Show.S01E02.en.forced.srt", "folder-a")
        val differentEpisode = subtitle("other", "Show.S01E03.en.srt", "folder-a")
        val differentFolder = subtitle("folder", "Show.S01E02.en.srt", "folder-b")

        assertEquals(
            setOf(english.id, forced.id),
            SubtitleAssociation.forVideo(video, listOf(differentEpisode, differentFolder, forced, english)).map { it.id }.toSet(),
        )
    }

    @Test
    fun detectsSubtitleByExtensionWhenServerTypeIsGeneric() {
        val subtitle = subtitle("generic", "Show.S01E02.vtt", "folder-a").copy(type = ItemType.VIDEO)

        assertEquals(listOf("generic"), SubtitleAssociation.forVideo(video, listOf(subtitle)).map { it.id })
    }

    @Test
    fun neverAssociatesANameMatchAcrossLibraryRoots() {
        val otherRoot = subtitle("other-root", "Show.S01E02.en.srt", "folder-a").copy(rootId = "second-root")

        assertTrue(SubtitleAssociation.forVideo(video, listOf(otherRoot)).isEmpty())
    }

    @Test
    fun subtitleLabelIsHumanReadable() {
        assertEquals("English (forced)", SubtitleAssociation.label(subtitle("forced", "Show.S01E02.en.forced.srt", "folder-a")))
    }

    private fun subtitle(id: String, filename: String, parent: String) = MediaItem(
        id = id,
        filename = filename,
        displayName = filename,
        parentId = parent,
        rootId = "root",
        relativePath = "Show/Season 1/$filename",
        extension = filename.substringAfterLast('.'),
        type = ItemType.SUBTITLE,
    )
}
