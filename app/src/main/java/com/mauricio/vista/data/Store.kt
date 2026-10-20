package com.mauricio.vista.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray

/**
 * Todo lo que Vista recuerda, guardado en el aparato: suscripciones, historial,
 * «Ver después», búsquedas recientes y ajustes. No hay cuenta ni nube.
 */
object Store {
    private lateinit var sp: SharedPreferences

    private val _subs = MutableStateFlow<List<Chan>>(emptyList())
    val subs: StateFlow<List<Chan>> get() = _subs

    private val _history = MutableStateFlow<List<Watched>>(emptyList())
    val history: StateFlow<List<Watched>> get() = _history

    private val _later = MutableStateFlow<List<Video>>(emptyList())
    val later: StateFlow<List<Video>> get() = _later

    private val _searches = MutableStateFlow<List<String>>(emptyList())
    val searches: StateFlow<List<String>> get() = _searches

    /** Cambia cada vez que se toca un ajuste (para refrescar pantallas). */
    val settingsTick = MutableStateFlow(0)

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("vista", Context.MODE_PRIVATE)
        _subs.value = readList("subs") { Chan.fromJson(it) }
        _history.value = readList("history") { Watched.fromJson(it) }
        _later.value = readList("later") { Video.fromJson(it) }
        _searches.value = runCatching { JSONArray(sp.getString("searches", "[]")).let { a -> List(a.length()) { a.getString(it) } } }.getOrDefault(emptyList())
    }

    private fun <T> readList(key: String, f: (org.json.JSONObject) -> T): List<T> = runCatching {
        val a = JSONArray(sp.getString(key, "[]"))
        List(a.length()) { f(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    private fun writeArr(key: String, list: List<org.json.JSONObject>) {
        val a = JSONArray(); list.forEach { a.put(it) }
        sp.edit().putString(key, a.toString()).apply()
    }

    // ------------------------------------------------------------------
    // Suscripciones
    // ------------------------------------------------------------------
    fun isSubscribed(url: String): Boolean = key(url).let { k -> _subs.value.any { key(it.url) == k } }

    private fun key(url: String) = Regex("(UC[\\w-]{22})").find(url)?.value ?: url.trimEnd('/').lowercase()

    fun subscribe(c: Chan) {
        if (isSubscribed(c.url)) return
        _subs.value = (_subs.value + c).sortedBy { it.name.lowercase() }
        writeArr("subs", _subs.value.map { it.toJson() })
        Feed.invalidate()
    }

    fun unsubscribe(url: String) {
        val k = key(url)
        _subs.value = _subs.value.filterNot { key(it.url) == k }
        writeArr("subs", _subs.value.map { it.toJson() })
        Feed.invalidate()
    }

    /** Agrega muchas de golpe (al importar). Devuelve cuántas eran nuevas. */
    fun subscribeAll(list: List<Chan>): Int {
        val have = _subs.value.map { key(it.url) }.toHashSet()
        val nuevos = list.filter { have.add(key(it.url)) }
        if (nuevos.isEmpty()) return 0
        _subs.value = (_subs.value + nuevos).sortedBy { it.name.lowercase() }
        writeArr("subs", _subs.value.map { it.toJson() })
        Feed.invalidate()
        return nuevos.size
    }

    /** Actualiza foto o nombre de un canal guardado (cuando se visita). */
    fun refreshChan(c: Chan) {
        val k = key(c.url)
        if (_subs.value.none { key(it.url) == k }) return
        _subs.value = _subs.value.map { if (key(it.url) == k) it.copy(name = c.name.ifBlank { it.name }, avatar = c.avatar.ifBlank { it.avatar }, subs = c.subs) else it }
        writeArr("subs", _subs.value.map { it.toJson() })
    }

    // ------------------------------------------------------------------
    // Historial y «Continuar viendo»
    // ------------------------------------------------------------------
    fun watched(id: String): Watched? = _history.value.firstOrNull { it.video.id == id }

    fun saveProgress(v: Video, posMs: Long, durMs: Long) {
        if (v.live) return
        val w = Watched(v, posMs, durMs, System.currentTimeMillis())
        _history.value = (listOf(w) + _history.value.filterNot { it.video.id == v.id }).take(400)
        writeArr("history", _history.value.map { it.toJson() })
    }

    fun removeHistory(id: String) {
        _history.value = _history.value.filterNot { it.video.id == id }
        writeArr("history", _history.value.map { it.toJson() })
    }

    fun clearHistory() {
        _history.value = emptyList()
        writeArr("history", emptyList())
    }

    /** Videos empezados y no terminados. */
    fun continueWatching(): List<Watched> = _history.value.filter { !it.finished && it.posMs > 15_000 }.take(20)

    // ------------------------------------------------------------------
    // Ver después
    // ------------------------------------------------------------------
    fun isLater(id: String) = _later.value.any { it.id == id }

    fun toggleLater(v: Video): Boolean {
        val now = !isLater(v.id)
        _later.value = if (now) listOf(v) + _later.value else _later.value.filterNot { it.id == v.id }
        writeArr("later", _later.value.map { it.toJson() })
        return now
    }

    // ------------------------------------------------------------------
    // Búsquedas recientes
    // ------------------------------------------------------------------
    fun addSearch(q: String) {
        val t = q.trim()
        if (t.isBlank()) return
        _searches.value = (listOf(t) + _searches.value.filterNot { it.equals(t, true) }).take(20)
        sp.edit().putString("searches", JSONArray(_searches.value).toString()).apply()
    }

    fun clearSearches() {
        _searches.value = emptyList()
        sp.edit().putString("searches", "[]").apply()
    }

    // ------------------------------------------------------------------
    // Ajustes
    // ------------------------------------------------------------------
    private fun tick() { settingsTick.value++ }

    /** Calidad máxima en Wi-Fi y en la tele (altura en píxeles). */
    var qualityWifi: Int
        get() = sp.getInt("qWifi", 1080)
        set(v) { sp.edit().putInt("qWifi", v).apply(); tick() }

    /** Calidad máxima con datos del celular. */
    var qualityData: Int
        get() = sp.getInt("qData", 480)
        set(v) { sp.edit().putInt("qData", v).apply(); tick() }

    var speed: Float
        get() = sp.getFloat("speed", 1f)
        set(v) { sp.edit().putFloat("speed", v).apply(); tick() }

    var autoplay: Boolean
        get() = sp.getBoolean("autoplay", true)
        set(v) { sp.edit().putBoolean("autoplay", v).apply(); tick() }

    var hideShorts: Boolean
        get() = sp.getBoolean("hideShorts", true)
        set(v) { sp.edit().putBoolean("hideShorts", v).apply(); tick() }

    var sponsorBlock: Boolean
        get() = sp.getBoolean("sb", true)
        set(v) { sp.edit().putBoolean("sb", v).apply(); tick() }

    /** Qué se salta: patrocinios, autopromoción, «suscríbete», intro, cierre, música sin contenido. */
    var sbCategories: Set<String>
        get() = sp.getStringSet("sbCats", null) ?: setOf("sponsor", "selfpromo", "interaction")
        set(v) { sp.edit().putStringSet("sbCats", v).apply(); tick() }

    /** "" = apagados; "es", "en"… */
    var subtitles: String
        get() = sp.getString("subs_lang", "") ?: ""
        set(v) { sp.edit().putString("subs_lang", v).apply(); tick() }

    /** Tamaño de subtítulos: 1 normal, 1.5 grande, 2 muy grande. */
    var subtitleScale: Float
        get() = sp.getFloat("subs_scale", 1.3f)
        set(v) { sp.edit().putFloat("subs_scale", v).apply(); tick() }

    // ------------------------------------------------------------------
    // Otros aparatos de la casa conectados (tele, tableta, otro celular)
    // ------------------------------------------------------------------
    data class Device(val host: String, val name: String, val tv: Boolean)

    val devices: List<Device>
        get() = runCatching {
            val a = JSONArray(sp.getString("devices", "[]"))
            List(a.length()) { a.getJSONObject(it).let { o -> Device(o.getString("h"), o.optString("n"), o.optBoolean("tv")) } }
        }.getOrDefault(emptyList()).ifEmpty {
            // Versión anterior: solo se guardaba la tele
            (sp.getString("tv", "") ?: "").takeIf { it.isNotBlank() }?.let { listOf(Device(it, "Tele", true)) } ?: emptyList()
        }

    fun addDevice(d: Device) {
        val list = listOf(d) + devices.filterNot { it.host == d.host || (d.name.isNotBlank() && it.name == d.name) }
        val a = JSONArray(); list.forEach { a.put(org.json.JSONObject().put("h", it.host).put("n", it.name).put("tv", it.tv)) }
        sp.edit().putString("devices", a.toString()).remove("tv").apply(); tick()
    }

    fun removeDevice(host: String) {
        val list = devices.filterNot { it.host == host }
        val a = JSONArray(); list.forEach { a.put(org.json.JSONObject().put("h", it.host).put("n", it.name).put("tv", it.tv)) }
        sp.edit().putString("devices", a.toString()).remove("tv").apply(); tick()
    }

    /** La tele para «Ver en la tele» (o el primer aparato conectado). */
    val tvAddress: String get() = devices.let { l -> (l.firstOrNull { it.tv } ?: l.firstOrNull())?.host.orEmpty() }

    /** "auto", "dark" o "light". */
    var theme: String
        get() = sp.getString("theme", "") ?: ""
        set(v) { sp.edit().putString("theme", v).apply(); tick() }

    // ------------------------------------------------------------------
    // Pasar la cuenta completa a otro aparato
    // ------------------------------------------------------------------
    fun exportAccount(): org.json.JSONObject {
        val subsA = JSONArray(); _subs.value.forEach { subsA.put(it.toJson()) }
        val laterA = JSONArray(); _later.value.forEach { laterA.put(it.toJson()) }
        val histA = JSONArray(); _history.value.take(300).forEach { histA.put(it.toJson()) }
        return org.json.JSONObject().put("vista", 2).put("subs", subsA).put("later", laterA).put("history", histA)
            .put("topics", JSONArray(topics)).put("hidden", JSONArray(hiddenVideos.toList())).put("blocked", JSONArray(blockedChannels.toList()))
            .put("qWifi", qualityWifi).put("qData", qualityData).put("speed", speed.toDouble()).put("sb", sponsorBlock)
            .put("sbCats", JSONArray(sbCategories.toList())).put("subs_lang", subtitles).put("hideShorts", hideShorts).put("autoplay", autoplay)
    }

    /** Junta la cuenta que llegó con lo que ya había aquí (no borra nada). Devuelve un resumen. */
    fun importAccount(o: org.json.JSONObject): String {
        fun strs(k: String) = o.optJSONArray(k)?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty()
        val subsIn = o.optJSONArray("subs")?.let { a -> List(a.length()) { Chan.fromJson(a.getJSONObject(it)) } }.orEmpty()
        val added = subscribeAll(subsIn)
        o.optJSONArray("later")?.let { a ->
            val inc = List(a.length()) { Video.fromJson(a.getJSONObject(it)) }
            val merged = (_later.value + inc).distinctBy { it.id }
            _later.value = merged; writeArr("later", merged.map { it.toJson() })
        }
        var histN = 0
        o.optJSONArray("history")?.let { a ->
            val inc = List(a.length()) { Watched.fromJson(a.getJSONObject(it)) }
            histN = inc.size
            val merged = (_history.value + inc).groupBy { it.video.id }.values.map { g -> g.maxBy { it.at } }.sortedByDescending { it.at }.take(400)
            _history.value = merged; writeArr("history", merged.map { it.toJson() })
        }
        val t = strs("topics")
        if (t.isNotEmpty()) topics = (topics + t).distinct()
        val e = sp.edit()
        strs("hidden").takeIf { it.isNotEmpty() }?.let { e.putStringSet("hiddenV", hiddenVideos + it) }
        strs("blocked").takeIf { it.isNotEmpty() }?.let { e.putStringSet("blockedC", blockedChannels + it) }
        if (o.has("qWifi")) e.putInt("qWifi", o.optInt("qWifi", 1080))
        if (o.has("qData")) e.putInt("qData", o.optInt("qData", 480))
        if (o.has("speed")) e.putFloat("speed", o.optDouble("speed", 1.0).toFloat())
        if (o.has("sb")) e.putBoolean("sb", o.optBoolean("sb", true))
        strs("sbCats").takeIf { it.isNotEmpty() }?.let { e.putStringSet("sbCats", it.toSet()) }
        if (o.has("subs_lang")) e.putString("subs_lang", o.optString("subs_lang"))
        if (o.has("hideShorts")) e.putBoolean("hideShorts", o.optBoolean("hideShorts", true))
        if (o.has("autoplay")) e.putBoolean("autoplay", o.optBoolean("autoplay", true))
        e.apply(); tick()
        return "$added canales nuevos, ${t.size} temas e historial de $histN videos"
    }

    // ------------------------------------------------------------------
    // Gustos (para las recomendaciones)
    // ------------------------------------------------------------------
    /** Temas que eligió la persona, en orden. */
    var topics: List<String>
        get() = runCatching { JSONArray(sp.getString("topics", "[]")).let { a -> List(a.length()) { a.getString(it) } } }.getOrDefault(emptyList())
        set(v) { sp.edit().putString("topics", JSONArray(v).toString()).putBoolean("topicsAsked", true).apply(); tick() }

    /** ¿Ya se preguntaron los temas la primera vez? */
    var topicsAsked: Boolean
        get() = sp.getBoolean("topicsAsked", false)
        set(v) { sp.edit().putBoolean("topicsAsked", v).apply(); tick() }

    /** Videos marcados con «No me interesa». */
    val hiddenVideos: Set<String> get() = sp.getStringSet("hiddenV", null) ?: emptySet()
    fun hideVideo(id: String) { sp.edit().putStringSet("hiddenV", (hiddenVideos + id).toList().takeLast(500).toSet()).apply(); tick() }

    /** Canales que no se deben recomendar (llave UC… o liga). */
    val blockedChannels: Set<String> get() = sp.getStringSet("blockedC", null) ?: emptySet()
    fun blockChannel(url: String) { sp.edit().putStringSet("blockedC", blockedChannels + key(url)).apply(); tick() }
    fun unblockAll() { sp.edit().remove("blockedC").remove("hiddenV").apply(); tick() }
    fun isBlocked(url: String) = url.isNotBlank() && key(url) in blockedChannels
    fun channelKey(url: String) = key(url)

    /** Pasada esta hora, la pantalla deja de reproducir (temporizador). 0 = apagado. */
    var sleepAt: Long = 0
}
