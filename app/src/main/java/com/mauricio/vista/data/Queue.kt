package com.mauricio.vista.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * «A continuación»: la cola que arma la persona mientras ve (se reproduce en ese orden).
 * Si la cola está vacía, sigue con la fila de donde se abrió el video y luego con lo recomendado.
 */
object Queue {
    private val _items = MutableStateFlow<List<Video>>(emptyList())
    val items: StateFlow<List<Video>> get() = _items

    /** "off" | "one" (repetir este video) | "all" (repetir la cola). */
    private val _repeat = MutableStateFlow("off")
    val repeat: StateFlow<String> get() = _repeat

    /** La fila de donde se abrió el video (búsqueda, canal, lista…): respaldo cuando la cola está vacía. */
    @Volatile var context: List<Video> = emptyList()

    private var file: File? = null

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "cola.json")
        runCatching {
            val o = JSONObject(file!!.readText())
            val a = o.getJSONArray("v")
            _items.value = List(a.length()) { Video.fromJson(a.getJSONObject(it)) }
            _repeat.value = o.optString("r", "off")
        }
    }

    private fun save() {
        runCatching {
            val a = JSONArray(); _items.value.forEach { a.put(it.toJson()) }
            file?.writeText(JSONObject().put("v", a).put("r", _repeat.value).toString())
        }
    }

    private fun set(list: List<Video>) { _items.value = list; save() }

    /** Al final de la cola. */
    fun add(v: Video) { set(_items.value.filterNot { it.id == v.id } + v) }

    /** Lo siguiente que se ve. */
    fun playNext(v: Video) { set(listOf(v) + _items.value.filterNot { it.id == v.id }) }

    fun addAll(list: List<Video>) { val have = _items.value.map { it.id }.toHashSet(); set(_items.value + list.filter { have.add(it.id) }) }

    fun remove(id: String) { set(_items.value.filterNot { it.id == id }) }

    fun has(id: String) = _items.value.any { it.id == id }

    fun move(id: String, delta: Int) {
        val l = _items.value.toMutableList()
        val i = l.indexOfFirst { it.id == id }
        if (i < 0) return
        val j = (i + delta).coerceIn(0, l.lastIndex)
        if (i == j) return
        val x = l.removeAt(i); l.add(j, x)
        set(l)
    }

    fun clear() = set(emptyList())

    fun shuffle() = set(_items.value.shuffled())

    fun cycleRepeat(): String {
        _repeat.value = when (_repeat.value) { "off" -> "all"; "all" -> "one"; else -> "off" }
        save()
        return _repeat.value
    }

    /** Qué sigue después de [current]: cola, luego la fila de origen, luego lo recomendado que no hayas visto. */
    fun peekNext(current: Video, related: List<Video>): Video? =
        _items.value.firstOrNull { it.id != current.id }
            ?: context.firstOrNull { it.id != current.id }
            ?: related.firstOrNull { r -> r.id != current.id && Store.watched(r.id)?.finished != true && !Store.isBlocked(r.channelUrl) && r.id !in Store.hiddenVideos }

    /** Avanza: quita de la cola (o de la fila) el video que va a empezar. Con «repetir la cola», el que terminó se va al final. */
    fun advance(finished: Video?, next: Video) {
        if (finished != null && _repeat.value == "all" && finished.title.isNotBlank()) {
            _items.value = _items.value.filterNot { it.id == finished.id } + finished
        }
        if (_items.value.any { it.id == next.id }) set(_items.value.filterNot { it.id == next.id })
        else {
            save()
            val i = context.indexOfFirst { it.id == next.id }
            if (i >= 0) context = context.drop(i + 1)
        }
    }

    /** Hay una cola armada a mano (entonces se sigue aunque esté apagado «siguiente automático»). */
    val manual: Boolean get() = _items.value.isNotEmpty()
}

/** Listas guardadas por la persona («Para el gym», «Recetas»…). */
object Playlists {
    data class PL(val id: String, val name: String, val videos: List<Video>, val at: Long = System.currentTimeMillis()) {
        fun toJson(): JSONObject {
            val a = JSONArray(); videos.forEach { a.put(it.toJson()) }
            return JSONObject().put("id", id).put("n", name).put("v", a).put("at", at)
        }
        companion object {
            fun fromJson(o: JSONObject): PL {
                val a = o.getJSONArray("v")
                return PL(o.getString("id"), o.optString("n"), List(a.length()) { Video.fromJson(a.getJSONObject(it)) }, o.optLong("at"))
            }
        }
    }

    private val _lists = MutableStateFlow<List<PL>>(emptyList())
    val lists: StateFlow<List<PL>> get() = _lists
    private var file: File? = null

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "listas.json")
        _lists.value = runCatching {
            val a = JSONArray(file!!.readText())
            List(a.length()) { PL.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
    }

    private fun set(l: List<PL>) {
        _lists.value = l
        runCatching { val a = JSONArray(); l.forEach { a.put(it.toJson()) }; file?.writeText(a.toString()) }
    }

    fun get(id: String) = _lists.value.firstOrNull { it.id == id }

    fun create(name: String, first: List<Video> = emptyList()): PL {
        val pl = PL(System.currentTimeMillis().toString(36), name.trim().ifBlank { "Mi lista" }, first.distinctBy { it.id })
        set(listOf(pl) + _lists.value)
        return pl
    }

    fun add(id: String, v: Video) = update(id) { it.copy(videos = it.videos.filterNot { x -> x.id == v.id } + v) }
    fun addAll(id: String, list: List<Video>) = update(id) { pl -> val have = pl.videos.map { it.id }.toHashSet(); pl.copy(videos = pl.videos + list.filter { have.add(it.id) }) }
    fun removeVideo(id: String, videoId: String) = update(id) { it.copy(videos = it.videos.filterNot { x -> x.id == videoId }) }
    fun rename(id: String, name: String) = update(id) { it.copy(name = name.trim().ifBlank { it.name }) }
    fun move(id: String, videoId: String, delta: Int) = update(id) { pl ->
        val l = pl.videos.toMutableList(); val i = l.indexOfFirst { it.id == videoId }
        if (i < 0) pl else { val j = (i + delta).coerceIn(0, l.lastIndex); val x = l.removeAt(i); l.add(j, x); pl.copy(videos = l) }
    }
    fun delete(id: String) = set(_lists.value.filterNot { it.id == id })

    private fun update(id: String, f: (PL) -> PL) = set(_lists.value.map { if (it.id == id) f(it) else it })

    fun contains(id: String, videoId: String) = get(id)?.videos?.any { it.id == videoId } == true

    /** Para pasar la cuenta a otro aparato. */
    fun toJson(): JSONArray = JSONArray().also { a -> _lists.value.forEach { a.put(it.toJson()) } }

    fun merge(a: JSONArray) {
        val incoming = List(a.length()) { PL.fromJson(a.getJSONObject(it)) }
        val byName = _lists.value.associateBy { it.name.lowercase() }.toMutableMap()
        incoming.forEach { pl ->
            val mine = byName[pl.name.lowercase()]
            if (mine == null) byName[pl.name.lowercase()] = pl
            else { val have = mine.videos.map { it.id }.toHashSet(); byName[pl.name.lowercase()] = mine.copy(videos = mine.videos + pl.videos.filter { have.add(it.id) }) }
        }
        set(byName.values.sortedByDescending { it.at })
    }
}
