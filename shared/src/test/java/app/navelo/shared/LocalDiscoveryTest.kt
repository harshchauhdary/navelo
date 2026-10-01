package app.navelo.shared

import org.junit.Assert.*
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import kotlinx.serialization.decodeFromString

class LocalDiscoveryTest {
    @Test fun hotspotDirectedProbeReturnsIdentityAndSenderAddress() {
        val host = LocalAnnouncer()
        host.start("stable-phone-id", "Media Phone", 9876)
        try {
            DatagramSocket().use { client ->
                client.soTimeout = 2500
                client.send(DatagramPacket(LocalDiscovery.QUERY, LocalDiscovery.QUERY.size, InetAddress.getLoopbackAddress(), LocalDiscovery.PORT))
                val packet = DatagramPacket(ByteArray(1024), 1024)
                client.receive(packet)
                val beacon = Protocol.json.decodeFromString<LocalDiscovery.Beacon>(packet.data.copyOfRange(0,packet.length).toString(Charsets.UTF_8))
                assertEquals("stable-phone-id",beacon.id)
                assertEquals(9876,beacon.port)
                assertEquals(LocalDiscovery.PORT,packet.port)
                assertTrue(packet.address.isLoopbackAddress)
            }
        } finally { host.stop() }
    }
    @Test fun renamingUpdatesHotspotDiscoveryWithoutChangingIdentity() {
        val host = LocalAnnouncer()
        try {
            for (name in listOf("Pixel 9", "Bedroom media")) {
                host.start("stable-phone-id", name, 9876)
                DatagramSocket().use { client ->
                    client.soTimeout = 2500
                    client.send(DatagramPacket(LocalDiscovery.QUERY, LocalDiscovery.QUERY.size, InetAddress.getLoopbackAddress(), LocalDiscovery.PORT))
                    val packet = DatagramPacket(ByteArray(1024), 1024)
                    client.receive(packet)
                    val beacon = Protocol.json.decodeFromString<LocalDiscovery.Beacon>(packet.data.copyOfRange(0, packet.length).toString(Charsets.UTF_8))
                    assertEquals("stable-phone-id", beacon.id)
                    assertEquals(name, beacon.name)
                    assertEquals(9876, beacon.port)
                }
            }
        } finally { host.stop() }
    }

    @Test fun unrelatedUdpDoesNotExposeAnything() {
        val host = LocalAnnouncer(); host.start("a","Phone",8765)
        try {
            DatagramSocket().use { client ->
                client.soTimeout=200
                val body="not navelo".toByteArray()
                client.send(DatagramPacket(body,body.size,InetAddress.getLoopbackAddress(),LocalDiscovery.PORT))
                try { client.receive(DatagramPacket(ByteArray(1024),1024)); fail("Unexpected response") } catch (_:SocketTimeoutException) { }
            }
        } finally { host.stop() }
    }
    @Test fun ipv6RouteHasBrackets() {
        assertEquals("http://[fe80::1%wlan0]:8765",DiscoveredServer("a","Phone","fe80::1%wlan0",8765).baseUrl)
        assertEquals("http://192.168.43.1:8765",DiscoveredServer("a","Phone","192.168.43.1",8765).baseUrl)
    }
    @Test fun discoveryIdentityAndNameAreBoundedForUiAndRegistrySafety() {
        assertTrue(LocalDiscovery.validId("21b6c97f-cfad-4f84-b96f-9951d676484f"))
        assertFalse(LocalDiscovery.validId("bad id"))
        assertFalse(LocalDiscovery.validId("\n"))
        assertEquals("Living Room TV", LocalDiscovery.cleanName("  Living\u0000 Room TV  "))
        assertEquals("Navelo server", LocalDiscovery.cleanName("\u0000\n"))
        assertEquals(80, LocalDiscovery.cleanName("x".repeat(100)).length)
    }
}
