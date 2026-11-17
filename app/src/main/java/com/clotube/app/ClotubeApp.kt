package com.clotube.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import com.clotube.app.data.DriveWatchHistorySync
import com.clotube.app.data.TokenRepository
import com.clotube.app.network.ClotubeNetworkMonitor
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class ClotubeApp : Application(), ImageLoaderFactory {

    companion object {
        lateinit var instance: ClotubeApp
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        DriveWatchHistorySync.init(this)
        ClotubeNetworkMonitor.startMonitoring(this)
    }

    override fun newImageLoader(): ImageLoader {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addInterceptor { chain ->
                val request = chain.request()
                val url = request.url.toString()
                val isDriveUrl = url.contains("lh3.googleusercontent.com") ||
                        url.contains("googleapis.com/drive/v3/files") ||
                        url.contains("drive.google.com")

                if (isDriveUrl && request.header("Authorization") == null) {
                    val token = runCatching {
                        runBlocking {
                            TokenRepository(this@ClotubeApp).getValidAccessToken(forceRefresh = false)
                        }
                    }.getOrNull()

                    if (!token.isNullOrBlank()) {
                        val authRequest = request.newBuilder()
                            .header("Authorization", "Bearer $token")
                            .build()
                        chain.proceed(authRequest)
                    } else {
                        chain.proceed(request)
                    }
                } else {
                    chain.proceed(request)
                }
            }
            .build()

        return ImageLoader.Builder(this)
            .okHttpClient(okHttpClient)
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
