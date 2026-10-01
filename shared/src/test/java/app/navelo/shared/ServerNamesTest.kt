package app.navelo.shared

import org.junit.Assert.*
import org.junit.Test

class ServerNamesTest {
    @Test fun prefersDeviceNameAndCleansWhitespace() {
        assertEquals("Harsh’s Phone", ServerNames.defaultName("  Harsh’s\nPhone  ", "Google", "Pixel 9"))
        assertEquals("Living room", ServerNames.clean(" Living\u0000\u200B room "))
    }

    @Test fun fallsBackWithoutRepeatingManufacturer() {
        assertEquals("Google Pixel 9", ServerNames.defaultName(null, "Google", "Pixel 9"))
        assertEquals("Samsung Galaxy S24", ServerNames.defaultName(" ", "Samsung", "Samsung Galaxy S24"))
        assertEquals("Navelo phone", ServerNames.defaultName(null, "unknown", "unknown"))
        assertEquals(60, ServerNames.defaultName("x".repeat(100), null, null).length)
    }

    @Test fun addsStableSuffixOnlyForDuplicateNames() {
        val one = server("a72f1234-one", "Pixel 9")
        val two = server("b83e1234-two", "pixel 9")
        val other = server("third", "Bedroom")
        val labels = ServerNames.labels(listOf(one, two, other))
        assertEquals("Pixel 9 · A72F1234", labels[one.serverId])
        assertEquals("pixel 9 · B83E1234", labels[two.serverId])
        assertEquals("Bedroom", labels[other.serverId])
        assertEquals(labels, ServerNames.labels(listOf(other, two, one)))
        assertEquals("Pixel 9", ServerNames.labels(listOf(one))[one.serverId])
    }

    @Test fun expandsSuffixWhenIdsSharePrefix() {
        val one = server("12345678aaaa", "Phone")
        val two = server("12345678bbbb", "Phone")
        val labels = ServerNames.labels(listOf(one, two))
        assertNotEquals(labels[one.serverId], labels[two.serverId])
        assertEquals("Phone · 12345678A", labels[one.serverId])
    }

    private fun server(id: String, name: String) = DiscoveredServer(id, name, "192.168.1.10", 8765)
}
