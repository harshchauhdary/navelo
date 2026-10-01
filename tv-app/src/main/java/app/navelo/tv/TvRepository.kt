package app.navelo.tv

import android.content.Context
import android.os.Build
import android.util.Base64
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.navelo.shared.DiscoveredServer
import app.navelo.shared.FilenameParser
import app.navelo.shared.ItemType
import app.navelo.shared.ItemsResponse
import app.navelo.shared.LibraryResponse
import app.navelo.shared.MetadataMatchRequest
import app.navelo.shared.MetadataSearchResponse
import app.navelo.shared.MetadataSnapshot
import app.navelo.shared.MediaItem
import app.navelo.shared.NaveloDiscovery
import app.navelo.shared.PairRequest
import app.navelo.shared.PairTicket
import app.navelo.shared.PlaybackReport
import app.navelo.shared.Protocol
import app.navelo.shared.SecureStore
import app.navelo.shared.ServerInfo
import java.io.IOException
import java.security.SecureRandom
import java.text.Normalizer
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val Context.naveloTvLegacyStore by preferencesDataStore(name = "navelo-tv")
private val Context.naveloTvConnectionStore by preferencesDataStore(name = "navelo-tv-connection")
private val Context.naveloTvLibraryStore by preferencesDataStore(name = "navelo-tv-library")
private val Context.naveloTvUserStore by preferencesDataStore(name = "navelo-tv-user")
private val LEGACY_SNAPSHOT_KEY = stringPreferencesKey("snapshot-v1")
private val CONNECTION_KEY = stringPreferencesKey("connection-v2")
private val LIBRARY_KEY = stringPreferencesKey("library-v2")
private val USER_KEY = stringPreferencesKey("user-v2")
private const val PAGE_SIZE = 500
private const val PHONE_METADATA_CAPABILITY = "phone-metadata-v1"
private const val RESCAN_STATUS_CAPABILITY = "rescan-status-v1"

enum class SettingsAction { REFRESH_LIBRARY, RESCAN_LIBRARY, CLEAR_MATCHES }
enum class SettingsActionPhase { RUNNING, SUCCESS, ERROR }

data class SettingsActionFeedback(
    val phase: SettingsActionPhase,
    val message: String,
)

data class TvState(
    val items: List<MediaItem> = emptyList(),
    val metadata: Map<String, Metadata> = emptyMap(),
    val watch: Map<String, WatchProgress> = emptyMap(),
    val discovered: List<DiscoveredServer> = emptyList(),
    val paired: Boolean = false,
    val online: Boolean = false,
    val connecting: Boolean = false,
    val pairingPin: String? = null,
    val message: String? = null,
    val settings: TvSettings = TvSettings(),
    val phoneMetadataSupported: Boolean = false,
    val phoneMetadataCapabilityKnown: Boolean = false,
    val rescanStatusSupported: Boolean = false,
    val activeSettingsAction: SettingsAction? = null,
    val settingsActionFeedback: Map<SettingsAction, SettingsActionFeedback> = emptyMap(),
    val serverName: String = "",
)

@Serializable
internal data class StoredUser(
    val clientId: String = "",
    val watch: Map<String, WatchProgress> = emptyMap(),
    val settings: TvSettings = TvSettings(),
)

/**
 * TV-side source of truth. Public mutating methods are safe to call directly from Compose;
 * network and disk work is kept on the repository scope.
 */
