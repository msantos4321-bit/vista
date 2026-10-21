package com.mauricio.vista.data

import android.content.Context
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
import java.io.File

/**
 * Recomendaciones hechas en el aparato, sin cuenta:
 * se juntan los «videos relacionados» (que calcula YouTube) de lo que has visto,
 * se les da más peso a lo que viste completo y reciente, y se quita lo ya visto.
 */
object Recs {
    /** Temas que se pueden elegir y lo que se busca para cada uno. */
    val topicQueries = linkedMapOf(
        "Bolsa y trading" to "bolsa de valores trading análisis",
        "Finanzas personales" to "finanzas personales inversiones México",
        "Noticias" to "noticias México hoy",
        "Fútbol" to "fútbol resumen goles",
        "Béisbol" to "béisbol resumen",
        "Box y UFC" to "box UFC pelea",
        "Fórmula 1" to "Fórmula 1 resumen",
        "Tecnología" to "tecnología novedades celulares",
        "Inteligencia artificial" to "inteligencia artificial novedades",
        "Cocina" to "recetas fáciles cocina mexicana",
        "Música" to "música estrenos video oficial",
        "Comedia" to "comedia stand up",
        "Ciencia" to "ciencia explicada",
        "Historia" to "historia documental",
        "Autos" to "autos reseña prueba",
        "Viajes" to "viajes guía lugares",
        "Salud y ejercicio" to "ejercicio salud rutina",
        "Hogar y jardín" to "hogar jardín ideas",
        "Videojuegos" to "videojuegos novedades",
        "Películas y series" to "películas series reseña estrenos",
        "Para niños" to "caricaturas para niños",
    )

    fun queryFor(topic: String) = topicQueries[topic] ?: topic

    /** Lo pone el reproductor: mientras se ve un video no se hace trabajo de fondo pesado. */
    @Volatile var playing = false
    /** Teles y aparatos lentos: menos trabajo de fondo. */
    @Volatile var lightMode = false

    private val _forYou = MutableStateFlow<List<Video>>(emptyList())
    val forYou: StateFlow<List<Video>> get() = _forYou

    data class Suggested(val chan: Chan, val score: Double, val sample: Video)
    private val _channels = MutableStateFlow<List<Suggested>>(emptyList())
    val channels: StateFlow<List<Suggested>> get() = _channels

    private val _topicRows = MutableStateFlow<Map<String, List<Video>>>(emptyMap())
    val topicRows: StateFlow<Map<String, List<Video>>> get() = _topicRows

    private lateinit var relFile: File
    private lateinit var topicFile: File
    private val related = LinkedHashMap<String, Pair<Long, List<Video>>>()
    private val topicCache = HashMap<String, Pair<Long, List<Video>>>()
    private val lock = Mutex()
    private var builtAt = 0L
    private var builtFrom = ""

    fun init(ctx: Context) {
        relFile = File(Profiles.dir(ctx), "relacionados.json")
        topicFile = File(Profiles.dir(ctx), "temas.json")
        synchronized(related) { related.clear() }
        synchronized(topicCache) { topicCache.clear() }
        _topicRows.value = emptyMap(); builtAt = 0; builtFrom = ""
        runCatching {
            val o = JSONObject(relFile.readText())
            o.keys().forEach { k ->
                val e = o.getJSONObject(k)
                val a = e.getJSONArray("v")
                related[k] = e.optLong("at") to List(a.length()) { Video.fromJson(a.getJSONObject(it)) }
            }
        }
        runCatching {
            val o = JSONObject(topicFile.readText())
            o.keys().forEach { k ->
                val e = o.getJSONObject(k)
                val a = e.getJSONArray("v")
                topicCache[k] = e.optLong("at") to List(a.length()) { Video.fromJson(a.getJSONObject(it)) }
            }
            _topicRows.value = topicCache.mapValues { it.value.second }
        }
        compute()
    }

    /** Guarda los relacionados de un video que se acaba de abrir (los trae el reproductor gratis). */
    fun remember(videoId: String, list: List<Video>) {
        if (list.isEmpty()) return
        synchronized(related) {
            related.remove(videoId)
            related[videoId] = System.currentTimeMillis() to list.take(25)
            while (related.size > 80) related.remove(related.keys.first())
        }
        saveRelated()
    }

    private fun saveRelated() {
        runCatching {
            val o = JSONObject()
            synchronized(related) {
                related.forEach { (k, v) ->
                    val a = JSONArray(); v.second.forEach { a.put(it.toJson()) }
                    o.put(k, JSONObject().put("at", v.first).put("v", a))
                }
            }
            relFile.writeText(o.toString())
        }
    }

