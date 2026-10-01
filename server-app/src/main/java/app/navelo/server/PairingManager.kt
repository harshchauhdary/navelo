package app.navelo.server

import app.navelo.shared.PairRequest
import app.navelo.shared.PairTicket
import app.navelo.shared.PendingPair
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

internal class PairingManager(
    trusted: List<TrustedRecord>,
    private val clock: () -> Long = System::currentTimeMillis,
    private val randomBytes: (Int) -> ByteArray = { size -> ByteArray(size).also(SECURE_RANDOM::nextBytes) },
) {
    private data class RequestRecord(
        val requestId: String,
        val clientId: String,
        val displayName: String,
        val pin: String,
        val secretHash: ByteArray,
        val expiresAt: Long,
        var status: String = STATUS_PENDING,
        var token: String? = null,
    )

    private val requests = LinkedHashMap<String, RequestRecord>()
    private val trustedByClient = trusted.associateByTo(LinkedHashMap()) { it.clientId }

    @Synchronized
    fun request(request: PairRequest): PairTicket {
        require(request.clientId.isNotBlank() && request.clientId.length <= 200) { "Invalid client ID" }
        require(request.displayName.isNotBlank() && request.displayName.length <= 100) { "Invalid display name" }
        require(validClientSecret(request.clientSecret)) { "Client secret must contain 32 random bytes" }
        expire()
        requests.values.removeAll { it.clientId == request.clientId && it.status == STATUS_PENDING }
        val id = encode(randomBytes(18))
        val pinSeed = randomBytes(4).fold(0L) { value, byte -> (value shl 8) xor (byte.toLong() and 0xff) }
        val pin = (pinSeed % 1_000_000).toString().padStart(6, '0')
        val record = RequestRecord(
            requestId = id,
            clientId = request.clientId,
            displayName = request.displayName.trim(),
            pin = pin,
            secretHash = sha256(request.clientSecret),
            expiresAt = clock() + REQUEST_LIFETIME_MS,
        )
        requests[id] = record
        return record.ticket()
    }

    @Synchronized
    fun poll(requestId: String, clientSecret: String, serverId: String): PairTicket? {
        expire()
        val record = requests[requestId] ?: return null
        if (!MessageDigest.isEqual(record.secretHash, sha256(clientSecret))) return null
        return record.ticket(serverId)
    }

    @Synchronized
    fun approve(requestId: String): Boolean {
        expire()
        val request = requests[requestId]?.takeIf { it.status == STATUS_PENDING } ?: return false
        val token = encode(randomBytes(32))
        trustedByClient[request.clientId] = TrustedRecord(
            request.clientId,
            request.displayName,
            clock(),
            encode(sha256(token)),
        )
        request.status = STATUS_APPROVED
        request.token = token
        return true
    }

    @Synchronized
    fun deny(requestId: String): Boolean {
        expire()
        val request = requests[requestId]?.takeIf { it.status == STATUS_PENDING } ?: return false
        request.status = STATUS_DENIED
        return true
    }

    @Synchronized
    fun revoke(clientId: String): Boolean = trustedByClient.remove(clientId) != null

    @Synchronized
    fun authenticate(token: String): TrustedRecord? {
        val candidate = sha256(token)
        return trustedByClient.values.firstOrNull { stored ->
            runCatching { MessageDigest.isEqual(Base64.getUrlDecoder().decode(stored.tokenHash), candidate) }
                .getOrDefault(false)
        }
    }

    @Synchronized
    fun pending(): List<PendingPair> {
        expire()
        return requests.values.filter { it.status == STATUS_PENDING }.map {
            PendingPair(it.requestId, it.clientId, it.displayName, it.pin, it.expiresAt)
        }
    }

    @Synchronized
    fun trustedRecords(): List<TrustedRecord> = trustedByClient.values.toList()

    private fun RequestRecord.ticket(serverId: String? = null) = PairTicket(
        requestId = requestId,
        status = status,
        pin = pin,
        token = token,
        serverId = if (status == STATUS_APPROVED) serverId else null,
    )

    private fun expire() {
        val now = clock()
        requests.entries.removeAll { it.value.expiresAt <= now }
    }

    private fun validClientSecret(secret: String): Boolean = runCatching {
        Base64.getUrlDecoder().decode(secret).size == 32
    }.getOrDefault(false)

    private fun sha256(value: String): ByteArray = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))

    private fun encode(value: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value)

    private companion object {
        val SECURE_RANDOM = SecureRandom()
        const val REQUEST_LIFETIME_MS = 5 * 60 * 1000L
        const val STATUS_PENDING = "pending"
        const val STATUS_APPROVED = "approved"
        const val STATUS_DENIED = "denied"
    }
}

internal class RequestRateLimiter(
    private val maximum: Int,
    private val windowMs: Long,
    private val maximumKeys: Int = 1_024,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val events = HashMap<String, ArrayDeque<Long>>()

    @Synchronized
    fun allow(key: String): Boolean {
        val now = clock()
        val cutoff = now - windowMs
        if (events.size >= maximumKeys && key !in events) {
            events.entries.removeAll { entry ->
                while (entry.value.firstOrNull()?.let { it <= cutoff } == true) entry.value.removeFirst()
                entry.value.isEmpty()
            }
            if (events.size >= maximumKeys) return false
        }
        val queue = events.getOrPut(key) { ArrayDeque() }
        while (queue.firstOrNull()?.let { it <= cutoff } == true) queue.removeFirst()
        if (queue.size >= maximum) return false
        queue.addLast(now)
        return true
    }
}
