package com.mauricio.vista.data

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher

/** Conexión a internet compartida por toda la app. */
object Net {
    /** Se presenta como navegador de escritorio (igual que NewPipe). */
    const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    val quick: OkHttpClient by lazy {
        client.newBuilder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).build()
    }

    /** Marca para el trabajo de fondo (novedades, recomendaciones, temas, populares). */
    object Background : AbstractCoroutineContextElement(BackgroundKey)
    object BackgroundKey : CoroutineContext.Key<Background>

    /**
     * Hilos de baja prioridad para el trabajo de fondo: así en teles con procesador lento
     * la pantalla y el control remoto siempre tienen preferencia.
     */
    val background: CoroutineContext by lazy {
        Executors.newFixedThreadPool(4) { r ->
            Thread(r, "vista-fondo").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }
        }.asCoroutineDispatcher() + Background
    }

    /** Donde correr una petición: en fondo si la pidió el trabajo de fondo, si no, normal. */
    suspend fun io(): CoroutineContext = if (coroutineContext[BackgroundKey] != null) background else Dispatchers.IO

    fun get(url: String, c: OkHttpClient = client): String {
        c.newCall(Request.Builder().url(url).header("User-Agent", UA).build()).execute().use { r ->
            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
            return r.body?.string().orEmpty()
        }
    }
}

/** Puente entre la librería de YouTube y nuestra conexión. */
class YtDownloader : Downloader() {
    override fun execute(request: org.schabi.newpipe.extractor.downloader.Request): Response {
        val data = request.dataToSend()
        val body = data?.toRequestBody(null)
        val b = Request.Builder()
            .method(request.httpMethod(), body)
            .url(request.url())
            .header("User-Agent", Net.UA)
        request.headers().forEach { (name, values) ->
            b.removeHeader(name)
            values.forEach { v -> b.addHeader(name, v) }
        }
        Net.client.newCall(b.build()).execute().use { r ->
            if (r.code == 429) throw ReCaptchaException("YouTube pidió verificación", request.url())
            val text = r.body?.string()
            return Response(r.code, r.message, r.headers.toMultimap(), text, r.request.url.toString())
        }
    }
}
