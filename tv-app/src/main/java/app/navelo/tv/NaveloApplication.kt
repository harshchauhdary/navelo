package app.navelo.tv

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.OkHttpClient

/** Keeps artwork caching predictable on modest TV hardware. */
class NaveloApplication : Application(), ImageLoaderFactory {
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        // A phone hotspot can reach Navelo without a validated internet network.
        // Coil's default observer otherwise forces cache-only HTTP (504) on that LAN.
        .networkObserverEnabled(false)
        .okHttpClient {
            OkHttpClient.Builder().followRedirects(false).followSslRedirects(false).build()
        }
        .crossfade(false)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizeBytes(48 * 1024 * 1024)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("artwork"))
                .maxSizeBytes(256L * 1024L * 1024L)
                .build()
        }
        .respectCacheHeaders(true)
        .build()
}
