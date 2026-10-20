package com.mauricio.vista.data

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.stream.Stream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext

/**
 * Descargas para ver sin internet (en el celular).
 * Video: se bajan por separado la imagen (MP4) y el sonido (M4A) y se juntan en un solo .mp4.
 * Solo audio: un .m4a, ideal para música y pódcasts.
 */
object Downloads {
    data class Item(
        val id: String,
        val video: Video,
        val audioOnly: Boolean,
        /** Altura pedida (p. ej. 720). */
        val quality: Int,
        val status: String, // "espera", "bajando", "uniendo", "listo", "error"
        val progress: Float = 0f,
        val path: String = "",
        val size: Long = 0,
        val error: String = "",
        val at: Long = System.currentTimeMillis(),
    ) {
        val done get() = status == "listo"
        fun toJson(): JSONObject = JSONObject().put("id", id).put("v", video.toJson()).put("a", audioOnly).put("q", quality)
            .put("s", status).put("p", path).put("z", size).put("e", error).put("at", at)

        companion object {
            fun fromJson(o: JSONObject) = Item(
                o.getString("id"), Video.fromJson(o.getJSONObject("v")), o.optBoolean("a"), o.optInt("q"),
                o.optString("s").let { if (it == "listo") it else if (it == "error") it else "espera" },
                0f, o.optString("p"), o.optLong("z"), o.optString("e"), o.optLong("at"),
            )
        }
    }

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> get() = _items

