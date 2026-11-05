package com.mauricio.vista.data

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Duración de los videos que no la traen (las novedades salen del RSS de YouTube, que no la incluye).
 * Se completa leyendo la pestaña «Videos» de cada canal (una sola petición trae ~30 videos con su duración)
 * y se guarda en el aparato: la duración de un video nunca cambia.
 * Valor −1 = transmisión en vivo.
 */
object Durations {
    private val map = ConcurrentHashMap<String, Long>()
    private val _all = MutableStateFlow<Map<String, Long>>(emptyMap())
    /** Para las tarjetas: id del video → segundos. */
    val all: StateFlow<Map<String, Long>> get() = _all

    private var file: File? = null
    @Volatile private var dirty = false
    private val lock = Mutex()
    /** Canales ya revisados en esta sesión (para no volver a pedirlos si un video no salió en su pestaña). */
    private val triedChannels = ConcurrentHashMap<String, Long>()

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "duraciones.json")
        runCatching {
            val o = JSONObject(file!!.readText())
            o.keys().forEach { k -> map[k] = o.optLong(k) }
        }
        _all.value = HashMap(map)
    }

    fun of(v: Video): Long = if (v.duration != 0L) v.duration else map[v.id] ?: 0L

    /** Anota la duración que trajo cualquier otra parte (búsqueda, canal, el reproductor). */
    fun learn(id: String?, sec: Long) {
        if (id.isNullOrBlank() || sec == 0L) return
        if (map.put(id, sec) != sec) dirty = true
    }

    private fun publish() {
        if (!dirty) return
        dirty = false
        if (map.size > 8000) map.keys.toList().take(map.size - 6000).forEach { map.remove(it) }
        _all.value = HashMap(map)
        runCatching {
            val o = JSONObject(); map.forEach { (k, v) -> o.put(k, v) }
            file?.writeText(o.toString())
        }
    }

    /** Completa la duración de los videos más nuevos que no la tienen ([maxChannels] canales por vez). */
    suspend fun fill(videos: List<Video>, maxChannels: Int) { lock.withLock {
        val now = System.currentTimeMillis()
        val missing = videos.asSequence()
            .filter { !it.short && !it.live && it.duration == 0L && !map.containsKey(it.id) }
            .take(250).toList()
        if (missing.isEmpty()) return@withLock
        val channels = missing.groupBy { it.channelUrl }
            .filterKeys { url -> url.isNotBlank() && (triedChannels[url]?.let { now - it > 30 * 60_000 } ?: true) }
            .entries.sortedByDescending { e -> e.value.maxOf { it.publishedMs } }
            .take(maxChannels).map { it.key }
        if (channels.isEmpty()) return@withLock
        val sem = Semaphore(if (Recs.lightMode) 2 else 4)
        withContext(Net.background) {
            coroutineScope {
                channels.map { url ->
                    async {
                        sem.withPermit {
                            if (Recs.playing && Recs.lightMode) return@withPermit
                            runCatching { channelVideos(url) }
                            triedChannels[url] = System.currentTimeMillis()
                        }
                    }
                }.awaitAll()
            }
            publish()
        }
    } }

    private fun channelVideos(url: String) {
        val id = Chan(url, "").channelId ?: return
        val yt = ServiceList.YouTube
        val lh = yt.channelTabLHFactory.fromQuery("channel/$id", listOf(ChannelTabs.VIDEOS), "")
        val info = ChannelTabInfo.getInfo(yt, lh)
        info.relatedItems.filterIsInstance<StreamInfoItem>().forEach { i ->
            val live = i.streamType == StreamType.LIVE_STREAM || i.streamType == StreamType.AUDIO_LIVE_STREAM
            learn(Yt.videoId(i.url), if (live) -1 else i.duration)
        }
    }
}
