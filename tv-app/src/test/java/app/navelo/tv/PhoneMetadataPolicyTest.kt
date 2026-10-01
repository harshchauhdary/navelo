package app.navelo.tv

import app.navelo.shared.DiscoveredServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneMetadataPolicyTest {
    private val phone = DiscoveredServer("phone-stable-id", "Media Phone", "192.168.1.10", 8765)

    @Test
    fun artworkAcceptsOnlyOpaqueRelativePhonePaths() {
        assertEquals("asset_ABC-123", ArtworkPolicy.assetId("/api/v1/artwork/asset_ABC-123"))
        assertNull(ArtworkPolicy.assetId("https://image.tmdb.org/t/p/w500/poster.jpg"))
        assertNull(ArtworkPolicy.assetId("https://api.themoviedb.org/3/movie/1"))
        assertNull(ArtworkPolicy.assetId("/api/v1/artwork/../media/secret"))
        assertNull(ArtworkPolicy.assetId("/api/v1/artwork/id?token=leak"))
    }

    @Test
    fun artworkRoutesToPairedPhoneAndUsesAddressIndependentCacheKey() {
        val reference = "/api/v1/artwork/opaque-id"

        assertEquals(
            "http://192.168.1.10:8765/api/v1/artwork/opaque-id",
            ArtworkPolicy.artworkUrl(phone, reference),
        )
        assertEquals(
            ArtworkPolicy.cacheKey(phone.serverId, "opaque-id"),
            ArtworkPolicy.cacheKey(phone.copy(host = "192.168.43.1").serverId, "opaque-id"),
        )
        assertNull(ArtworkPolicy.artworkUrl(phone, "https://example.com/art.jpg"))
    }

    @Test
    fun metadataPollingIsFastWhilePhoneWorkCanStillArriveAndBoundedWhenIdle() {
        assertEquals(5_000L, MetadataPollingPolicy.delayMs(metadataEmpty = true, matchingActive = false))
        assertEquals(5_000L, MetadataPollingPolicy.delayMs(metadataEmpty = false, matchingActive = true))
        assertEquals(30_000L, MetadataPollingPolicy.delayMs(metadataEmpty = false, matchingActive = false))
    }

    @Test
    fun staleOrPreviousPhoneSnapshotsCannotOverwriteCurrentMetadata() {
        assertTrue(MetadataSnapshotPolicy.shouldApply("phone-a", "phone-a", 7, 7))
        assertTrue(MetadataSnapshotPolicy.shouldApply("phone-a", "phone-a", 7, 8))
        assertFalse(MetadataSnapshotPolicy.shouldApply("phone-a", "phone-a", 8, 7))
        assertFalse(MetadataSnapshotPolicy.shouldApply("phone-b", "phone-a", 0, 99))
        assertFalse(MetadataSnapshotPolicy.shouldApply(null, "phone-a", 0, 99))
    }

    @Test
    fun identicalMetadataPollsDoNotRewriteTheLibraryCache() {
        val items = mapOf("movie" to Metadata(1, "Movie"))

        assertFalse(MetadataSnapshotPolicy.shouldPersist(4, 4, items, items.toMap()))
        assertTrue(MetadataSnapshotPolicy.shouldPersist(4, 5, items, items))
        assertTrue(MetadataSnapshotPolicy.shouldPersist(4, 4, items, emptyMap()))
    }

    @Test
    fun rescanStatusWaitsForItsOwnTicketAndReportsRestartOrFailure() {
        assertEquals(RescanStatus.PENDING, RescanStatusPolicy.evaluate(8, 8, 7, null))
        assertEquals(RescanStatus.COMPLETE, RescanStatusPolicy.evaluate(8, 8, 8, null))
        assertEquals(RescanStatus.FAILED, RescanStatusPolicy.evaluate(8, 8, 8, "Phone could not read a media folder."))
        assertEquals(RescanStatus.RESTARTED, RescanStatusPolicy.evaluate(8, 0, 0, null))
    }
}