    /** Renueva todo: baja los relacionados que falten y vuelve a calcular. */
    suspend fun refresh(force: Boolean = false) { lock.withLock {
        val hist = Store.history.value.take(30)
        val sig = hist.take(12).joinToString { it.video.id } + Store.settingsTick.value
        if (!force && sig == builtFrom && System.currentTimeMillis() - builtAt < 30 * 60_000) return@withLock
        // Relacionados que falten de lo último que viste (máximo 8 por vez para no tardar)
        val missing = hist.take(15).map { it.video }.filter { v ->
            val r = synchronized(related) { related[v.id] }
            r == null || System.currentTimeMillis() - r.first > 3 * 86_400_000L
        }.take(if (lightMode) 2 else 4)
        // De uno en uno, en fondo, y nunca mientras se está viendo un video
        withContext(Net.background) {
            for (v in missing) {
                if (playing) break
                runCatching { Yt.related(Yt.stream(v.url)) }.getOrNull()?.let { remember(v.id, it) }
            }
        }
        compute()
        builtFrom = sig
        builtAt = System.currentTimeMillis()
        refreshTopics()
    } }

    /** Calcula «Para ti» y «Canales que quizá te gusten» con lo que ya está guardado. */
    fun compute() {
        val hist = Store.history.value
        if (hist.isEmpty()) { _forYou.value = emptyList(); _channels.value = emptyList(); return }
        val seen = hist.filter { it.finished || it.progress > 0.5f }.map { it.video.id }.toHashSet()
        val hidden = Store.hiddenVideos
        val now = System.currentTimeMillis()
        // Canales que más ves (por tiempo visto)
        val chanWeight = HashMap<String, Double>()
        hist.take(150).forEach { w ->
            if (w.video.channelUrl.isNotBlank()) chanWeight.add(Store.channelKey(w.video.channelUrl), w.progress.toDouble() + 0.2)
        }
        val maxChan = chanWeight.values.maxOrNull() ?: 1.0

        val score = HashMap<String, Double>()
        val byId = HashMap<String, Video>()
        val chanScore = HashMap<String, Double>()
        val chanInfo = HashMap<String, Video>()
        hist.take(30).forEachIndexed { idx, w ->
            val rel = synchronized(related) { related[w.video.id] }?.second ?: return@forEachIndexed
            // Lo reciente y lo que viste más completo pesa más
            val ageDays = ((now - w.at) / 86_400_000.0).coerceAtLeast(0.0)
            val weight = (0.4 + w.progress) * Math.pow(0.85, ageDays) * Math.pow(0.93, idx.toDouble())
            rel.forEachIndexed { pos, v ->
                if (v.id in seen || v.id in hidden || Store.isBlocked(v.channelUrl)) return@forEachIndexed
                if (Store.hideShorts && v.short) return@forEachIndexed
                val s = weight * (1.0 / (1 + pos * 0.15))
                score.add(v.id, s)
                byId[v.id] = v
                if (v.channelUrl.isNotBlank()) {
                    val k = Store.channelKey(v.channelUrl)
                    chanScore.add(k, s)
                    chanInfo.putIfMissing(k, v)
                }
            }
        }
        // Un poco más arriba lo de canales que ya te gustan, pero sin que llenen todo
        val ranked = score.entries.map { (id, s) ->
            val v = byId.getValue(id)
            val affinity = chanWeight[Store.channelKey(v.channelUrl)]?.let { it / maxChan } ?: 0.0
            v to s * (1 + 0.35 * affinity)
        }.sortedByDescending { it.second }
        val perChannel = HashMap<String, Int>()
        val out = ArrayList<Video>()
        for ((v, _) in ranked) {
            val k = Store.channelKey(v.channelUrl)
            val n = perChannel[k] ?: 0
            if (n >= 2) continue
            perChannel[k] = n + 1
            out += v
            if (out.size >= 60) break
        }
        _forYou.value = out

        // Canales nuevos: aparecen seguido en tus relacionados y no los sigues
        _channels.value = chanScore.entries
            .filter { (k, _) -> !Store.isSubscribed(k) && !Store.subs.value.any { Store.channelKey(it.url) == k } && k !in Store.blockedChannels }
            .sortedByDescending { it.value }
            .take(15)
            .mapNotNull { (k, s) ->
                val sample = chanInfo[k] ?: return@mapNotNull null
                Suggested(Chan(sample.channelUrl, sample.channel), s, sample)
            }
    }

