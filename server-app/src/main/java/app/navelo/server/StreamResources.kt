package app.navelo.server

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

internal fun interface StreamResources {
    fun acquire(): Closeable

    companion object {
        val NONE = StreamResources { Closeable {} }
    }
}

internal class AndroidStreamResources(context: Context) : StreamResources {
    private val powerManager = context.applicationContext.getSystemService(PowerManager::class.java)
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    private val timeoutHandler = Handler(Looper.getMainLooper())
    private val monitor = Any()
    private val activeLeases = HashSet<Long>()
    private var nextLeaseId = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var expiry: Runnable? = null

    override fun acquire(): Closeable {
        val leaseId: Long
        synchronized(monitor) {
            leaseId = ++nextLeaseId
            activeLeases += leaseId
            try {
                if (expiry == null) acquireProtection()
            } catch (failure: Exception) {
                activeLeases -= leaseId
                releaseProtection()
                throw failure
            }
        }
        val closed = AtomicBoolean(false)
        return Closeable {
            if (!closed.compareAndSet(false, true)) return@Closeable
            synchronized(monitor) {
                activeLeases -= leaseId
                if (activeLeases.isEmpty()) releaseProtection()
            }
        }
    }

    private fun acquireProtection() {
        releaseProtection()
        wakeLock = powerManager?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Navelo:MediaStream")?.apply {
            setReferenceCounted(false)
            acquire(MAX_STREAM_PROTECTION_MS)
        }
        wifiLock = wifiManager?.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "Navelo:MediaStream")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        expiry = Runnable {
            synchronized(monitor) { releaseProtection() }
        }.also { timeoutHandler.postDelayed(it, RESOURCE_RELEASE_MS) }
    }

    private fun releaseProtection() {
        expiry?.let(timeoutHandler::removeCallbacks)
        expiry = null
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock = null
    }

    private companion object {
        const val MAX_STREAM_PROTECTION_MS = 6 * 60 * 60 * 1000L
        const val RESOURCE_RELEASE_MS = MAX_STREAM_PROTECTION_MS - 1_000L
    }
}
