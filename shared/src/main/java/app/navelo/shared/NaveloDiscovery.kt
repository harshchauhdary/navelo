package app.navelo.shared

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Identity is stable; the address is merely the latest route to that identity. */
data class DiscoveredServer(val serverId: String, val displayName: String, val host: String, val port: Int) {
    val baseUrl: String get() = "http://${if (':' in host) "[$host]" else host}:$port"
}
@Suppress("DEPRECATION")
class NaveloDiscovery(context: Context) {
    private val app = context.applicationContext
    private val nsd = app.getSystemService(NsdManager::class.java)
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val mutable = MutableStateFlow<List<DiscoveredServer>>(emptyList())
    val servers: StateFlow<List<DiscoveredServer>> = mutable.asStateFlow()
    private val fallback = LocalFinder(app) { server -> handler.post {
        if (enabled) record(server)
    } }
    private var enabled = false
    private var generation = 0
    private var listener: NsdManager.DiscoveryListener? = null
    private val pending = ArrayDeque<Pair<Int, NsdServiceInfo>>()
    private val foundNames = mutableMapOf<String, String>()
    private val lastSeen = mutableMapOf<String, Long>()
    private var resolving = false
    private var activeResolveToken = 0L
    private var activeServiceName: String? = null
    private var lock: WifiManager.MulticastLock? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { handler.post { restart() } }
        override fun onLost(network: Network) { handler.post { restart() } }
    }
    private val refresh = object : Runnable { override fun run() { if (enabled) { restart(); handler.postDelayed(this, 45_000) } } }
    private val prune = object : Runnable { override fun run() {
        if (!enabled) return
        val cutoff = SystemClock.elapsedRealtime() - STALE_AFTER_MS
        val staleIds = lastSeen.filterValues { it < cutoff }.keys
        if (staleIds.isNotEmpty()) {
            staleIds.forEach(lastSeen::remove)
            foundNames.entries.removeAll { it.value in staleIds }
            mutable.value = mutable.value.filterNot { it.serverId in staleIds }
        }
        handler.postDelayed(this, PRUNE_INTERVAL_MS)
    } }
    fun start() { handler.post {
        if (enabled) return@post
        enabled = true
        fallback.start()
        lock = (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.createMulticastLock("navelo-discovery")?.apply { setReferenceCounted(false); acquire() }
        runCatching { connectivity.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback) }
        restart(); handler.postDelayed(refresh, 45_000); handler.postDelayed(prune, PRUNE_INTERVAL_MS)
    } }
    fun stop() { handler.post {
        enabled = false; fallback.stop(); generation++; handler.removeCallbacks(refresh); handler.removeCallbacks(prune)
        runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }; listener = null
        pending.clear(); foundNames.clear(); lastSeen.clear(); mutable.value = emptyList()
        activeResolveToken++; resolving = false; activeServiceName = null
        lock?.let { if (it.isHeld) it.release() }; lock = null
    } }
    private fun restart() {
        if (!enabled) return
        generation++; val epoch = generation
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        pending.clear(); activeResolveToken++; resolving = false; activeServiceName = null
        val next = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) = Unit
            override fun onDiscoveryStopped(type: String) = Unit
            override fun onStartDiscoveryFailed(type: String, error: Int) { handler.postDelayed({ if (enabled && generation == epoch) restart() }, 5_000) }
            override fun onStopDiscoveryFailed(type: String, error: Int) = Unit
            override fun onServiceFound(info: NsdServiceInfo) { handler.post {
                val alreadyQueued = pending.any { it.second.serviceName == info.serviceName }
                if (enabled && generation == epoch && info.serviceType.contains("_navelo._tcp") &&
                    info.serviceName != activeServiceName && !alreadyQueued && pending.size < MAX_PENDING_RESOLVES
                ) {
                    pending.addLast(epoch to info)
                    resolveNext()
                }
            } }
            override fun onServiceLost(info: NsdServiceInfo) { handler.post {
                // Keep the entry briefly: the UDP hotspot path may still be refreshing the same server.
                if (generation == epoch) foundNames.remove(info.serviceName)
            } }
        }
        listener = next
        runCatching { nsd.discoverServices(Protocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, next) }
            .onFailure { handler.postDelayed({ if (enabled && generation == epoch) restart() }, 5_000) }
    }
    private fun resolveNext() {
        if (resolving || pending.isEmpty() || !enabled) return
        val (epoch, info) = pending.removeFirst()
        resolving = true
        activeServiceName = info.serviceName
        val token = ++activeResolveToken
        handler.postDelayed({ finishResolve(token) }, RESOLVE_TIMEOUT_MS)
        val callback = object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, error: Int) { handler.post { finishResolve(token) } }
            override fun onServiceResolved(service: NsdServiceInfo) { handler.post {
                if (token != activeResolveToken) return@post
                if (enabled && epoch == generation) {
                    val id = service.attributes["id"]?.toString(Charsets.UTF_8)
                    val api = service.attributes["api"]?.toString(Charsets.UTF_8)?.toIntOrNull()
                    val host = service.host?.hostAddress
                    if (id != null && LocalDiscovery.validId(id) && api == Protocol.VERSION && host != null && service.port in 1..65535) {
                        val name = LocalDiscovery.cleanName(
                            service.attributes["name"]?.toString(Charsets.UTF_8) ?: service.serviceName,
                        )
                        foundNames[service.serviceName] = id
                        record(DiscoveredServer(id, name, host, service.port))
                    }
                }
                finishResolve(token)
            } }
        }
        runCatching { nsd.resolveService(info, callback) }.onFailure { finishResolve(token) }
    }

    private fun finishResolve(token: Long) {
        if (token != activeResolveToken) return
        activeResolveToken++
        resolving = false
        activeServiceName = null
        resolveNext()
    }

    private fun record(server: DiscoveredServer) {
        lastSeen[server.serverId] = SystemClock.elapsedRealtime()
        var next = mutable.value.filterNot { it.serverId == server.serverId } + server
        if (next.size > MAX_DISCOVERED_SERVERS) {
            val evicted = next.minBy { lastSeen[it.serverId] ?: Long.MIN_VALUE }
            next = next.filterNot { it.serverId == evicted.serverId }
            lastSeen.remove(evicted.serverId)
            foundNames.entries.removeAll { it.value == evicted.serverId }
        }
        mutable.value = next.sortedBy { it.displayName }
    }

    private companion object {
        const val MAX_PENDING_RESOLVES = 128
        const val MAX_DISCOVERED_SERVERS = 64
        const val RESOLVE_TIMEOUT_MS = 8_000L
        const val PRUNE_INTERVAL_MS = 10_000L
        const val STALE_AFTER_MS = 90_000L
    }
}

