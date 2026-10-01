package app.navelo.server

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import app.navelo.shared.ItemsResponse
import app.navelo.shared.LibraryResponse
import app.navelo.shared.MediaItem
import app.navelo.shared.MetadataMatchRequest
import app.navelo.shared.MetadataSearchResponse
import app.navelo.shared.MetadataSnapshot
import app.navelo.shared.PairRequest
import app.navelo.shared.PairTicket
import app.navelo.shared.PlaybackReport
import app.navelo.shared.Protocol
import app.navelo.shared.RootType
import app.navelo.shared.ServerInfo
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ServerRuntime private constructor(context: Context) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val persistence = ServerPersistence(appContext)
    private val library = MediaLibrary(appContext, persistence)
    private val metadata = PhoneMetadataOwner(
        metadataFile = java.io.File(appContext.filesDir, "navelo-metadata-v1.json"),
        artworkDir = java.io.File(appContext.cacheDir, "metadata-artwork-v1"),
        provider = TmdbMetadataProvider(BuildConfig.NAVELO_TMDB_READ_TOKEN),
        scope = scope,
    )
    private val scanRequests = ScanRequests()
    private val libraryChangeMutex = Mutex()
    private val initialized = CompletableDeferred<Unit>()
    private val notificationRefresh = AtomicReference<(() -> Unit)?>(null)
    private lateinit var pairing: PairingManager
    @Volatile private var lastAuthenticatedName: String? = null
    @Volatile private var lastAuthenticatedAt: Long = 0
    @Volatile private var lastPlaybackReportAt: Long = 0

    private val _state = MutableStateFlow(ServerState(port = Protocol.PORT))
    val state: StateFlow<ServerState> = _state.asStateFlow()

    init {
        scope.launch {
            runCatching {
                val serverId = persistence.loadOrCreateServerId()
                metadata.restore()
                val restored = library.restore()
                metadata.onLibraryChanged(restored.items.map { it.item })
                pairing = PairingManager(persistence.loadTrusted())
                _state.update {
                    it.copy(
                        roots = restored.roots.map(StoredRoot::publicValue),
                        devices = pairing.trustedRecords().map(TrustedRecord::publicValue),
                        pending = pairing.pending(),
                        serverId = serverId,
                    )
                }
            }.onFailure { failure ->
                pairing = PairingManager(emptyList())
                _state.update { it.copy(serverId = UUID.randomUUID().toString(), message = userMessage(failure)) }
            }
            initialized.complete(Unit)
        }
    }

    fun start() {
        ContextCompat.startForegroundService(
            appContext,
            Intent(appContext, MediaServerService::class.java).setAction(MediaServerService.ACTION_START),
        )
    }

    fun stop() {
        appContext.stopService(Intent(appContext, MediaServerService::class.java))
    }

    suspend fun addRoot(uri: Uri, type: RootType) {
        runLibraryChange { library.addRoot(uri, type) }
    }

    suspend fun removeRoot(id: String) {
        runLibraryChange { library.removeRoot(id) }
    }

    internal fun requestRescan(): Long {
        val request = scanRequests.reserve()
        if (request.start) scope.launch {
            var error: String? = "The phone could not finish scanning. Please try again."
            try {
                val result = runLibraryChange { library.rescan() }
                error = if (result.isSuccess) null else
                    "The phone could not finish scanning. Check folder access and try again."
            } finally {
                scanRequests.complete(request.generation, error)
            }
        }
        return request.generation
    }

    suspend fun rescan() {
        runLibraryChange { library.rescan() }
    }

    suspend fun approve(requestId: String) {
        initialized.await()
        if (pairing.approve(requestId)) {
            persistence.saveTrusted(pairing.trustedRecords())
            publishPairing()
            notificationRefresh.get()?.invoke()
        }
    }

    suspend fun deny(requestId: String) {
        initialized.await()
        if (pairing.deny(requestId)) {
            publishPairing()
            notificationRefresh.get()?.invoke()
        }
    }

    suspend fun revoke(clientId: String) {
        initialized.await()
        if (pairing.revoke(clientId)) {
            persistence.saveTrusted(pairing.trustedRecords())
            publishPairing()
            notificationRefresh.get()?.invoke()
        }
    }

    internal suspend fun awaitInitialized() = initialized.await()

    internal suspend fun serverInfo(): ServerInfo {
        initialized.await()
        val snapshot = library.current()
        val scan = scanRequests.status()
        return ServerInfo(
            serverId = state.value.serverId,
            displayName = DISPLAY_NAME,
            libraryRevision = snapshot.revision,
            scanGeneration = scan.generation,
            completedScanGeneration = scan.completed,
            scanError = scan.error,
        )
    }

    internal suspend fun libraryResponse(): LibraryResponse {
        initialized.await()
        val snapshot = library.current()
        return LibraryResponse(snapshot.revision, snapshot.roots.map(StoredRoot::publicValue))
    }

    internal suspend fun itemsResponse(
        offset: Int,
        limit: Int,
        requestedRevision: Long?,
        rootId: String?,
        parentId: String?,
    ): ItemsPageResult {
        initialized.await()
        val current = library.current()
        if (offset == 0 && requestedRevision == current.revision) {
            return ItemsPageResult.Success(ItemsResponse(current.revision, emptyList(), unchanged = true))
        }
        val revision = if (offset > 0 && requestedRevision != null) requestedRevision else current.revision
        val source = if (revision == current.revision) current.items else library.snapshot(revision)
            ?: return ItemsPageResult.Stale(current.revision)
        val filtered = source.asSequence()
            .map(StoredMediaItem::item)
            .filter { rootId == null || it.rootId == rootId }
            .filter { parentId == null || it.parentId == parentId }
            .toList()
        val page = filtered.drop(offset).take(limit)
        val next = (offset + page.size).takeIf { it < filtered.size }
        return ItemsPageResult.Success(ItemsResponse(revision, page, nextOffset = next))
    }

    internal suspend fun media(id: String): StoredMediaItem? {
        initialized.await()
        return library.find(id)
    }

    internal fun metadataSnapshot(): MetadataSnapshot = metadata.snapshot()

    internal fun searchMetadata(itemId: String, query: String): MetadataSearchResponse =
        metadata.search(itemId, query)

    internal fun matchMetadata(request: MetadataMatchRequest): MetadataSnapshot = metadata.match(request)

    internal fun clearMetadata(): MetadataSnapshot = metadata.clear()

    internal fun artwork(id: String): Artwork? = metadata.artwork(id)

    internal suspend fun rootUris(): List<Uri> {
        initialized.await()
        return library.current().roots.map { Uri.parse(it.uri) }
    }

    internal fun requestPair(request: PairRequest): PairTicket {
        val ticket = pairing.request(request)
        publishPairing()
        notificationRefresh.get()?.invoke()
        return ticket
    }

    internal fun pollPair(requestId: String, secret: String): PairTicket? {
        val result = pairing.poll(requestId, secret, state.value.serverId)
        if (publishPairing()) notificationRefresh.get()?.invoke()
        return result
    }

    internal fun authenticate(token: String): TrustedRecord? = pairing.authenticate(token)?.also {
        val now = System.currentTimeMillis()
        val becameVisible = lastAuthenticatedName != it.displayName || now - lastAuthenticatedAt > CONNECTED_VISIBLE_MS
        lastAuthenticatedName = it.displayName
        lastAuthenticatedAt = now
        if (becameVisible) notificationRefresh.get()?.invoke()
    }

    internal fun acceptPlayback(report: PlaybackReport): PlaybackReport {
        scope.launch {
            val media = library.find(report.itemId)?.item
            lastPlaybackReportAt = System.currentTimeMillis()
            _state.update { current ->
                current.copy(streamingTitle = if (report.playing) media?.displayName else null)
            }
            notificationRefresh.get()?.invoke()
        }
        return report
    }

    internal fun onServerStarted(refreshNotification: () -> Unit) {
        notificationRefresh.set(refreshNotification)
        _state.update { it.copy(running = true, message = null) }
    }

    internal fun onServerStopped() {
        notificationRefresh.set(null)
        lastAuthenticatedName = null
        lastAuthenticatedAt = 0
        lastPlaybackReportAt = 0
        _state.update { it.copy(running = false, streamingTitle = null) }
    }

    internal fun onServerFailure(failure: Throwable) {
        _state.update { it.copy(running = false, message = userMessage(failure)) }
    }

    internal fun connectedDeviceName(): String? = lastAuthenticatedName?.takeIf {
        System.currentTimeMillis() - lastAuthenticatedAt <= CONNECTED_VISIBLE_MS
    }

    internal fun pruneTransientState() {
        val connectedBefore = lastAuthenticatedName != null
        if (connectedBefore && connectedDeviceName() == null) {
            lastAuthenticatedName = null
            lastAuthenticatedAt = 0
        }
        val playbackExpired = state.value.streamingTitle != null &&
            System.currentTimeMillis() - lastPlaybackReportAt > PLAYBACK_VISIBLE_MS
        if (playbackExpired) _state.update { it.copy(streamingTitle = null) }
        val pairingChanged = publishPairing()
        if ((connectedBefore && lastAuthenticatedName == null) || playbackExpired || pairingChanged) {
            notificationRefresh.get()?.invoke()
        }
    }

    private suspend fun runLibraryChange(change: suspend () -> LibrarySnapshot): Result<Unit> = libraryChangeMutex.withLock {
        initialized.await()
        _state.update { it.copy(scanning = true, message = null) }
        try {
            val snapshot = withContext(Dispatchers.IO) { change() }
            metadata.onLibraryChanged(snapshot.items.map { it.item })
            _state.update {
                it.copy(roots = snapshot.roots.map(StoredRoot::publicValue), scanning = false)
            }
            Result.success(Unit)
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            _state.update { it.copy(scanning = false, message = userMessage(failure)) }
            Result.failure(failure)
        }
    }

    private fun publishPairing(): Boolean {
        val pending = pairing.pending()
        val devices = pairing.trustedRecords().map(TrustedRecord::publicValue)
        val changed = state.value.pending != pending || state.value.devices != devices
        _state.update {
            it.copy(
                pending = pending,
                devices = devices,
            )
        }
        return changed
    }

    private fun userMessage(failure: Throwable): String = when (failure) {
        is SecurityException -> "Navelo no longer has access to that folder. Please choose it again."
        else -> failure.message?.takeIf(String::isNotBlank) ?: "Navelo could not complete that action."
    }

    internal sealed interface ItemsPageResult {
        data class Success(val response: ItemsResponse) : ItemsPageResult
        data class Stale(val currentRevision: Long) : ItemsPageResult
    }

    companion object {
        const val DISPLAY_NAME = "Media Phone"
        private const val CONNECTED_VISIBLE_MS = 60_000L
        private const val PLAYBACK_VISIBLE_MS = 2 * 60_000L
        @Volatile private var instance: ServerRuntime? = null

        fun get(context: Context): ServerRuntime = instance ?: synchronized(this) {
            instance ?: ServerRuntime(context).also { instance = it }
        }
    }
}
