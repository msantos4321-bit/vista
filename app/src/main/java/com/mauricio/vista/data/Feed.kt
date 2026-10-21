package com.mauricio.vista.data

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
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
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.time.OffsetDateTime

/**
 * «Nuevos de tus suscripciones»: se arma con la lista pública (RSS) de cada canal,
 * que es rápida y no gasta nada. Se guarda en el aparato y se renueva cada 30 minutos.
 */
object Feed {
    sealed interface State {
        data object Empty : State
        data class Loading(val done: Int, val total: Int) : State
        data object Ready : State
    }

    private val _videos = MutableStateFlow<List<Video>>(emptyList())
    val videos: StateFlow<List<Video>> get() = _videos
    private val _state = MutableStateFlow<State>(State.Empty)
    val state: StateFlow<State> get() = _state

    /** Último video de cada canal (clave del canal → milisegundos; 0 = no tiene videos). Solo de canales que contestaron. */
    @Volatile var lastUpload: Map<String, Long> = emptyMap()
        private set

    private lateinit var cache: File
    private var loadedAt = 0L
    private val lock = Mutex()

    fun init(ctx: Context) {
        cache = File(Profiles.dir(ctx), "feed.json")
        _videos.value = emptyList(); _state.value = State.Empty; loadedAt = 0
        runCatching {
            val o = JSONObject(cache.readText())
            loadedAt = o.optLong("at")
            val a = o.getJSONArray("v")
            _videos.value = List(a.length()) { Video.fromJson(a.getJSONObject(it)) }
            if (_videos.value.isNotEmpty()) _state.value = State.Ready
            o.optJSONObject("last")?.let { l -> lastUpload = l.keys().asSequence().associateWith { k -> l.optLong(k) } }
        }
    }

    fun invalidate() { loadedAt = 0 }

    /** Canales que llevan más de [months] meses sin subir nada (según la última revisión). */
    fun inactive(months: Int): List<Chan> {
        val cut = System.currentTimeMillis() - months * 30L * 86_400_000L
        val l = lastUpload
        return Store.subs.value.filter { c -> l[Store.channelKey(c.url)]?.let { it < cut } == true }
    }

    /** Renueva si ya pasó el tiempo (o si se pide a fuerza). */
    suspend fun refresh(force: Boolean = false) { lock.withLock {
        val subs = Store.subs.value
        if (subs.isEmpty()) { _videos.value = emptyList(); _state.value = State.Empty; return@withLock }
        if (!force && System.currentTimeMillis() - loadedAt < 30 * 60_000 && _videos.value.isNotEmpty()) return@withLock
        _state.value = State.Loading(0, subs.size)
        var done = 0
        val sem = Semaphore(8)
        val last = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val all = coroutineScope {
            subs.map { ch ->
                async(Net.background) {
                    sem.withPermit {
                        val got = runCatching { channelFeed(ch) }
                        got.getOrNull()?.let { l -> last[Store.channelKey(ch.url)] = l.maxOfOrNull { it.publishedMs } ?: 0L }
                        val r = got.getOrDefault(emptyList())
                        synchronized(this@Feed) { done++; if (done % 6 == 0 || done == subs.size) _state.value = State.Loading(done, subs.size) }
                        r
                    }
                }
            }.awaitAll().flatten()
        }
        val sorted = all.distinctBy { it.id }.sortedByDescending { it.publishedMs }.take(300)
        if (sorted.isNotEmpty() || _videos.value.isEmpty()) _videos.value = sorted
        loadedAt = System.currentTimeMillis()
        if (last.isNotEmpty()) lastUpload = lastUpload + last
        _state.value = if (_videos.value.isEmpty()) State.Empty else State.Ready
        withContext(Dispatchers.IO) {
            runCatching {
                val a = JSONArray(); _videos.value.forEach { a.put(it.toJson()) }
                val l = JSONObject(); lastUpload.forEach { (k, v) -> l.put(k, v) }
                cache.writeText(JSONObject().put("at", loadedAt).put("v", a).put("last", l).toString())
            }
        }
    } }

    /** Lista pública de un canal: sus últimos ~15 videos. */
    private suspend fun channelFeed(ch: Chan): List<Video> {
        var id = ch.channelId
        if (id == null) {
            // Canal guardado con otra forma de liga (@nombre): se convierte una vez
            val r = Yt.resolveChannel(ch.url) ?: return emptyList()
            Store.unsubscribe(ch.url); Store.subscribe(r.copy(name = ch.name.ifBlank { r.name }))
            id = r.channelId ?: return emptyList()
        }
        val xml = Net.get("https://www.youtube.com/feeds/videos.xml?channel_id=$id", Net.quick)
        return parse(xml, ch)
    }

    private fun parse(xml: String, ch: Chan): List<Video> {
        val out = ArrayList<Video>()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(xml.reader())
        var inEntry = false
        var vid = ""; var title = ""; var published = ""; var link = ""; var thumb = ""; var views = -1L; var author = ""
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "entry" -> { inEntry = true; vid = ""; title = ""; published = ""; link = ""; thumb = ""; views = -1; author = "" }
                    "yt:videoId" -> if (inEntry) vid = p.nextText()
                    "title" -> if (inEntry) title = p.nextText()
                    "published" -> if (inEntry) published = p.nextText()
                    "name" -> if (inEntry) author = p.nextText()
                    "link" -> if (inEntry) link = p.getAttributeValue(null, "href").orEmpty()
                    "media:thumbnail" -> if (inEntry) thumb = p.getAttributeValue(null, "url").orEmpty()
                    "media:statistics" -> if (inEntry) views = p.getAttributeValue(null, "views")?.toLongOrNull() ?: -1
                }
                XmlPullParser.END_TAG -> if (p.name == "entry" && inEntry) {
                    inEntry = false
                    if (vid.isNotBlank()) {
                        val ms = runCatching { OffsetDateTime.parse(published).toInstant().toEpochMilli() }.getOrDefault(0L)
                        out += Video(
                            url = Yt.watchUrl(vid), title = title, channel = author.ifBlank { ch.name }, channelUrl = ch.url,
                            thumb = "https://i.ytimg.com/vi/$vid/hqdefault.jpg", views = views, publishedMs = ms,
                            uploaded = Fmt.ago(ms), short = link.contains("/shorts/"),
                        )
                    }
                }
            }
            ev = p.next()
        }
        return out
    }
}