    /**
     * «Recomendados de tus suscripciones»: lo nuevo de tus canales ordenado por lo que más ves de cada uno
     * y qué tan reciente es; sin lo que ya viste ni lo que dijiste que no te interesa.
     */
    fun fromSubs(feed: List<Video>): List<Video> {
        val hist = Store.history.value
        val seen = hist.filter { it.finished || it.progress > 0.5f }.map { it.video.id }.toHashSet()
        val chanWeight = HashMap<String, Double>()
        hist.take(200).forEach { w -> if (w.video.channelUrl.isNotBlank()) chanWeight.add(Store.channelKey(w.video.channelUrl), w.progress.toDouble() + 0.2) }
        val maxChan = chanWeight.values.maxOrNull() ?: 1.0
        val now = System.currentTimeMillis()
        val ranked = feed.asSequence()
            .filter { it.id !in seen && it.id !in Store.hiddenVideos && !Store.isBlocked(it.channelUrl) && !(Store.hideShorts && it.short) }
            .map { v ->
                val aff = (chanWeight[Store.channelKey(v.channelUrl)] ?: 0.0) / maxChan
                val ageDays = if (v.publishedMs > 0) ((now - v.publishedMs) / 86_400_000.0).coerceAtLeast(0.0) else 7.0
                v to (0.15 + aff) * Math.exp(-ageDays / 10.0)
            }
            .sortedByDescending { it.second }
        val perChannel = HashMap<String, Int>()
        val out = ArrayList<Video>()
        for ((v, _) in ranked) {
            val k = Store.channelKey(v.channelUrl)
            val n = perChannel[k] ?: 0
            if (n >= 3) continue
            perChannel[k] = n + 1
            out += v
            if (out.size >= 80) break
        }
        return out
    }

    private fun <K, V> HashMap<K, V>.putIfMissing(k: K, v: V) { if (!containsKey(k)) this[k] = v }
    private fun <K> HashMap<K, Double>.add(k: K, v: Double) { this[k] = (this[k] ?: 0.0) + v }

    /** Una fila por cada tema elegido (se renueva cada 3 horas). */
    suspend fun refreshTopics(force: Boolean = false) { withContext(Net.background) {
        val topics = Store.topics
        if (topics.isEmpty()) { _topicRows.value = emptyMap(); return@withContext }
        val sem = Semaphore(if (lightMode) 1 else 3)
        coroutineScope {
            topics.map { t ->
                async {
                    val c = topicCache[t]
                    if (!force && c != null && System.currentTimeMillis() - c.first < 3 * 3_600_000L) return@async
                    sem.withPermit {
                        runCatching { Yt.search(queryFor(t), onlyVideos = true).items }.getOrNull()
                            ?.filterIsInstance<Item.V>()?.map { it.v }
                            ?.filter { !it.short && !it.live }
                            ?.let { list ->
                                // Primero lo reciente (horas, días o semanas)
                                val fresh = list.filter { v -> Regex("hora|día|dia|semana|minuto", RegexOption.IGNORE_CASE).containsMatchIn(v.uploaded) }
                                val sorted = fresh + list.filterNot { it in fresh }
                                synchronized(topicCache) { topicCache[t] = System.currentTimeMillis() to sorted.take(25) }
                            }
                    }
                }
            }.awaitAll()
        }
        _topicRows.value = topics.associateWith { t -> synchronized(topicCache) { topicCache[t]?.second.orEmpty() } }
            .mapValues { (_, l) -> l.filter { it.id !in Store.hiddenVideos && !Store.isBlocked(it.channelUrl) } }
        runCatching {
            val o = JSONObject()
            synchronized(topicCache) {
                topicCache.forEach { (k, v) ->
                    val a = JSONArray(); v.second.forEach { a.put(it.toJson()) }
                    o.put(k, JSONObject().put("at", v.first).put("v", a))
                }
            }
            topicFile.writeText(o.toString())
        }
    } }

    /** Para aplicar al instante «No me interesa» y «No recomendar este canal». */
    fun applyDislikes() {
        val hidden = Store.hiddenVideos
        _forYou.value = _forYou.value.filter { it.id !in hidden && !Store.isBlocked(it.channelUrl) }
        _channels.value = _channels.value.filter { !Store.isBlocked(it.chan.url) }
        _topicRows.value = _topicRows.value.mapValues { (_, l) -> l.filter { it.id !in hidden && !Store.isBlocked(it.channelUrl) } }
    }
}
