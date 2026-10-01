package app.navelo.server

import app.navelo.shared.ByteRanges
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class SparseFileSeekTest {
    @Test fun actualFileChannelsSeekDirectlyIntoFiveTwentyAndFortyFiveGiBFiles() {
        for (gib in listOf(5L, 20L, 45L)) {
            val path = Files.createTempFile("navelo-large-seek-", ".bin")
            try {
                val size = gib * 1024 * 1024 * 1024
                val expected = ByteArray(1024) { (it % 251).toByte() }
                RandomAccessFile(path.toFile(), "rw").use { file ->
                    file.setLength(size) // Sparse, no multi-gigabyte fixture allocation.
                    file.seek(size - expected.size)
                    file.write(expected)
                }
                val range = ByteRanges.parse("bytes=-1024", size)!!
                FileInputStream(path.toFile()).use { file ->
                    file.channel.position(range.start)
                    assertEquals(size - 1024, file.channel.position())
                    BoundedInputStream(file, range.length).use { stream ->
                        assertArrayEquals(expected, stream.readBytes())
                        assertEquals(-1, stream.read())
                    }
                }
                assertEquals(size, Files.size(path))
            } finally { Files.deleteIfExists(path) }
        }
    }
}