class NaveloAdvertiser(context: Context) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(NsdManager::class.java)
    private val connectivity = app.getSystemService(ConnectivityManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val fallback = LocalAnnouncer()
    private var registration: NsdManager.RegistrationListener? = null
    private var server: Triple<String, String, Int>? = null
    private var registeredNetwork = false
    private var generation = 0
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { handler.post { register() } }
        override fun onLost(network: Network) { handler.post { register() } }
    }
    fun start(serverId: String, displayName: String, port: Int) { handler.post {
        server = Triple(serverId, displayName, port)
        fallback.start(serverId, displayName, port)
        if (!registeredNetwork) { registeredNetwork = runCatching { connectivity.registerNetworkCallback(NetworkRequest.Builder().build(), networkCallback); true }.getOrDefault(false) }
        register()
    } }
    fun stop() { handler.post {
        server = null; fallback.stop(); generation++
        registration?.let { runCatching { manager.unregisterService(it) } }; registration = null
        if (registeredNetwork) runCatching { connectivity.unregisterNetworkCallback(networkCallback) }
        registeredNetwork = false
    } }
    private fun register() {
        val current = server ?: return
        generation++; val epoch = generation
        registration?.let { runCatching { manager.unregisterService(it) } }
        val callback = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Unit
            override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
            override fun onUnregistrationFailed(info: NsdServiceInfo, error: Int) = Unit
            override fun onRegistrationFailed(info: NsdServiceInfo, error: Int) { handler.postDelayed({ if (generation == epoch) register() }, 5_000) }
        }
        registration = callback
        runCatching { manager.registerService(NsdServiceInfo().apply {
            serviceName = "Navelo ${current.first.take(8)}"; serviceType = Protocol.SERVICE_TYPE; port = current.third
            setAttribute("id", current.first); setAttribute("api", Protocol.VERSION.toString()); setAttribute("name", current.second.take(60))
        }, NsdManager.PROTOCOL_DNS_SD, callback) }.onFailure { handler.postDelayed({ if (generation == epoch) register() }, 5_000) }
    }
}
