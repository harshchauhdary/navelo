package app.navelo.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.DocumentsContract
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import app.navelo.shared.NaveloAdvertiser
import app.navelo.shared.Protocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class MediaServerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var runtime: ServerRuntime
    private var httpServer: MediaHttpServer? = null
    private var advertiser: NaveloAdvertiser? = null
    private var cleanupJob: Job? = null
    private var storageRescanJob: Job? = null
    private var started = false
    private var receiverRegistered = false
    private var lastNotificationText: String? = null
    private var lastUnavailableRecoveryAt = 0L
    private val observedUris = LinkedHashSet<Uri>()
    private val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = scheduleStorageRescan(CONTENT_CHANGE_DEBOUNCE_MS)
        override fun onChange(selfChange: Boolean, uri: Uri?) = scheduleStorageRescan(CONTENT_CHANGE_DEBOUNCE_MS)
    }
    private val storageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            scheduleStorageRescan(MOUNT_CHANGE_DEBOUNCE_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        runtime = ServerRuntime.get(this)
        createChannel()
        startForeground(NOTIFICATION_ID, notification("Starting Navelo…"))
        registerStorageReceiver()
        scope.launch { startServing() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        cleanupJob?.cancel()
        storageRescanJob?.cancel()
        runCatching { contentResolver.unregisterContentObserver(contentObserver) }
        observedUris.clear()
        if (receiverRegistered) runCatching { unregisterReceiver(storageReceiver) }
        receiverRegistered = false
        advertiser?.stop()
        advertiser = null
        httpServer?.stop()
        httpServer = null
        runtime.onServerStopped()
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun startServing() {
        if (started) return
        started = true
        try {
            runtime.awaitInitialized()
            val server = MediaHttpServer(this, runtime, Protocol.PORT)
            server.start(SOCKET_TIMEOUT_MS, false)
            httpServer = server
            advertiser = NaveloAdvertiser(this).also {
                it.start(runtime.state.value.serverId, ServerRuntime.DISPLAY_NAME, Protocol.PORT)
            }
            runtime.onServerStarted(::refreshNotification)
            refreshNotification()
            syncContentObservers()
            cleanupJob = scope.launch {
                while (isActive) {
                    delay(30_000)
                    runtime.pruneTransientState()
                    syncContentObservers()
                    val now = System.currentTimeMillis()
                    if (runtime.state.value.roots.any { !it.available } &&
                        now - lastUnavailableRecoveryAt >= UNAVAILABLE_RECOVERY_MS
                    ) {
                        lastUnavailableRecoveryAt = now
                        runtime.rescan()
                    }
                }
            }
            runtime.rescan()
        } catch (failure: Throwable) {
            if (failure is CancellationException) throw failure
            runtime.onServerFailure(failure)
            refreshNotification("Navelo needs attention")
            stopSelf()
        }
    }

    private fun refreshNotification(overrideText: String? = null) {
        val text = overrideText ?: when {
            runtime.state.value.pending.isNotEmpty() -> "${runtime.state.value.pending.last().displayName} wants to connect — tap to review"
            runtime.state.value.streamingTitle != null -> "Playing ${runtime.state.value.streamingTitle}"
            runtime.connectedDeviceName() != null -> "${runtime.connectedDeviceName()} connected"
            else -> "Ready on your Wi-Fi or phone hotspot"
        }
        if (text == lastNotificationText) return
        lastNotificationText = text
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(text))
    }

    private fun registerStorageReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MEDIA_MOUNTED)
            addAction(Intent.ACTION_MEDIA_UNMOUNTED)
            addAction(Intent.ACTION_MEDIA_EJECT)
            addAction(Intent.ACTION_MEDIA_REMOVED)
            addAction(Intent.ACTION_MEDIA_BAD_REMOVAL)
            addDataScheme("file")
        }
        ContextCompat.registerReceiver(this, storageReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        receiverRegistered = true
    }

    private fun scheduleStorageRescan(delayMs: Long) {
        storageRescanJob?.cancel()
        storageRescanJob = scope.launch {
            delay(delayMs)
            runtime.rescan()
            syncContentObservers()
        }
    }

    private suspend fun syncContentObservers() {
        runtime.rootUris().forEach { treeUri ->
            val candidates = buildSet {
                add(treeUri)
                runCatching {
                    add(
                        DocumentsContract.buildDocumentUriUsingTree(
                            treeUri,
                            DocumentsContract.getTreeDocumentId(treeUri),
                        ),
                    )
                }
            }
            candidates.filter(observedUris::add).forEach { uri ->
                runCatching { contentResolver.registerContentObserver(uri, true, contentObserver) }
                    .onFailure { observedUris.remove(uri) }
            }
        }
    }

    private fun notification(text: String): Notification {
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val openPendingIntent = openIntent?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            Intent(this, MediaServerService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_navelo)
            .setContentTitle("Navelo is ready")
            .setContentText(text)
            .setContentIntent(openPendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, "Stop", stopPendingIntent)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Navelo availability",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Shows when your phone is sharing media with Navelo TV" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        internal const val ACTION_START = "app.navelo.server.action.START"
        private const val ACTION_STOP = "app.navelo.server.action.STOP"
        private const val CHANNEL_ID = "navelo_server"
        private const val NOTIFICATION_ID = 1001
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val MOUNT_CHANGE_DEBOUNCE_MS = 2_000L
        private const val CONTENT_CHANGE_DEBOUNCE_MS = 3_000L
        private const val UNAVAILABLE_RECOVERY_MS = 15 * 60_000L
    }
}