class TvRepository(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .retryOnConnectionFailure(true)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()
    private val discovery = NaveloDiscovery(appContext)
    private val credentials = SecureStore(appContext, "navelo-tv-credentials")
    private val persistMutex = Mutex()
    private val connectionMutex = Mutex()
    private val metadataMutex = Mutex()
    private val started = AtomicBoolean(false)
    private val activeSettingsAction = java.util.concurrent.atomic.AtomicReference<SettingsAction?>(null)
    private val mutableState = MutableStateFlow(TvState())
    val state: StateFlow<TvState> = mutableState.asStateFlow()

    @Volatile private var selectedServer: DiscoveredServer? = null
    @Volatile private var activeCredential: Pair<String, String>? = null
    @Volatile private var cachedRevision: Long = 0
    @Volatile private var cachedMetadataRevision: Long = 0
    @Volatile private var metadataEndpointUnavailable = false
    @Volatile private var clientId: String = ""
    @Volatile private var reconnectFailures = 0
    @Volatile private var nextReconnectAt = 0L
    private var discoveryJob: Job? = null
    private var connectionJob: Job? = null
    private var metadataJob: Job? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        discovery.start()
        discoveryJob = scope.launch {
            discovery.servers.collect { servers ->
                val reachableAddresses = servers.filter(ConnectionPolicy::supportsHttp)
                mutableState.update { it.copy(discovered = reachableAddresses) }
                // NSD and UDP can report different valid routes to the same phone.
                // A discovery announcement must never replace a working playback route.
                val replacement = ConnectionPolicy.replacement(selectedServer, reachableAddresses, state.value.online)
                    ?: return@collect
                scope.launch { connectInternal(replacement, onlyIfOffline = true) }
            }
        }
        scope.launch { restoreAndReconnect() }
        connectionJob = scope.launch { connectionMonitor() }
        metadataJob = scope.launch { metadataMonitor() }
    }

    fun close() {
        if (!started.compareAndSet(true, false)) return
        discoveryJob?.cancel()
        connectionJob?.cancel()
        metadataJob?.cancel()
        discovery.stop()
        client.dispatcher.cancelAll()
        // saveProgress is asynchronous; make the final small user-state write durable before cancellation.
        runCatching { runBlocking(Dispatchers.IO) { persistUser() } }
        scope.cancel()
    }

    fun connect(server: DiscoveredServer) {
        scope.launch { connectInternal(server) }
    }

    fun refresh() {
        scope.launch {
            runCatching { refreshNow() }
                .onFailure(::showError)
        }
    }

    fun refreshLibrary() {
        if (!beginSettingsAction(SettingsAction.REFRESH_LIBRARY, "Refreshing from your phone…")) return
        scope.launch {
            runCatching {
                requireAuthorizedServer()
                refreshNow(force = true)
            }
                .onSuccess {
                    val videoCount = state.value.items.count { item -> item.type == ItemType.VIDEO }
                    finishSettingsAction(
                        SettingsAction.REFRESH_LIBRARY,
                        SettingsActionPhase.SUCCESS,
                        "Library is up to date · $videoCount ${if (videoCount == 1) "video" else "videos"}",
                    )
                }
                .onFailure { failure ->
                    showError(failure)
                    finishSettingsAction(
                        SettingsAction.REFRESH_LIBRARY,
                        SettingsActionPhase.ERROR,
                        settingsFailureMessage(failure, "Couldn’t refresh the library"),
                    )
                }
        }
    }

    /** Requests a phone-side SAF scan and, when supported, waits for its reserved generation. */
    fun rescan() {
        if (!beginSettingsAction(SettingsAction.RESCAN_LIBRARY, "Scanning media on your phone…")) return
        scope.launch {
            runCatching {
                val requestServer = selectedServer ?: throw IOException("no server")
                val response = decode<LibraryResponse>(
                    authorizedRequest(
                        "api/v1/rescan",
                        method = "POST",
                        expectedServerId = requestServer.serverId,
                    ),
                )
                val ticket = response.scanGeneration
                if (!state.value.rescanStatusSupported || ticket == null) {
                    refreshNow(force = true)
                    RescanCompletion.REQUESTED_ONLY
                } else {
                    awaitRescan(requestServer, ticket)
                    refreshNow(force = true)
                    RescanCompletion.CONFIRMED
                }
            }.onSuccess { completion ->
                val videoCount = state.value.items.count { item -> item.type == ItemType.VIDEO }
                val message = when (completion) {
                    RescanCompletion.CONFIRMED ->
                        "Scan complete · $videoCount ${if (videoCount == 1) "video" else "videos"}"
                    RescanCompletion.REQUESTED_ONLY ->
                        "Scan requested on your phone. Use Refresh library after it finishes."
                }
                finishSettingsAction(SettingsAction.RESCAN_LIBRARY, SettingsActionPhase.SUCCESS, message)
            }.onFailure { failure ->
                if (failure !is ScanFailed && failure !is ScanTimedOut && failure !is ScanRestarted) {
                    showError(failure)
                }
                finishSettingsAction(
                    SettingsAction.RESCAN_LIBRARY,
                    SettingsActionPhase.ERROR,
                    settingsFailureMessage(failure, "Couldn’t scan the library"),
                )
            }
        }
    }

    fun switchServer() {
        selectedServer = null
        activeCredential = null
        cachedRevision = 0
        cachedMetadataRevision = 0
        metadataEndpointUnavailable = false
        activeSettingsAction.set(null)
        reconnectFailures = 0
        nextReconnectAt = 0
        mutableState.update {
            it.copy(
                items = emptyList(),
                metadata = emptyMap(),
                watch = emptyMap(),
                paired = false,
                online = false,
                connecting = false,
                pairingPin = null,
                message = null,
                settings = it.settings.copy(tmdbConfigured = false),
                phoneMetadataSupported = false,
                phoneMetadataCapabilityKnown = false,
                rescanStatusSupported = false,
                activeSettingsAction = null,
                settingsActionFeedback = emptyMap(),
                serverName = "",
            )
        }
        scope.launch { persistAll() }
    }

    suspend fun searchMetadata(item: MediaItem, query: String): List<Metadata> = withContext(Dispatchers.IO) {
        check(state.value.phoneMetadataSupported) { "Update Navelo on the phone to search for matches" }
        check(state.value.settings.tmdbConfigured) { "Movie and show information is unavailable from the phone" }
        val requestServerId = activeServerId() ?: throw IOException("no server")
        decode<MetadataSearchResponse>(
            authorizedRequest(
                path = "api/v1/metadata/search",
                query = listOf("itemId" to item.id, "q" to query.trim()),
                expectedServerId = requestServerId,
            ),
        ).results.map { it.localArtworkOnly() }
    }

    fun fixMatch(item: MediaItem, metadata: Metadata) {
        scope.launch {
            val requestServerId = activeServerId()
                ?: return@launch mutableState.update { it.copy(message = "Connect to the phone and try again") }
            val request = MetadataMatchRequest(item.id, metadata.id, metadata.mediaType)
            runCatching {
                metadataMutex.withLock {
                    val snapshot = decode<MetadataSnapshot>(
                        authorizedRequest(
                            "api/v1/metadata/match",
                            method = "POST",
                            body = Protocol.json.encodeToString(request).toRequestBody(JSON_MEDIA_TYPE),
                            expectedServerId = requestServerId,
                        ),
                    )
                    applyMetadataSnapshot(snapshot, requestServerId)
                }
            }.onSuccess { applied ->
                if (applied) {
                    mutableState.update { it.copy(message = "Match saved. Details and artwork may take a moment to appear") }
                }
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                mutableState.update { it.copy(message = "Couldn’t save that match. Check the phone and try again") }
            }
        }
    }

    fun clearMetadata() {
        if (!beginSettingsAction(SettingsAction.CLEAR_MATCHES, "Clearing matches on your phone…")) return
        scope.launch {
            val requestServerId = activeServerId()
                ?: return@launch finishSettingsAction(
                    SettingsAction.CLEAR_MATCHES,
                    SettingsActionPhase.ERROR,
                    "Connect to your phone and try again.",
                )
            runCatching {
                metadataMutex.withLock {
                    val snapshot = decode<MetadataSnapshot>(
                        authorizedRequest(
                            "api/v1/metadata/clear",
                            method = "POST",
                            expectedServerId = requestServerId,
                        ),
                    )
                    check(applyMetadataSnapshot(snapshot, requestServerId)) { "server changed" }
                }
            }.onSuccess {
                finishSettingsAction(
                    SettingsAction.CLEAR_MATCHES,
                    SettingsActionPhase.SUCCESS,
                    "Matches cleared on your phone. New matches will appear as it scans the library.",
                )
            }.onFailure { failure ->
                if (failure is CancellationException) throw failure
                finishSettingsAction(
                    SettingsAction.CLEAR_MATCHES,
                    SettingsActionPhase.ERROR,
                    settingsFailureMessage(failure, "Couldn’t clear matches on the phone"),
                )
            }
        }
    }

    fun updateSettings(settings: TvSettings) {
        mutableState.update { it.copy(settings = settings.copy(tmdbConfigured = it.settings.tmdbConfigured)) }
        scope.launch { persistUser() }
    }

    fun mediaUrl(itemId: String): String? {
        val server = selectedServer ?: return null
        return ConnectionPolicy.mediaUrl(server, itemId)
    }

    fun activeServerId(): String? = selectedServer?.serverId

    fun thumbnailUrl(itemId: String): String? = selectedServer?.baseUrl?.toHttpUrlOrNull()
        ?.newBuilder()?.addPathSegments("api/v1/thumbnail")?.addPathSegment(itemId)?.build()?.toString()

    fun artworkUrl(reference: String?): String? = selectedServer?.let { ArtworkPolicy.artworkUrl(it, reference) }

    fun authToken(): String? {
        val serverId = selectedServer?.serverId ?: return null
        return activeCredential?.takeIf { it.first == serverId }?.second
    }

    private fun requireAuthorizedServer(): DiscoveredServer {
        val server = selectedServer ?: throw IOException("no server")
        if (activeCredential?.takeIf { it.first == server.serverId }?.second.isNullOrBlank()) {
            throw RemoteFailure(401)
        }
        return server
    }

    fun saveProgress(itemId: String, positionMs: Long, durationMs: Long) {
        val progress = WatchPolicy.create(positionMs, durationMs, System.currentTimeMillis())
        mutableState.update { it.copy(watch = it.watch + (itemId to progress)) }
        scope.launch { persistUser() }
    }

    fun reportPlayback(itemId: String, positionMs: Long, durationMs: Long, playing: Boolean) {
        scope.launch {
            val body = Protocol.json.encodeToString(PlaybackReport(itemId, positionMs, durationMs, playing))
                .toRequestBody(JSON_MEDIA_TYPE)
            runCatching { authorizedRequest("api/v1/playback", method = "POST", body = body) }
        }
    }

    /** Episode ordering is numeric and restricted to the same parsed show. */
    fun nextEpisode(item: MediaItem): MediaItem? {
        val current = episodeIdentity(item) ?: return null
        return state.value.items.asSequence()
            .filter { it.type == ItemType.VIDEO && it.id != item.id }
            .mapNotNull { candidate -> episodeIdentity(candidate)?.let { it to candidate } }
            .filter { (identity, _) -> identity.showKey == current.showKey }
            .filter { (identity, _) -> identity.season > current.season || identity.season == current.season && identity.episode > current.episode }
            .minWithOrNull(compareBy<Pair<EpisodeIdentity, MediaItem>>({ it.first.season }, { it.first.episode }))
            ?.second
    }

    private suspend fun restoreAndReconnect() {
        val (connectionPreferences, libraryPreferences, userPreferences) = coroutineScope {
            listOf(
                async { runCatching { appContext.naveloTvConnectionStore.data.first() }.getOrNull() },
                async { runCatching { appContext.naveloTvLibraryStore.data.first() }.getOrNull() },
                async { runCatching { appContext.naveloTvUserStore.data.first() }.getOrNull() },
            ).awaitAll()
        }
        val legacyPreferences = if (connectionPreferences?.get(CONNECTION_KEY) == null) {
            runCatching { appContext.naveloTvLegacyStore.data.first() }.getOrNull()
        } else null
        val legacy = legacyPreferences?.get(LEGACY_SNAPSHOT_KEY)?.let {
            runCatching { Protocol.json.decodeFromString<StoredSnapshot>(it) }.getOrNull()
        }
        val connection = connectionPreferences?.get(CONNECTION_KEY)?.let {
            runCatching { Protocol.json.decodeFromString<StoredConnection>(it) }.getOrNull()
        } ?: legacy?.let { StoredConnection(it.serverId, it.serverName, it.serverHost, it.serverPort) }
        val library = libraryPreferences?.get(LIBRARY_KEY)?.let {
            runCatching { Protocol.json.decodeFromString<StoredLibrary>(it) }.getOrNull()
        } ?: legacy?.let { StoredLibrary(it.revision, it.items, it.metadata) }
        val user = userPreferences?.get(USER_KEY)?.let {
            runCatching { Protocol.json.decodeFromString<StoredUser>(it) }.getOrNull()
        } ?: legacy?.let { StoredUser(it.clientId, it.watch, it.settings) }

        clientId = user?.clientId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        cachedRevision = library?.revision ?: 0
        cachedMetadataRevision = library?.metadataRevision ?: 0
        mutableState.update { it.copy(settings = (user?.settings ?: TvSettings()).copy(tmdbConfigured = false)) }
        if (connection != null) {
            selectedServer = if (
                connection.serverId.isNotBlank() && connection.serverHost.isNotBlank() && connection.serverPort > 0
            ) DiscoveredServer(connection.serverId, connection.serverName, connection.serverHost, connection.serverPort) else null
            activeCredential = selectedServer?.serverId?.let { id ->
                credentials.get(tokenKey(id))?.takeIf(String::isNotBlank)?.let { id to it }
            }
            val paired = activeCredential != null
            mutableState.update {
                it.copy(
                    items = library?.items.orEmpty(),
                    metadata = library?.metadata.orEmpty().mapValues { it.value.localArtworkOnly() },
                    watch = user?.watch.orEmpty(),
                    serverName = connection.serverName,
                    paired = paired,
                    online = false,
                )
            }
        } else {
            persistAll()
        }

        val cached = selectedServer ?: return
        val currentAddress = discovery.servers.value.firstOrNull {
            it.serverId == cached.serverId && ConnectionPolicy.supportsHttp(it)
        } ?: cached
        connectInternal(currentAddress)
    }

    private suspend fun connectionMonitor() {
        delay(2_000)
        while (started.get()) {
            val server = selectedServer
            if (server != null && !state.value.connecting && System.currentTimeMillis() >= nextReconnectAt) {
                if (state.value.online && state.value.paired) {
                    try {
                        val info = publicServerInfo(server)
                        if (info.serverId != server.serverId) throw IllegalArgumentException("identity")
                        updateServerCapabilities(info)
                        if (state.value.serverName != info.displayName) {
                            selectedServer = server.copy(displayName = info.displayName)
                            mutableState.update { it.copy(serverName = info.displayName) }
                            persistConnection()
                        }
                        if (info.libraryRevision != cachedRevision) refreshNow()
                        reconnectFailures = 0
                        nextReconnectAt = System.currentTimeMillis() + 30_000
                    } catch (failure: Throwable) {
                        if (failure is CancellationException) throw failure
                        reconnectFailures++
                        // One delayed health response must not interrupt a healthy stream
                        // or flash the offline banner. Retry before declaring the phone lost.
                        if (reconnectFailures >= 2) showError(failure)
                        nextReconnectAt = System.currentTimeMillis() + ReconnectBackoff.delayMs(reconnectFailures)
                    }
                } else {
                    val replacement = ConnectionPolicy.replacement(server, discovery.servers.value, online = false)
                    connectInternal(replacement ?: server, onlyIfOffline = true)
                }
            }
            delay(1_000)
        }
    }

    private suspend fun metadataMonitor() {
        delay(1_000)
        var previousRevision = cachedMetadataRevision
        while (started.get()) {
            var changed = false
            if (state.value.online && state.value.paired && state.value.phoneMetadataSupported) {
                changed = refreshMetadataSafely()
            }
            val current = state.value
            val hasUnmatchedVideos = current.settings.tmdbConfigured &&
                current.items.any { it.type == ItemType.VIDEO && it.id !in current.metadata }
            val delayMs = MetadataPollingPolicy.delayMs(
                metadataEmpty = current.metadata.isEmpty() && current.items.any { it.type == ItemType.VIDEO },
                matchingActive = hasUnmatchedVideos || changed || cachedMetadataRevision != previousRevision,
            )
            previousRevision = cachedMetadataRevision
            delay(delayMs)
        }
    }

    private suspend fun connectInternal(server: DiscoveredServer, onlyIfOffline: Boolean = false) {
        connectionMutex.withLock {
            if (!started.get() || state.value.connecting || (onlyIfOffline && state.value.online)) return
            mutableState.update {
                it.copy(connecting = true, online = false, pairingPin = null, message = "Connecting to ${server.displayName}…")
            }
            try {
            val info = publicServerInfo(server)
            require(info.apiVersion == Protocol.VERSION) { "incompatible" }
            require(info.serverId == server.serverId) { "identity" }

            val previousId = selectedServer?.serverId
            if (previousId != null && previousId != server.serverId) {
                cachedRevision = 0
                cachedMetadataRevision = 0
                metadataEndpointUnavailable = false
                mutableState.update { it.copy(items = emptyList(), metadata = emptyMap(), watch = emptyMap()) }
            }
            updateServerCapabilities(info)
            selectedServer = server.copy(displayName = info.displayName)
            mutableState.update { it.copy(serverName = info.displayName, online = true) }
            persistConnection()

            val existingToken = credentials.get(tokenKey(server.serverId))
            activeCredential = existingToken?.takeIf(String::isNotBlank)?.let { server.serverId to it }
            if (existingToken.isNullOrBlank()) {
                pair(server)
            } else {
                mutableState.update { it.copy(paired = true, pairingPin = null) }
                try {
                    refreshNow()
                } catch (error: RemoteFailure) {
                    if (error.code != 401 && error.code != 403) throw error
                    credentials.put(tokenKey(server.serverId), null)
                    activeCredential = null
                    mutableState.update { it.copy(paired = false) }
                    pair(server)
                }
            }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                showError(error)
            } finally {
                mutableState.update { it.copy(connecting = false) }
                if (state.value.online) {
                    reconnectFailures = 0
                    nextReconnectAt = System.currentTimeMillis() + 30_000
                } else {
                    reconnectFailures++
                    nextReconnectAt = System.currentTimeMillis() + ReconnectBackoff.delayMs(reconnectFailures)
                }
            }
        }
    }

    private suspend fun pair(server: DiscoveredServer) {
        val secret = randomSecret()
        val requestBody = Protocol.json.encodeToString(
            PairRequest(clientId = clientId, displayName = deviceName(), clientSecret = secret),
        ).toRequestBody(JSON_MEDIA_TYPE)
        var ticket = executeJson<PairTicket>(
            Request.Builder().url(serverUrl(server, "api/v1/pair")).post(requestBody).build(),
        )
        val deadline = System.currentTimeMillis() + 5 * 60_000
        while (ticket.status.lowercase(Locale.ROOT) == "pending" && System.currentTimeMillis() < deadline) {
            mutableState.update {
                it.copy(
                    online = true,
                    paired = false,
                    pairingPin = ticket.pin.takeIf(String::isNotBlank),
                    message = "Approve this TV on the phone",
                )
            }
            delay(2_000)
            val poll = Request.Builder()
                .url(serverUrl(server, "api/v1/pair/${ticket.requestId}"))
                .header("X-Pair-Secret", secret)
                .get()
                .build()
            ticket = executeJson(poll)
        }
        when (ticket.status.lowercase(Locale.ROOT)) {
            "approved" -> {
                val token = ticket.token?.takeIf(String::isNotBlank) ?: throw IOException("missing token")
                if (ticket.serverId != null && ticket.serverId != server.serverId) throw IOException("server identity changed")
                credentials.put(tokenKey(server.serverId), token)
                activeCredential = server.serverId to token
                mutableState.update { it.copy(paired = true, online = true, pairingPin = null, message = "Paired with ${server.displayName}") }
                persistConnection()
                refreshNow(force = true)
            }
            "denied" -> throw PairingFailure("denied")
            "expired", "pending" -> throw PairingFailure("expired")
            else -> throw PairingFailure("failed")
        }
    }

    private suspend fun refreshNow(force: Boolean = false) {
        val server = selectedServer ?: return
        if (authToken().isNullOrBlank()) {
            mutableState.update { it.copy(paired = false, online = true, message = "Pair this TV to browse media") }
            return
        }
        val library = decode<LibraryResponse>(authorizedRequest("api/v1/library"))
        if (!force && library.revision == cachedRevision) {
            mutableState.update { it.copy(paired = true, online = true, message = null) }
            refreshMetadataSafely()
            return
        }

        val fetched = fetchStableSnapshot()
        cachedRevision = fetched.revision
        mutableState.update {
            it.copy(items = fetched.items, paired = true, online = true, message = null, serverName = server.displayName)
        }
        persistLibrary()
        refreshMetadataSafely()
    }

    private suspend fun fetchStableSnapshot(): ItemsResponse {
        var lastFailure: Throwable? = null
        repeat(3) {
            try {
                val all = ArrayList<MediaItem>()
                var offset = 0
                var revision: Long? = null
                while (true) {
                    val path = buildString {
                        append("api/v1/items?offset=").append(offset).append("&limit=").append(PAGE_SIZE)
                        // Offset zero deliberately has no revision: passing current means "unchanged".
                        if (offset > 0) append("&revision=").append(revision)
                    }
                    val page = decode<ItemsResponse>(authorizedRequest(path))
                    if (page.unchanged && offset == 0) return ItemsResponse(cachedRevision, state.value.items, unchanged = true)
                    if (revision == null) revision = page.revision
                    if (page.revision != revision) throw RevisionChanged()
                    all += page.items
                    val next = page.nextOffset ?: break
                    if (next <= offset) throw IOException("invalid pagination")
                    offset = next
                }
                return ItemsResponse(revision ?: 0, all)
            } catch (failure: Throwable) {
                if (failure is CancellationException) throw failure
                lastFailure = failure
                if (failure !is RevisionChanged && (failure !is RemoteFailure || failure.code != 409)) throw failure
            }
        }
        throw lastFailure ?: IOException("library kept changing")
    }

    private fun updateServerCapabilities(info: ServerInfo) {
        val supported = PHONE_METADATA_CAPABILITY in info.capabilities && !metadataEndpointUnavailable
        mutableState.update {
            it.copy(
                phoneMetadataSupported = supported,
                phoneMetadataCapabilityKnown = true,
                rescanStatusSupported = RESCAN_STATUS_CAPABILITY in info.capabilities,
                settings = if (supported) it.settings else it.settings.copy(tmdbConfigured = false),
            )
        }
    }

    private suspend fun awaitRescan(server: DiscoveredServer, ticket: Long) {
        val deadline = System.currentTimeMillis() + RESCAN_WAIT_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val info = publicServerInfo(server)
            if (info.serverId != server.serverId || activeServerId() != server.serverId) {
                throw IOException("server changed")
            }
            updateServerCapabilities(info)
            when (RescanStatusPolicy.evaluate(
                ticket = ticket,
                scanGeneration = info.scanGeneration,
                completedScanGeneration = info.completedScanGeneration,
                scanError = info.scanError,
            )) {
                RescanStatus.PENDING -> delay(RESCAN_POLL_DELAY_MS)
                RescanStatus.COMPLETE -> return
                RescanStatus.FAILED -> throw ScanFailed(info.scanError.orEmpty())
                RescanStatus.RESTARTED -> throw ScanRestarted()
            }
        }
        throw ScanTimedOut()
    }

    private fun beginSettingsAction(action: SettingsAction, message: String): Boolean {
        if (!activeSettingsAction.compareAndSet(null, action)) return false
        mutableState.update {
            it.copy(
                activeSettingsAction = action,
                settingsActionFeedback = mapOf(
                    action to SettingsActionFeedback(SettingsActionPhase.RUNNING, message),
                ),
            )
        }
        return true
    }

    private fun finishSettingsAction(
        action: SettingsAction,
        phase: SettingsActionPhase,
        message: String,
    ) {
        if (!activeSettingsAction.compareAndSet(action, null)) return
        mutableState.update {
            it.copy(
                activeSettingsAction = null,
                settingsActionFeedback = mapOf(action to SettingsActionFeedback(phase, message)),
            )
        }
    }

    private fun settingsFailureMessage(failure: Throwable, fallback: String): String = when (failure) {
        is ScanFailed -> failure.message?.takeIf(String::isNotBlank)
            ?: "The phone couldn’t finish scanning. Check its media folders and try again."
        is ScanTimedOut -> "The phone is still scanning. Try Refresh library in a moment."
        is ScanRestarted -> "The phone restarted during the scan. Start the scan again."
        is RemoteFailure -> when (failure.code) {
            401, 403 -> "This TV needs to pair with the phone again."
            429 -> "The phone is busy. Wait a moment and try again."
            in 500..599 -> "$fallback. Check the phone and try again."
            else -> "$fallback. Try again."
        }
        is IOException -> "$fallback. Make sure the phone is sharing and try again."
        else -> "$fallback. Try again."
    }

    /** Metadata failure is isolated from media availability and playback state. */
    private suspend fun refreshMetadataSafely(): Boolean = metadataMutex.withLock {
        if (!state.value.phoneMetadataSupported) return@withLock false
        val requestServerId = activeServerId() ?: return@withLock false
        val before = cachedMetadataRevision
        return@withLock try {
            val snapshot = decode<MetadataSnapshot>(
                authorizedRequest("api/v1/metadata", expectedServerId = requestServerId),
            )
            applyMetadataSnapshot(snapshot, requestServerId) && snapshot.revision != before
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            if (failure is RemoteFailure && failure.code == 404 && activeServerId() == requestServerId) {
                // A mismatched/older phone advertised no usable endpoint. Disable polling for
                // this connection so a 404 cannot become an endless retry loop.
                metadataEndpointUnavailable = true
                mutableState.update {
                    it.copy(
                        phoneMetadataSupported = false,
                        settings = it.settings.copy(tmdbConfigured = false),
                    )
                }
            }
            false
        }
    }

    private suspend fun applyMetadataSnapshot(snapshot: MetadataSnapshot, expectedServerId: String): Boolean {
        if (!MetadataSnapshotPolicy.shouldApply(
                activeServerId = activeServerId(),
                expectedServerId = expectedServerId,
                currentRevision = cachedMetadataRevision,
                responseRevision = snapshot.revision,
            )
        ) return false
        val sanitized = snapshot.items.mapValues { entry -> entry.value.localArtworkOnly() }
        val current = state.value
        val shouldPersist = MetadataSnapshotPolicy.shouldPersist(
            currentRevision = cachedMetadataRevision,
            responseRevision = snapshot.revision,
            currentItems = current.metadata,
            responseItems = sanitized,
        )
        cachedMetadataRevision = snapshot.revision
        if (shouldPersist || current.settings.tmdbConfigured != snapshot.configured) {
            mutableState.update {
                it.copy(
                    metadata = if (shouldPersist) sanitized else it.metadata,
                    settings = it.settings.copy(tmdbConfigured = snapshot.configured),
                )
            }
        }
        if (shouldPersist) persistLibrary()
        return true
    }

    private fun publicServerInfo(server: DiscoveredServer): ServerInfo {
        val request = Request.Builder().url(serverUrl(server, "api/v1/server")).get().build()
        return executeJson(request)
    }

    private fun authorizedRequest(
        path: String,
        method: String = "GET",
        body: okhttp3.RequestBody? = null,
        query: List<Pair<String, String>> = emptyList(),
        expectedServerId: String? = null,
    ): String {
        val server = selectedServer
            ?.takeIf { expectedServerId == null || it.serverId == expectedServerId }
            ?: throw IOException("server changed")
        val token = activeCredential
            ?.takeIf { it.first == server.serverId }
            ?.second
            ?: throw RemoteFailure(401)
        val builder = Request.Builder()
            .url(serverUrl(server, path, query))
            .header("Authorization", "Bearer $token")
        when (method) {
            "POST" -> builder.post(body ?: EMPTY_BODY)
            "HEAD" -> builder.head()
            else -> builder.get()
        }
        return executeText(builder.build())
    }

    private fun executeText(request: Request): String = client.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw RemoteFailure(response.code)
        response.body?.string().orEmpty()
    }

    private inline fun <reified T> executeJson(request: Request): T = decode(executeText(request))

    private inline fun <reified T> decode(value: String): T = Protocol.json.decodeFromString(value)

    private fun serverUrl(
        server: DiscoveredServer,
        path: String,
        query: List<Pair<String, String>> = emptyList(),
    ): String =
        server.baseUrl.toHttpUrl().newBuilder().apply {
            path.substringBefore('?').split('/').filter(String::isNotBlank).forEach(::addPathSegment)
            path.substringAfter('?', "").takeIf(String::isNotBlank)?.split('&')?.forEach { part ->
                addQueryParameter(part.substringBefore('='), part.substringAfter('=', ""))
            }
            query.forEach { (name, value) -> addQueryParameter(name, value) }
        }.build().toString()

    private suspend fun persistConnection() = persistMutex.withLock {
        val server = selectedServer
        val value = state.value
        val stored = StoredConnection(
            serverId = server?.serverId.orEmpty(),
            serverName = value.serverName.ifBlank { server?.displayName.orEmpty() },
            serverHost = server?.host.orEmpty(),
            serverPort = server?.port ?: 0,
        )
        appContext.naveloTvConnectionStore.edit { it[CONNECTION_KEY] = Protocol.json.encodeToString(stored) }
    }

    private suspend fun persistLibrary() = persistMutex.withLock {
        val value = state.value
        val stored = StoredLibrary(cachedRevision, value.items, value.metadata, cachedMetadataRevision)
        appContext.naveloTvLibraryStore.edit { it[LIBRARY_KEY] = Protocol.json.encodeToString(stored) }
    }

    private suspend fun persistUser() = persistMutex.withLock {
        val value = state.value
        val stored = StoredUser(
            clientId = clientId.ifBlank { UUID.randomUUID().toString().also { clientId = it } },
            watch = value.watch,
            settings = value.settings,
        )
        appContext.naveloTvUserStore.edit { it[USER_KEY] = Protocol.json.encodeToString(stored) }
    }

    private suspend fun persistAll() {
        persistConnection()
        persistLibrary()
        persistUser()
    }

    private fun showError(error: Throwable) {
        val message = when (error) {
            is PairingFailure -> when (error.reason) {
                "denied" -> "Pairing was denied on the phone"
                "expired" -> "Pairing expired. Select the phone again to retry"
                else -> "Couldn’t pair with the phone"
            }
            is RemoteFailure -> when (error.code) {
                401, 403 -> "This TV is no longer authorized. Pair it again"
                404 -> "The phone could not find that media"
                409 -> "The library changed while loading. Refresh to try again"
                429 -> "The phone is busy. Try again in a moment"
                in 500..599 -> "The phone couldn’t read its media right now"
                else -> "Couldn’t connect to the phone"
            }
            is IllegalArgumentException -> when (error.message) {
                "incompatible" -> "This phone uses an incompatible Navelo version"
                "identity" -> "The phone identity did not match the discovered server"
                else -> "Couldn’t connect to the phone"
            }
            is IOException -> "Phone is offline"
            else -> "Something went wrong. Try again"
        }
        mutableState.update {
            it.copy(
                online = false,
                paired = if (error is RemoteFailure && error.code in listOf(401, 403)) false else it.paired,
                connecting = false,
                pairingPin = null,
                message = message,
            )
        }
    }

    private fun episodeIdentity(item: MediaItem): EpisodeIdentity? {
        val parsed = FilenameParser.parse(item.filename, item.relativePath)
        val season = item.season ?: parsed.season ?: return null
        val episode = item.episode ?: parsed.episode ?: return null
        val show = item.showHint ?: parsed.show ?: return null
        return EpisodeIdentity(TitleNormalizer.normalize(show), season, episode)
    }

    private fun deviceName(): String {
        val model = Build.MODEL?.trim().orEmpty()
        return if (model.isBlank()) "Navelo TV" else "Navelo on $model"
    }

    private fun randomSecret(): String = ByteArray(32).also(SecureRandom()::nextBytes).let {
        Base64.encodeToString(it, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }

    private fun tokenKey(serverId: String) = "server-token:$serverId"

    private data class EpisodeIdentity(val showKey: String, val season: Int, val episode: Int)

    @Serializable
    private data class StoredConnection(
        val serverId: String = "",
        val serverName: String = "",
        val serverHost: String = "",
        val serverPort: Int = 0,
    )

    @Serializable
    private data class StoredLibrary(
        val revision: Long = 0,
        val items: List<MediaItem> = emptyList(),
        val metadata: Map<String, Metadata> = emptyMap(),
        val metadataRevision: Long = 0,
    )

    /** v1 migration only. New writes are deliberately split across three stores. */
    @Serializable
    private data class StoredSnapshot(
        val clientId: String = "",
        val serverId: String = "",
        val serverName: String = "",
        val serverHost: String = "",
        val serverPort: Int = 0,
        val revision: Long = 0,
        val items: List<MediaItem> = emptyList(),
        val metadata: Map<String, Metadata> = emptyMap(),
        val watch: Map<String, WatchProgress> = emptyMap(),
        val settings: TvSettings = TvSettings(),
    )

    private class RevisionChanged : IOException()
    private class RemoteFailure(val code: Int) : IOException()
    private class PairingFailure(val reason: String) : IOException()
    private class ScanFailed(message: String) : IOException(message)
    private class ScanTimedOut : IOException()
    private class ScanRestarted : IOException()

    private companion object {
        const val RESCAN_POLL_DELAY_MS = 1_000L
        const val RESCAN_WAIT_TIMEOUT_MS = 2 * 60_000L
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val EMPTY_BODY = ByteArray(0).toRequestBody(null)
    }
}

