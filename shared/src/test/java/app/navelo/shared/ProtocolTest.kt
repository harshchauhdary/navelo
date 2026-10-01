package app.navelo.shared

import org.junit.Assert.*
import org.junit.Test
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

class ProtocolTest {
    @Test fun rangesAtFortyGigabytesStay64Bit() {
        for (size in listOf(5L, 20L, 45L).map { it * 1024 * 1024 * 1024 }) {
            val range = ByteRanges.parse("bytes=${size - 10}-", size)!!
            assertEquals(10L, range.length)
            assertEquals("bytes ${size - 10}-${size - 1}/$size", range.contentRange(size))
            assertEquals(ByteRange(size - 1024, size - 1), ByteRanges.parse("bytes=-1024", size))
        }
    }
    @Test fun validRanges() {
        assertNull(ByteRanges.parse(null, 100))
        assertEquals(ByteRange(0, 0), ByteRanges.parse("bytes=0-0", 100))
        assertEquals(ByteRange(20, 99), ByteRanges.parse("bytes=20-999", 100))
        assertEquals(ByteRange(0, 99), ByteRanges.parse("bytes=-999", 100))
    }
    @Test fun invalidRanges() {
        listOf("bytes=", "bytes=-", "bytes=-0", "bytes=100-", "bytes=50-20", "items=0-4", "bytes=0-1,4-5", "bytes=9223372036854775808-", "bytes=--1", "bytes=a-b").forEach {
            try { ByteRanges.parse(it, 100); fail(it) } catch (_: InvalidRange) { }
        }
        try { ByteRanges.parse("bytes=0-0", 0); fail() } catch (_: InvalidRange) { }
    }
    @Test fun stableIdsUseDocumentIdentityNotDisplayName() {
        assertEquals(StableIds.forDocument("a", "id1"), StableIds.forDocument("a", "id1"))
        assertNotEquals(StableIds.forDocument("a", "id1"), StableIds.forDocument("b", "id1"))
        assertNotEquals(StableIds.forDocument("a", "bc"), StableIds.forDocument("ab", "c"))
        assertEquals(64, StableIds.forDocument("a", "id1").length)
    }
    @Test fun exampleFilenamesAndFolders() {
        for (file in listOf("Breaking.Bad.S01E03.1080p.mkv", "Breaking Bad - S01E03.mkv")) {
            val value = FilenameParser.parse(file)
            assertEquals("Breaking Bad", value.show); assertEquals(1, value.season); assertEquals(3, value.episode)
        }
        assertEquals(ParsedName("Dune Part Two", 2024), FilenameParser.parse("Dune.Part.Two.2024.2160p.mkv"))
        assertEquals(ParsedName("Breaking Bad", show="Breaking Bad", season=1, episode=3), FilenameParser.parse("E03.mkv", "Breaking Bad/Season 01/E03.mkv"))
        assertEquals("1917", FilenameParser.parse("1917.2019.1080p.BluRay.mkv").title)
    }
    @Test fun serializationHasNoStorageUri() {
        val value = MediaItem("opaque", "Dune.mkv", "Dune", rootId="root", relativePath="Dune.mkv", size=45L*1024*1024*1024)
        val json = Protocol.json.encodeToString(value)
        assertFalse(json.contains("content://")); assertEquals(value, Protocol.json.decodeFromString<MediaItem>(json))
        val decoded = Protocol.json.decodeFromString<ServerInfo>("""{"serverId":"a","displayName":"Phone","futureField":true}""")
        assertEquals(1, decoded.apiVersion)
    }
}
