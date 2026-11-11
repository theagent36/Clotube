package com.clotube.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.clotube.app.data.DriveWatchHistorySync

class ClotubeApp : Application(), ImageLoaderFactory {

    companion object {
        lateinit var instance: ClotubeApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        DriveWatchHistorySync.init(this)
    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCachePolicy(CachePolicy.ENABLED)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("clotube_image_cache"))
                    .maxSizeBytes(150L * 1024 * 1024) // 150 MB persistent poster cache
                    .build()
            }
            .allowHardware(true)
            .crossfade(200)
            .respectCacheHeaders(false)
            .build()
    }
}