    private lateinit var app: Context
    private lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var worker: Job? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
        dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "descargas").apply { mkdirs() }
        _items.value = runCatching {
            val a = JSONArray(File(ctx.filesDir, "descargas.json").readText())
            List(a.length()) { Item.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        if (_items.value.any { it.status == "espera" }) start()
    }

    private fun save() {
        runCatching {
            val a = JSONArray(); _items.value.forEach { a.put(it.toJson()) }
            File(app.filesDir, "descargas.json").writeText(a.toString())
        }
    }

    private fun update(id: String, f: (Item) -> Item) {
        _items.value = _items.value.map { if (it.id == id) f(it) else it }
    }

    fun find(videoId: String): Item? = _items.value.firstOrNull { it.video.id == videoId && it.done }

    /** Archivo local si ya está descargado (para reproducir sin internet). */
    fun localFile(videoId: String): File? = find(videoId)?.let { File(it.path) }?.takeIf { it.exists() }

    fun add(v: Video, quality: Int, audioOnly: Boolean) {
        val id = v.id + (if (audioOnly) "-a" else "-v$quality")
        if (_items.value.any { it.id == id && it.status != "error" }) return
        _items.value = listOf(Item(id, v, audioOnly, quality, "espera")) + _items.value.filterNot { it.id == id }
        save()
        start()
    }

    fun remove(id: String) {
        _items.value.firstOrNull { it.id == id }?.let { runCatching { File(it.path).delete() } }
        _items.value = _items.value.filterNot { it.id == id }
        save()
    }

    fun retry(id: String) {
        update(id) { it.copy(status = "espera", error = "", progress = 0f) }
        save(); start()
    }

    private fun start() {
        if (worker?.isActive == true) return
        runCatching {
            val i = Intent(app, DownloadService::class.java)
            if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(i) else app.startService(i)
        }
        worker = scope.launch {
            while (isActive) {
                val next = _items.value.lastOrNull { it.status == "espera" } ?: break
                runCatching { process(next) }.onFailure { e ->
                    update(next.id) { it.copy(status = "error", error = Yt.friendly(e)) }
                }
                save()
            }
            runCatching { app.stopService(Intent(app, DownloadService::class.java)) }
        }
    }

    val busy: Boolean get() = _items.value.any { it.status == "espera" || it.status == "bajando" || it.status == "uniendo" }

    private suspend fun process(item: Item) {
        update(item.id) { it.copy(status = "bajando", progress = 0f) }
        val info = Yt.stream(item.video.url, fresh = true)
        val tmp = File(dir, ".tmp").apply { mkdirs() }
        val safe = item.video.title.replace(Regex("[^\\p{L}\\p{N} ._-]"), "").take(60).trim().ifBlank { item.video.id }
        if (item.audioOnly) {
            val a = com.mauricio.vista.player.Sources.pickAudio(info, m4aOnly = true, progressiveOnly = true)
                ?: com.mauricio.vista.player.Sources.pickAudio(info, progressiveOnly = true) ?: throw IllegalStateException("Sin audio")
            val ext = if (a.format == MediaFormat.M4A) "m4a" else "webm"
            val out = File(dir, "$safe [${item.video.id}].$ext")
            fetch(a, out) { p -> update(item.id) { it.copy(progress = p) } }
            update(item.id) { it.copy(status = "listo", progress = 1f, path = out.absolutePath, size = out.length()) }
            return
        }
        // Imagen MP4 (H.264) que no pase de la calidad pedida
        val vids = info.videoOnlyStreams.filter { it.isUrl && it.format == MediaFormat.MPEG_4 && it.itagItem != null && !it.codec.orEmpty().startsWith("av01") }
        val v = vids.filter { it.height <= item.quality }.maxByOrNull { it.height } ?: vids.minByOrNull { it.height }
        val a = com.mauricio.vista.player.Sources.pickAudio(info, m4aOnly = true, progressiveOnly = true)
        val out = File(dir, "$safe [${item.video.id}].mp4")
        if (v == null || a == null) {
            // Respaldo: el archivo con todo junto (máximo 360p)
            val m = info.videoStreams.filter { it.isUrl && it.format == MediaFormat.MPEG_4 }.maxByOrNull { it.height }
                ?: throw IllegalStateException("Este video no se puede descargar")
            fetch(m, out) { p -> update(item.id) { it.copy(progress = p) } }
        } else {
            val vf = File(tmp, item.id + ".v.mp4"); val af = File(tmp, item.id + ".a.m4a")
            fetch(v, vf) { p -> update(item.id) { it.copy(progress = p * 0.85f) } }
            fetch(a, af) { p -> update(item.id) { it.copy(progress = 0.85f + p * 0.13f) } }
            update(item.id) { it.copy(status = "uniendo", progress = 0.98f) }
            mux(vf, af, out)
            vf.delete(); af.delete()
        }
        update(item.id) { it.copy(status = "listo", progress = 1f, path = out.absolutePath, size = out.length()) }
    }

    /** Baja un archivo de YouTube en pedazos de 4 MB (así no se alenta). */
    private suspend fun fetch(s: Stream, out: File, onProgress: (Float) -> Unit) {
        val base = s.content
        val total = s.itagItem?.contentLength?.takeIf { it > 0 }
            ?: Regex("[?&]clen=(\\d+)").find(base)?.groupValues?.get(1)?.toLongOrNull() ?: -1L
        val playback = base.contains("/videoplayback")
        val chunk = 4L * 1024 * 1024
        var pos = 0L
        var rn = 0
        RandomAccessFile(out, "rw").use { raf ->
            raf.setLength(0)
            while (coroutineContext.isActive) {
                val end = if (total > 0) minOf(pos + chunk, total) - 1 else pos + chunk - 1
                val b = Request.Builder()
                if (playback) {
                    b.url("$base&range=$pos-$end&rn=${rn++}").post(byteArrayOf(0x78, 0).toRequestBody(null))
                    val ua = if (YoutubeParsingHelper.isVisionOsStreamingUrl(base)) YoutubeParsingHelper.getVisionOsUserAgent(null) else Net.UA
                    b.header("User-Agent", ua)
                } else {
                    b.url(base).header("User-Agent", Net.UA).header("Range", "bytes=$pos-$end")
                }
                var got = 0L
                var attempt = 0
                while (true) {
                    try {
                        Net.client.newCall(b.build()).execute().use { r ->
                            if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                            val input = r.body!!.byteStream()
                            val buf = ByteArray(64 * 1024)
                            raf.seek(pos)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                raf.write(buf, 0, n)
                                got += n
                            }
                        }
                        break
                    } catch (e: java.io.IOException) {
                        if (++attempt >= 4) throw e
                        got = 0
                        kotlinx.coroutines.delay(1500L * attempt)
                    }
                }
                pos += got
                if (total > 0) onProgress((pos.toFloat() / total).coerceIn(0f, 1f))
                if (got == 0L || (total > 0 && pos >= total) || (total <= 0 && got < chunk)) break
            }
        }
    }

    /** Junta imagen y sonido en un solo MP4 (sin volver a comprimir: es rapidísimo). */
    private fun mux(video: File, audio: File, out: File) {
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val sources = listOf(video, audio).map { f ->
            MediaExtractor().apply { setDataSource(f.absolutePath) }
        }
        try {
            val tracks = sources.map { ex ->
                ex.selectTrack(0)
                muxer.addTrack(ex.getTrackFormat(0))
            }
            muxer.start()
            val buf = ByteBuffer.allocate(2 * 1024 * 1024)
            val info = MediaCodec.BufferInfo()
            sources.forEachIndexed { i, ex ->
                while (true) {
                    info.offset = 0
                    info.size = ex.readSampleData(buf, 0)
                    if (info.size < 0) break
                    info.presentationTimeUs = ex.sampleTime
                    info.flags = if ((ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
                    muxer.writeSampleData(tracks[i], buf, info)
                    ex.advance()
                }
            }
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            sources.forEach { runCatching { it.release() } }
        }
    }

    fun freeSpace(): Long = runCatching { dir.usableSpace }.getOrDefault(0L)
}

/** Mantiene viva la app mientras descarga, con un aviso en la barra de notificaciones. */
class DownloadService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(NotificationChannel("descargas", "Descargas", NotificationManager.IMPORTANCE_LOW))
        }
        val n: Notification = (if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "descargas") else @Suppress("DEPRECATION") Notification.Builder(this))
            .setContentTitle("Vista")
            .setContentText("Descargando videos…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 29) startForeground(7, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            else startForeground(7, n)
        }
        if (!Downloads.busy) stopSelf()
        return START_NOT_STICKY
    }
}
