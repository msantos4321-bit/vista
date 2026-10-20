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
        // Si la app se cierra sola, se guarda el motivo para poder revisarlo (Ajustes → Último error)
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                getSharedPreferences("vista", MODE_PRIVATE).edit().putString(
                    "lastError",
                    ("Cierre inesperado · v${BuildConfig.VERSION_NAME} · ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL} · Android ${android.os.Build.VERSION.RELEASE} · hilo ${t.name}\n" +
                        android.util.Log.getStackTraceString(e)).take(6000),
                ).putLong("crashAt", System.currentTimeMillis()).commit()
            }
            prev?.uncaughtException(t, e)
        }
        Yt.init()
        Store.init(this)
        Feed.init(this)
        com.mauricio.vista.data.Recs.init(this)
        Downloads.init(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient { Net.client }
        .crossfade(true)
        .respectCacheHeaders(false)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.20).build() }
        // En teles: imágenes en formato ligero (la mitad de memoria y más rápido)
        .allowRgb565(packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK))
        .diskCache { DiskCache.Builder().directory(cacheDir.resolve("img")).maxSizeBytes(250L * 1024 * 1024).build() }
        .build()
}
