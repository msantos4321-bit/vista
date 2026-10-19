package com.mauricio.vista.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * SponsorBlock: base de datos pública y gratuita donde la gente marca los patrocinios,
 * intros y «suscríbete» dentro de los videos. Vista los salta solo.
 */
object SponsorBlock {
    data class Segment(val startMs: Long, val endMs: Long, val category: String)

    val names = linkedMapOf(
        "sponsor" to "Patrocinio",
        "selfpromo" to "Autopromoción",
        "interaction" to "«Suscríbete y dale like»",
        "intro" to "Intro",
        "outro" to "Cierre y despedida",
        "music_offtopic" to "Partes sin música (en videos musicales)",
        "preview" to "Avance de lo que viene",
        "filler" to "Relleno fuera de tema",
    )

    fun label(cat: String) = names[cat] ?: cat

    suspend fun segments(videoId: String): List<Segment> = withContext(Dispatchers.IO) {
        if (!Store.sponsorBlock) return@withContext emptyList()
        val cats = Store.sbCategories.ifEmpty { return@withContext emptyList() }
        val catParam = JSONArray(cats.toList()).toString()
        val url = "https://sponsor.ajay.app/api/skipSegments?videoID=$videoId&categories=" + java.net.URLEncoder.encode(catParam, "UTF-8")
        runCatching {
            val a = JSONArray(Net.get(url, Net.quick))
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                val seg = o.getJSONArray("segment")
                Segment((seg.getDouble(0) * 1000).toLong(), (seg.getDouble(1) * 1000).toLong(), o.optString("category"))
            }.filter { it.endMs - it.startMs > 1000 }.sortedBy { it.startMs }
        }.getOrDefault(emptyList()) // 404 = el video no tiene partes marcadas
    }
}
