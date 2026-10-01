package app.navelo.server

import java.io.ByteArrayInputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class BoundedInputStreamTest {
    @Test
    fun neverReadsPastResponseBoundary() {
        val source = ByteArrayInputStream(ByteArray(32) { it.toByte() })
        val bounded = BoundedInputStream(source, 7)
        val buffer = ByteArray(20)

        val count = bounded.read(buffer)

        assertEquals(7, count)
        assertArrayEquals(ByteArray(7) { it.toByte() }, buffer.copyOf(count))
        assertEquals(-1, bounded.read(buffer))
        assertEquals(25, source.available())
    }

    @Test
    fun skipAndSingleByteReadsAlsoHonorBoundary() {
        val bounded = BoundedInputStream(ByteArrayInputStream(ByteArray(10) { it.toByte() }), 5)
        assertEquals(3, bounded.skip(3))
        assertEquals(3, bounded.read())
        assertEquals(4, bounded.read())
        assertEquals(-1, bounded.read())
    }
}
