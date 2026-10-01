package app.navelo.server

import app.navelo.shared.PairRequest
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PairingManagerTest {
    private val secret = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })

    @Test
    fun approvalRequiresOriginalSecretAndCreatesAuthenticatingToken() {
        var now = 1_000L
        var seed = 1
        val manager = PairingManager(
            emptyList(),
            clock = { now },
            randomBytes = { size -> ByteArray(size) { (seed++).toByte() } },
        )
        val pending = manager.request(PairRequest("living-room", "Living Room TV", secret))

        assertEquals("pending", pending.status)
        assertEquals(6, pending.pin.length)
        assertNull(manager.poll(pending.requestId, "wrong", "server-1"))
        assertTrue(manager.approve(pending.requestId))

        val approved = manager.poll(pending.requestId, secret, "server-1")
        assertNotNull(approved?.token)
        assertEquals("approved", approved?.status)
        assertEquals("server-1", approved?.serverId)
        assertEquals("living-room", manager.authenticate(approved!!.token!!)?.clientId)
        assertNull(manager.authenticate(secret))
        assertNotEquals(approved.token, manager.trustedRecords().single().tokenHash)
        assertTrue(manager.revoke("living-room"))
        assertNull(manager.authenticate(approved.token!!))
    }

    @Test
    fun deniedAndExpiredRequestsNeverCreateTrust() {
        var now = 5_000L
        val manager = PairingManager(emptyList(), clock = { now }) { size -> ByteArray(size) { 7 } }
        val denied = manager.request(PairRequest("denied", "Bedroom TV", secret))
        assertTrue(manager.deny(denied.requestId))
        assertEquals("denied", manager.poll(denied.requestId, secret, "server")?.status)
        assertTrue(manager.trustedRecords().isEmpty())

        val expired = manager.request(PairRequest("expired", "Old TV", secret))
        now += 5 * 60 * 1000L
        assertNull(manager.poll(expired.requestId, secret, "server"))
        assertFalse(manager.approve(expired.requestId))
        assertTrue(manager.pending().isEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun shortClientSecretIsRejected() {
        PairingManager(emptyList()).request(PairRequest("tv", "TV", "not-32-random-bytes"))
    }

    @Test
    fun limiterAllowsOnlyBoundedAttemptsPerWindow() {
        var now = 0L
        val limiter = RequestRateLimiter(2, 1_000) { now }
        assertTrue(limiter.allow("peer"))
        assertTrue(limiter.allow("peer"))
        assertFalse(limiter.allow("peer"))
        now = 1_001
        assertTrue(limiter.allow("peer"))
    }

    @Test
    fun limiterBoundsUntrustedKeyCardinalityAndReclaimsExpiredKeys() {
        var now = 0L
        val limiter = RequestRateLimiter(maximum = 2, windowMs = 1_000, clock = { now }, maximumKeys = 2)
        assertTrue(limiter.allow("one"))
        assertTrue(limiter.allow("two"))
        assertFalse(limiter.allow("three"))
        now = 1_001
        assertTrue(limiter.allow("three"))
    }
}
