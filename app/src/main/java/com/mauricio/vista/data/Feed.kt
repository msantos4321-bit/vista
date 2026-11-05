package com.mauricio.vista.data

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.time.OffsetDateTime
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * «Nuevos de tus suscripciones»: se arma con la lista pública (RSS) de cada canal.
 *
 * Para que sea rápido con cientos de canales:
 * - Lo guardado se enseña al instante y lo nuevo va apareciendo conforme llega (no hasta el final).
 * - Muchas peticiones a la vez (16 en celular, 8 en la tele).
 * - Cada canal se revisa según qué tan seguido sube: los activos cada 10 min, los que casi no suben cada día.
 *   Primero los canales que más ves.
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

    /** Lo último de cada canal y cuándo se revisó. */
    private class ChanFeed(val at: Long, val videos: List<Video>)
    private val chans = ConcurrentHashMap<String, ChanFeed>()

    private lateinit var cache: File
    private val lock = Mutex()
    private val bg = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Hilos solo para bajar las listas: casi todo el tiempo esperan a internet, así que pueden ser muchos sin trabar nada. */
    private val pool by lazy {
        Executors.newFixedThreadPool(if (Recs.lightMode) 8 else 16) { r ->
            Thread(r, "vista-novedades").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }
        }.asCoroutineDispatcher()
    }

    fun init(ctx: Context) {
        cache = File(Profiles.dir(ctx), "feed.json")
        chans.clear(); _videos.value = emptyList(); _state.value = State.Empty; lastUpload = emptyMap()
        runCatching {
            val o = JSONObject(cache.readText())
            val c = o.optJSONObject("c")
            if (c != null) {
                c.keys().forEach { k ->
                    val e = c.getJSONObject(k); val a = e.getJSONArray("v")
                    chans[k] = ChanFeed(e.optLong("at"), List(a.length()) { Video.fromJson(a.getJSONObject(it)) })
                }
            } else o.optJSONArray("v")?.let { a ->
                // Formato anterior (una sola lista): se reparte por canal y se revisa todo otra vez
                List(a.length()) { Video.fromJson(a.getJSONObject(it)) }.groupBy { Store.channelKey(it.channelUrl) }
                    .forEach { (k, l) -> chans[k] = ChanFeed(0L, l) }
            }
            o.optJSONObject("last")?.let { l -> lastUpload = l.keys().asSequence().associateWith { k -> l.optLong(k) } }
        }
        rebuild()
    }

    /** Se cambió la lista de canales: se rearma lo que se ve (sin volver a bajar nada). */
    fun invalidate() { if (::cache.isInitialized) bg.launch { rebuild() } }

    /** Canales que llevan más de [months] meses sin subir nada (según la última revisión). */
    fun inactive(months: Int): List<Chan> {
        val cut = System.currentTimeMillis() - months * 30L * 86_400_000L
        val l = lastUpload
        return Store.subs.value.filter { c -> l[Store.channelKey(c.url)]?.let { it < cut } == true }
    }

    /** Junta lo de todos los canales que sigues: lo más nuevo primero, sin repetidos, hasta 2 meses atrás. */
    private fun rebuild() {
        val subs = Store.subs.value.map { Store.channelKey(it.url) }.toHashSet()
        val cut = System.currentTimeMillis() - 60L * 86_400_000L
        val all = chans.entries.asSequence()
            .filter { it.key in subs }
            .flatMap { it.value.videos.asSequence() }
            .filter { it.publishedMs == 0L || it.publishedMs > cut }
            .distinctBy { it.id }
            .sortedByDescending { it.publishedMs }
            .take(1500)
            .toList()
        _videos.value = all
        if (_state.value !is State.Loading) _state.value = if (all.isEmpty()) State.Empty else State.Ready
    }

    /** Cada cuánto se revisa un canal, según qué tan seguido sube y qué tanto lo ves. */
    private fun interval(key: String, now: Long, liked: Boolean): Long {
        val last = lastUpload[key] ?: chans[key]?.videos?.maxOfOrNull { it.publishedMs } ?: 0L
        val days = if (last <= 0) 9999.0 else (now - last) / 86_400_000.0
        val min = 60_000L
        val base = when {
            days < 2 -> 10 * min
            days < 14 -> 30 * min
            days < 60 -> 120 * min
            days < 365 -> 8 * 60 * min
            else -> 24 * 60 * min
        }
        return if (liked) minOf(base, 15 * min) else base
    }

    /** Qué tanto ves cada canal (clave → peso), con más peso a lo reciente. */
    fun affinity(): Map<String, Double> {
        val now = System.currentTimeMillis()
        val w = HashMap<String, Double>()
        Store.history.value.take(300).forEach { h ->
            if (h.video.channelUrl.isBlank()) return@forEach
            val ageDays = ((now - h.at) / 86_400_000.0).coerceAtLeast(0.0)
            val k = Store.channelKey(h.video.channelUrl)
            w[k] = (w[k] ?: 0.0) + (0.25 + h.progress) * Math.exp(-ageDays / 45.0)
        }
        return w
    }

    /**
     * Revisa los canales que ya toca revisar (o todos con [force]).
     * Lo nuevo aparece en pantalla mientras se sigue revisando.
     */
    suspend fun refresh(force: Boolean = false) { lock.withLock {
        if (Store.subs.value.isEmpty()) { _videos.value = emptyList(); _state.value = State.Empty; return@withLock }
        resolveHandles()
        val subs = Store.subs.value
        val now = System.currentTimeMillis()
        val aff = affinity()
        val due = subs.filter { c ->
            val k = Store.channelKey(c.url)
            val f = chans[k]
            force || f == null || now - f.at >= interval(k, now, (aff[k] ?: 0.0) > 0.5)
        }.sortedByDescending { c ->
            // Primero los que más ves y los que suben seguido
            val k = Store.channelKey(c.url)
            val last = lastUpload[k] ?: 0L
            (aff[k] ?: 0.0) * 3 + if (last > 0) Math.exp(-((now - last) / 86_400_000.0) / 7.0) else 0.5
        }
        if (due.isEmpty()) { rebuild(); return@withLock }

        _state.value = State.Loading(0, due.size)
        val done = AtomicInteger()
        var shownAt = 0L
        val step = if (Recs.lightMode) 1500L else 700L
        val last = ConcurrentHashMap<String, Long>()
        withContext(pool) {
            coroutineScope {
                due.map { ch ->
                    async {
                        val k = Store.channelKey(ch.url)
                        runCatching { channelFeed(ch) }.getOrNull()?.let { l ->
                            chans[k] = ChanFeed(System.currentTimeMillis(), l)
                            last[k] = l.maxOfOrNull { it.publishedMs } ?: 0L
                        }
                        val d = done.incrementAndGet()
                        // Se enseña lo que ya llegó, sin rearmar la pantalla a cada rato
                        synchronized(this@Feed) {
                            val t = System.currentTimeMillis()
                            if (t - shownAt > step || d == due.size) {
                                shownAt = t
                                if (last.isNotEmpty()) lastUpload = lastUpload + last
                                _state.value = State.Loading(d, due.size)
                                rebuild()
                            }
                        }
                    }
                }.awaitAll()
            }
        }
        if (last.isNotEmpty()) lastUpload = lastUpload + last
        _state.value = State.Ready
        rebuild()
        withContext(Dispatchers.IO) { save() }
        // Después, en calma: la duración de lo nuevo (el RSS no la trae)
        bg.launch { runCatching { Durations.fill(_videos.value, if (Recs.lightMode) 15 else 30) } }
    } }

    private fun save() {
        runCatching {
            val subs = Store.subs.value.map { Store.channelKey(it.url) }.toHashSet()
            val c = JSONObject()
            chans.forEach { (k, f) ->
                if (k !in subs) return@forEach
                val a = JSONArray(); f.videos.forEach { a.put(it.toJson()) }
                c.put(k, JSONObject().put("at", f.at).put("v", a))
            }
            val l = JSONObject(); lastUpload.forEach { (k, v) -> l.put(k, v) }
            cache.writeText(JSONObject().put("c", c).put("last", l).toString())
        }
    }

    /** Canales guardados con otra forma de liga (@nombre): se convierten una vez, de uno en uno. */
    private suspend fun resolveHandles() {
        val pending = Store.subs.value.filter { it.channelId == null }.take(20)
        for (ch in pending) {
            val r = Yt.resolveChannel(ch.url) ?: continue
            Store.replaceChan(ch.url, r.copy(name = ch.name.ifBlank { r.name }, avatar = ch.avatar.ifBlank { r.avatar }))
        }
    }

    /** Lista pública de un canal: sus últimos ~15 videos. */
    private fun channelFeed(ch: Chan): List<Video> {
        val id = ch.channelId ?: return emptyList()
        val xml = Net.get("https://www.youtube.com/feeds/videos.xml?channel_id=$id", Net.quick)
        return parse(xml, ch)
    }

    private fun parse(xml: String, ch: Chan): List<Video> {
        val out = ArrayList<Video>()
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        p.setInput(xml.reader())
        var inEntry = false
        var vid = ""; var title = ""; var published = ""; var link = ""; var views = -1L; var author = ""
        var ev = p.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            when (ev) {
                XmlPullParser.START_TAG -> when (p.name) {
                    "entry" -> { inEntry = true; vid = ""; title = ""; published = ""; link = ""; views = -1; author = "" }
                    "yt:videoId" -> if (inEntry) vid = p.nextText()
                    "title" -> if (inEntry) title = p.nextText()
                    "published" -> if (inEntry) published = p.nextText()
                    "name" -> if (inEntry) author = p.nextText()
                    "link" -> if (inEntry) link = p.getAttributeValue(null, "href").orEmpty()
                    "media:statistics" -> if (inEntry) views = p.getAttributeValue(null, "views")?.toLongOrNull() ?: -1
                }
                XmlPullParser.END_TAG -> if (p.name == "entry" && inEntry) {
                    inEntry = false
                    if (vid.isNotBlank()) {
                        val ms = runCatching { OffsetDateTime.parse(published).toInstant().toEpochMilli() }.getOrDefault(0L)
                        out += Video(
                            url = Yt.watchUrl(vid), title = title, channel = author.ifBlank { ch.name }, channelUrl = ch.url,
                            thumb = "https://i.ytimg.com/vi/$vid/hqdefault.jpg", views = views, publishedMs = ms,
                            // La fecha se calcula al mostrarla (antes se guardaba el texto y se quedaba viejo)
                            uploaded = "", short = link.contains("/shorts/"),
                        )
                    }
                }
            }
            ev = p.next()
        }
        return out
    }
}
