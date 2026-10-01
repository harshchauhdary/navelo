package app.navelo.tv

import app.navelo.shared.DiscoveredServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Test

class ConnectionPolicyTest {
    private val wifi = DiscoveredServer("phone", "Media Phone", "192.168.1.10", 8765)
    private val ipv6 = wifi.copy(host = "fe80::1234%wlan0")
    private val hotspot = wifi.copy(host = "192.168.43.1")

    @Test fun alternatingAnnouncementsNeverReplaceAWorkingRoute() {
        listOf(ipv6, wifi, ipv6, hotspot).forEach {
            assertNull(ConnectionPolicy.replacement(wifi, listOf(it), online = true))
        }
    }

    @Test fun offlinePhoneCanMoveToItsNewHotspotAddress() {
        assertEquals(hotspot, ConnectionPolicy.replacement(wifi, listOf(hotspot), online = false))
    }

    @Test fun scopedIpv6AnnouncementCannotCrashPlaybackOrReplaceThePhone() {
        val reportedByTv = wifi.copy(host = "fe80::ac30:42ff:fec6:8df8%wlan0")
        assertFalse(ConnectionPolicy.supportsHttp(reportedByTv))
        assertNull(ConnectionPolicy.mediaUrl(reportedByTv, "movie"))
        assertNull(ConnectionPolicy.replacement(wifi, listOf(reportedByTv), false))
        assertEquals("http://192.168.1.10:8765/media/movie", ConnectionPolicy.mediaUrl(wifi, "movie"))
    }

    @Test fun unscopedIpv6StillWorksAndMediaIdsAreEscaped() {
        assertEquals("http://[fd00::1234]:8765/media/a%2Fb", ConnectionPolicy.mediaUrl(wifi.copy(host = "fd00::1234"), "a/b"))
    }

    @Test fun recoveryDoesNotChooseADifferentPhoneOrReconnectForANameChange() {
        assertNull(ConnectionPolicy.replacement(wifi, listOf(hotspot.copy(serverId = "other")), false))
        assertNull(ConnectionPolicy.replacement(wifi, listOf(wifi.copy(displayName = "Renamed")), false))
        assertNull(ConnectionPolicy.replacement(null, listOf(wifi), false))
    }
}
