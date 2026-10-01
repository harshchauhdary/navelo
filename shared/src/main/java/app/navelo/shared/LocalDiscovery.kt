package app.navelo.shared

import android.content.Context
import android.net.ConnectivityManager
import android.os.SystemClock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A small LAN-only discovery side channel for Android hotspots that filter mDNS.
 * No credentials or media are sent. Replies use the sender's source address, never a supplied URL.
 */
internal object LocalDiscovery {
    const val PORT = 8766
    val QUERY = "NAVELO_DISCOVER_V1".toByteArray(Charsets.US_ASCII)
    @Serializable data class Beacon(val navelo: Int = 1, val id: String, val name: String, val port: Int)

    fun validId(id: String): Boolean = id.length in 1..128 && id.all {
        it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' || it == ':'
    }

    fun cleanName(name: String): String = name
        .filterNot(Char::isISOControl)
        .trim()
        .take(80)
        .ifBlank { "Navelo server" }

    fun targets(context: Context): Set<InetAddress> {
        val result = mutableSetOf(InetAddress.getByName("255.255.255.255"))
        runCatching { NetworkInterface.getNetworkInterfaces().toList().filter { it.isUp && !it.isLoopback }.forEach { network ->
            network.interfaceAddresses.mapNotNull { it.broadcast }.forEach { result.add(it) }
        } }
        // A hotspot host is the TV's gateway. Directed probes survive multicast/broadcast filtering.
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            cm.allNetworks.forEach { network -> cm.getLinkProperties(network)?.routes?.filter { it.isDefaultRoute }?.mapNotNull { it.gateway }?.filterNot { it.isAnyLocalAddress }?.forEach { result.add(it) } }
        }
        return result
    }
}

internal class LocalAnnouncer {
    @Volatile private var socket: DatagramSocket? = null
    @Synchronized fun start(serverId: String, displayName: String, port: Int) {
        stop()
        val sock = runCatching { DatagramSocket(null).apply { reuseAddress = true; bind(InetSocketAddress(LocalDiscovery.PORT)) } }.getOrNull() ?: return
        socket = sock
        val reply = Protocol.json.encodeToString(LocalDiscovery.Beacon(id=serverId, name=displayName.take(80), port=port)).toByteArray()
        Thread({
            val buffer = ByteArray(256)
            val recent = LinkedHashMap<String, Long>()
            while (!sock.isClosed) {
                runCatching {
                    val packet = DatagramPacket(buffer, buffer.size); sock.receive(packet)
                    if (packet.length == LocalDiscovery.QUERY.size && packet.data.copyOfRange(0, packet.length).contentEquals(LocalDiscovery.QUERY)) {
                        val now = System.currentTimeMillis(); val host = packet.address.hostAddress.orEmpty()
                        if (now - (recent[host] ?: 0) > 900) {
                            if (recent.size > 128) recent.clear()
                            recent[host] = now
                            sock.send(DatagramPacket(reply, reply.size, packet.address, packet.port))
                        }
                    }
                }
            }
        }, "Navelo-hotspot-announcer").apply { isDaemon = true; start() }
    }
    @Synchronized fun stop() { socket?.close(); socket = null }
}

internal class LocalFinder(private val context: Context, private val onFound: (DiscoveredServer) -> Unit) {
    private var scheduler: java.util.concurrent.ScheduledExecutorService? = null
    private var socket: DatagramSocket? = null
    @Synchronized fun start() {
        if (socket != null) return
        val sock = runCatching { DatagramSocket().apply { broadcast = true } }.getOrNull() ?: return
        socket = sock
        scheduler = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "Navelo-hotspot-probe").apply { isDaemon = true } }.also { executor ->
            executor.scheduleWithFixedDelay({ LocalDiscovery.targets(context).forEach { address ->
                runCatching { sock.send(DatagramPacket(LocalDiscovery.QUERY, LocalDiscovery.QUERY.size, address, LocalDiscovery.PORT)) }
            } }, 0, 8, TimeUnit.SECONDS)
        }
        Thread({
            val buffer = ByteArray(1024)
            var receiveWindowStartedAt = SystemClock.elapsedRealtime()
            var repliesAcceptedInWindow = 0
            while (!sock.isClosed) {
                runCatching {
                    val packet = DatagramPacket(buffer, buffer.size); sock.receive(packet)
                    if (packet.port == LocalDiscovery.PORT) {
                        val beacon = Protocol.json.decodeFromString<LocalDiscovery.Beacon>(packet.data.copyOfRange(0, packet.length).toString(Charsets.UTF_8))
                        val now = SystemClock.elapsedRealtime()
                        if (now - receiveWindowStartedAt >= RECEIVE_WINDOW_MS) {
                            receiveWindowStartedAt = now
                            repliesAcceptedInWindow = 0
                        }
                        if (beacon.navelo == 1 && LocalDiscovery.validId(beacon.id) &&
                            beacon.port in 1..65535 && repliesAcceptedInWindow < MAX_REPLIES_PER_WINDOW
                        ) {
                            repliesAcceptedInWindow++
                            onFound(
                                DiscoveredServer(
                                    beacon.id,
                                    LocalDiscovery.cleanName(beacon.name),
                                    packet.address.hostAddress!!,
                                    beacon.port,
                                ),
                            )
                        }
                    }
                }
            }
        }, "Navelo-hotspot-results").apply { isDaemon = true; start() }
    }
    @Synchronized fun stop() { scheduler?.shutdownNow(); scheduler = null; socket?.close(); socket = null }

    private companion object {
        const val RECEIVE_WINDOW_MS = 1_000L
        const val MAX_REPLIES_PER_WINDOW = 32
    }
}