internal object ConnectionPolicy {
    // OkHttp cannot route RFC 4007 scoped IPv6 literals such as fe80::1%wlan0.
    // Keep the IPv4 fallback or an unscoped IPv6 route instead of stripping its
    // required interface identifier and accidentally reaching the wrong link.
    fun supportsHttp(server: DiscoveredServer): Boolean = server.baseUrl.toHttpUrlOrNull() != null

    fun mediaUrl(server: DiscoveredServer, itemId: String): String? = server.baseUrl.toHttpUrlOrNull()
        ?.newBuilder()?.addPathSegment("media")?.addPathSegment(itemId)?.build()?.toString()

    fun replacement(active: DiscoveredServer?, discovered: List<DiscoveredServer>, online: Boolean): DiscoveredServer? {
        if (online || active == null) return null
        return discovered.firstOrNull {
            it.serverId == active.serverId && supportsHttp(it) && (it.host != active.host || it.port != active.port)
        }
    }
}

internal object ReconnectBackoff {
    fun delayMs(failures: Int): Long {
        if (failures <= 0) return 0
        return (2_000L shl (failures - 1).coerceAtMost(4)).coerceAtMost(30_000L)
    }
}

internal object MetadataPollingPolicy {
    const val ACTIVE_DELAY_MS = 5_000L
    const val IDLE_DELAY_MS = 30_000L

