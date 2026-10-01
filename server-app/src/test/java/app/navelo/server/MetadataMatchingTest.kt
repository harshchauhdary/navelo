package app.navelo.server

import app.navelo.shared.FilenameParser
import app.navelo.shared.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetadataMatchingTest {
    @Test fun normalizationFoldsLatinAccentsAndPunctuation() {
        assertEquals("amelie and friends", MetadataMatcher.normalize("Amélie & Friends!"))
    }

    @Test fun normalizationPreservesNonLatinCombiningMarks() {
        assertEquals("किताब", MetadataMatcher.normalize("किताब"))
        assertEquals("東京物語", MetadataMatcher.normalize("東京物語"))
    }

    @Test fun accentedReleaseTitleCanMatchPlainProviderTitle() {
        val expected = MediaMetadata(1, "Amelie", year = "2001")
        assertEquals(expected, MetadataMatcher.confidentMatch("Amélie", 2001, listOf(expected)))
    }

    @Test fun parsedReleaseFilenameSelectsExactYearOverAdjacentYear() {
        val parsed = FilenameParser.parse("500 Miles 2026 1080p WEB-DL HEVC x265 5.1 BONE.mkv")
        assertEquals("500 Miles", parsed.title)
        assertEquals(2026, parsed.year)
        val correct = MediaMetadata(1242876, "500 Miles", year = "2026", mediaType = "movie")
        val candidates = listOf(
            MediaMetadata(1525032, "500 Miles", year = "2025", mediaType = "movie"),
            MediaMetadata(317882, "500 Miles", year = "2014", mediaType = "movie"),
            correct,
        )
        assertEquals(correct, MetadataMatcher.confidentMatch(parsed.title, parsed.year, candidates))
    }

    @Test fun unknownYearDoesNotTieWithAnExactKnownYear() {
        val exact = MediaMetadata(1, "Example", year = "2026")
        assertEquals(exact, MetadataMatcher.confidentMatch("Example", 2026,
            listOf(MediaMetadata(2, "Example"), exact)))
    }

    @Test fun identicalTitleAndYearRemainAmbiguous() {
        assertNull(MetadataMatcher.confidentMatch("Example", 2026,
            listOf(MediaMetadata(1, "Example", year = "2026"), MediaMetadata(2, "Example", year = "2026"))))
        assertNull(MetadataMatcher.confidentMatch("Example", null,
            listOf(MediaMetadata(1, "Example", year = "2025"), MediaMetadata(2, "Example", year = "2026"))))
    }

    @Test fun nearbyYearCanMatchAloneButDistantYearCannot() {
        val nearby = MediaMetadata(1, "Example", year = "2025")
        assertEquals(nearby, MetadataMatcher.confidentMatch("Example", 2026, listOf(nearby)))
        assertNull(MetadataMatcher.confidentMatch("Example", 2026,
            listOf(MediaMetadata(2, "Example", year = "2001"))))
    }
}
