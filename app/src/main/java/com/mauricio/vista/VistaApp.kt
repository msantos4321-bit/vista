package com.mauricio.vista

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.mauricio.vista.data.Downloads
import com.mauricio.vista.data.Feed
import com.mauricio.vista.data.Net
import com.mauricio.vista.data.Store
import com.mauricio.vista.data.Yt

class VistaApp : Application(), ImageLoaderFactory {
    override fun onCreate() {
        super.onCreate()
        Yt.init()
        Store.init(this)
        Feed.init(this)
        Downloads.init(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { Net.client }
        .crossfade(true)
        .respectCacheHeaders(false)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("img")).maxSizeBytes(250L * 1024 * 1024).build() }
        .build()
}