    fun delayMs(metadataEmpty: Boolean, matchingActive: Boolean): Long =
        if (metadataEmpty || matchingActive) ACTIVE_DELAY_MS else IDLE_DELAY_MS
}

internal object MetadataSnapshotPolicy {
    fun shouldApply(
        activeServerId: String?,
        expectedServerId: String,
        currentRevision: Long,
        responseRevision: Long,
    ): Boolean = activeServerId == expectedServerId && responseRevision >= currentRevision

    fun shouldPersist(
        currentRevision: Long,
        responseRevision: Long,
        currentItems: Map<String, Metadata>,
        responseItems: Map<String, Metadata>,
    ): Boolean = responseRevision != currentRevision || responseItems != currentItems
}

internal enum class RescanStatus { PENDING, COMPLETE, FAILED, RESTARTED }

internal object RescanStatusPolicy {
    fun evaluate(
        ticket: Long,
        scanGeneration: Long,
        completedScanGeneration: Long,
        scanError: String?,
    ): RescanStatus = when {
        scanGeneration < ticket -> RescanStatus.RESTARTED
        completedScanGeneration < ticket -> RescanStatus.PENDING
        !scanError.isNullOrBlank() -> RescanStatus.FAILED
        else -> RescanStatus.COMPLETE
    }
}

