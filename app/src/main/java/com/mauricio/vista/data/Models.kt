package com.mauricio.vista.data

import org.json.JSONObject

/** Un video (de búsqueda, de un canal, de tus suscripciones, del historial…). */
data class Video(
    val url: String,
    val title: String,
    val channel: String = "",
    val channelUrl: String = "",
    val thumb: String = "",
    /** Duración en segundos (0 = desconocida, -1 = en vivo). */
    val duration: Long = 0,
    val views: Long = -1,
    /** Texto como «hace 2 días». */
    val uploaded: String = "",
    /** Fecha exacta (ms) cuando se sabe; sirve para ordenar lo nuevo. */
    val publishedMs: Long = 0,
    val live: Boolean = false,
    val short: Boolean = false,
) {
    val id: String get() = Yt.videoId(url) ?: url

    /** Miniatura en buena calidad aunque no venga en la lista. */
    val thumbHq: String get() = thumb.ifBlank { "https://i.ytimg.com/vi/$id/hqdefault.jpg" }
    val thumbMax: String get() = "https://i.ytimg.com/vi/$id/maxresdefault.jpg"

    fun toJson(): JSONObject = JSONObject()
        .put("u", url).put("t", title).put("c", channel).put("cu", channelUrl).put("th", thumb)
        .put("d", duration).put("v", views).put("up", uploaded).put("p", publishedMs).put("l", live).put("s", short)

    companion object {
        fun fromJson(o: JSONObject) = Video(
            url = o.optString("u"), title = o.optString("t"), channel = o.optString("c"), channelUrl = o.optString("cu"),
            thumb = o.optString("th"), duration = o.optLong("d"), views = o.optLong("v", -1), uploaded = o.optString("up"),
            publishedMs = o.optLong("p"), live = o.optBoolean("l"), short = o.optBoolean("s"),
        )
    }
}

/** Un canal. */
data class Chan(
    val url: String,
    val name: String,
    val avatar: String = "",
    val subs: Long = -1,
) {
    /** El identificador UC… (lo necesita la lista de novedades). */
    val channelId: String? get() = Regex("/channel/(UC[\\w-]{22})").find(url)?.groupValues?.get(1)

    fun toJson(): JSONObject = JSONObject().put("u", url).put("n", name).put("a", avatar).put("s", subs)

    companion object {
        fun fromJson(o: JSONObject) = Chan(o.optString("u"), o.optString("n"), o.optString("a"), o.optLong("s", -1))
    }
}

/** Lo que se vio: para «Continuar viendo» e historial. */
data class Watched(val video: Video, val posMs: Long, val durMs: Long, val at: Long) {
    val progress: Float get() = if (durMs > 0) (posMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f
    val finished: Boolean get() = durMs > 0 && (posMs >= durMs - 20_000 || progress > 0.95f)

    fun toJson(): JSONObject = JSONObject().put("v", video.toJson()).put("p", posMs).put("d", durMs).put("a", at)

    companion object {
        fun fromJson(o: JSONObject) = Watched(Video.fromJson(o.getJSONObject("v")), o.optLong("p"), o.optLong("d"), o.optLong("a"))
    }
}

/** Resultado de búsqueda: videos y canales mezclados. */
sealed interface Item {
    data class V(val v: Video) : Item
    data class C(val c: Chan) : Item
    /** Lista de reproducción de YouTube. */
    data class P(val url: String, val name: String, val thumb: String, val count: Long, val owner: String) : Item
}

/** Página de resultados con la llave para pedir más. */
class Paged<T>(val items: List<T>, val more: (suspend () -> Paged<T>)?)