private enum class RescanCompletion { CONFIRMED, REQUESTED_ONLY }

internal object WatchPolicy {
    fun create(positionMs: Long, durationMs: Long, now: Long): WatchProgress {
        val duration = durationMs.coerceAtLeast(0)
        val position = positionMs.coerceIn(0, if (duration > 0) duration else Long.MAX_VALUE)
        val watched = duration >= 60_000 && position >= 60_000 && position.toDouble() / duration >= 0.9
        return WatchProgress(position, duration, now, watched)
    }
}

internal object TitleNormalizer {
    fun normalize(value: String): String {
        val decomposed = Normalizer.normalize(value.lowercase(Locale.ROOT).replace("&", " and "), Normalizer.Form.NFD)
        val folded = StringBuilder(decomposed.length)
        var previousBaseWasLatin = false
        var index = 0
        while (index < decomposed.length) {
            val codePoint = decomposed.codePointAt(index)
            val type = Character.getType(codePoint)
            val isMark = type == Character.NON_SPACING_MARK.toInt() ||
                type == Character.COMBINING_SPACING_MARK.toInt() ||
                type == Character.ENCLOSING_MARK.toInt()
            if (!isMark || !previousBaseWasLatin) folded.appendCodePoint(codePoint)
            if (!isMark) previousBaseWasLatin = Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.LATIN
            index += Character.charCount(codePoint)
        }
        return Normalizer.normalize(folded, Normalizer.Form.NFC)
            .replace(Regex("[^\\p{L}\\p{N}\\p{M}]+"), " ")
            .trim()
    }
}

private fun Metadata.localArtworkOnly(): Metadata = copy(
    posterUrl = posterUrl.takeIf { ArtworkPolicy.assetId(it) != null },
    backdropUrl = backdropUrl.takeIf { ArtworkPolicy.assetId(it) != null },
    seasonPosterUrl = seasonPosterUrl.takeIf { ArtworkPolicy.assetId(it) != null },
    episodeImageUrl = episodeImageUrl.takeIf { ArtworkPolicy.assetId(it) != null },
)
