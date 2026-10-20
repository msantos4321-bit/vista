package com.mauricio.vista.data

import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PaidContentException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.kiosk.KioskInfo
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.util.concurrent.ConcurrentHashMap

/** Todo lo que se le pide a YouTube pasa por aquí (sin cuenta y sin anuncios). */
object Yt {
    private val yt get() = ServiceList.YouTube

    fun init() {
        NewPipe.init(YtDownloader(), Localization("es", "MX"), ContentCountry("MX"))
    }

    // ------------------------------------------------------------------
    // Identificadores y conversiones
    // ------------------------------------------------------------------
    private val idRx = Regex("(?:v=|/shorts/|youtu\\.be/|/embed/|/live/|/v/)([\\w-]{11})")

    fun videoId(url: String): String? = idRx.find(url)?.groupValues?.get(1)
        ?: url.takeIf { it.length == 11 && it.all { c -> c.isLetterOrDigit() || c == '-' || c == '_' } }

    fun watchUrl(id: String) = "https://www.youtube.com/watch?v=$id"

    /** Busca una liga de YouTube dentro de un texto (por ejemplo, al compartir desde otra app). */
    fun findLink(text: String): String? {
        val m = Regex("https?://\\S+").findAll(text).map { it.value.trimEnd('.', ',', ')') }
        return m.firstOrNull { videoId(it) != null }?.let { watchUrl(videoId(it)!!) }
    }

    fun bestImage(list: List<Image>?, maxH: Int = 720): String {
        if (list.isNullOrEmpty()) return ""
        val known = list.filter { it.height > 0 }
        val pick = known.filter { it.height <= maxH }.maxByOrNull { it.height }
            ?: known.minByOrNull { it.height }
            ?: list.last()
        return pick.url.let { if (it.startsWith("//")) "https:$it" else it }
    }

    fun toVideo(i: StreamInfoItem): Video {
        val live = i.streamType == StreamType.LIVE_STREAM || i.streamType == StreamType.AUDIO_LIVE_STREAM
        val date = runCatching { i.uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli() }.getOrNull() ?: 0L
        return Video(
            url = i.url, title = i.name.orEmpty(), channel = i.uploaderName.orEmpty(), channelUrl = i.uploaderUrl.orEmpty(),
            thumb = bestImage(i.thumbnails, 480), duration = if (live) -1 else i.duration, views = i.viewCount,
            uploaded = i.textualUploadDate.orEmpty(), publishedMs = date, live = live,
            short = runCatching { i.isShortFormContent }.getOrDefault(false) || i.url.contains("/shorts/"),
        )
    }

    fun toChan(i: ChannelInfoItem) = Chan(i.url, i.name.orEmpty(), bestImage(i.thumbnails, 240), i.subscriberCount)

    private fun toItems(list: List<InfoItem>): List<Item> = list.mapNotNull {
        when (it) {
            is StreamInfoItem -> Item.V(toVideo(it))
            is ChannelInfoItem -> Item.C(toChan(it))
            else -> null
        }
    }

    // ------------------------------------------------------------------
    // Búsqueda
    // ------------------------------------------------------------------
    suspend fun search(q: String, onlyVideos: Boolean = false): Paged<Item> = withContext(Net.io()) {
        val filters = if (onlyVideos) listOf(YoutubeSearchQueryHandlerFactory.VIDEOS) else emptyList()
        val qh = yt.searchQHFactory.fromQuery(q, filters, "")
        val info = SearchInfo.getInfo(yt, qh)
        fun next(page: Page?): (suspend () -> Paged<Item>)? = if (!Page.isValid(page)) null else suspend {
            withContext(Net.io()) {
                val p = SearchInfo.getMoreItems(yt, qh, page)
                Paged(toItems(p.items), next(p.nextPage))
            }
        }
        Paged(toItems(info.relatedItems), next(info.nextPage))
    }

    suspend fun suggestions(q: String): List<String> = withContext(Net.io()) {
        if (q.isBlank()) emptyList() else runCatching { yt.suggestionExtractor.suggestionList(q).take(8) }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------------
    // Videos
    // ------------------------------------------------------------------
    private class Cached(val info: StreamInfo, val at: Long)
    private val streamCache = ConcurrentHashMap<String, Cached>()
    private val loadScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
    private val inflight = ConcurrentHashMap<String, kotlinx.coroutines.Deferred<StreamInfo>>()

    /**
     * Datos completos del video (calidades, subtítulos, capítulos, relacionados). Se guardan 1 h.
     * Si ya se está cargando (por ejemplo, porque se adelantó al enfocar la tarjeta), se espera esa misma carga.
     */
    suspend fun stream(url: String, fresh: Boolean = false): StreamInfo {
        val key = videoId(url) ?: url
        val c = streamCache[key]
        if (!fresh && c != null && System.currentTimeMillis() - c.at < 60 * 60_000) return c.info
        val running = inflight[key]?.takeIf { !fresh && it.isActive }
        val job = running ?: loadScope.async(Net.io()) {
            val info = StreamInfo.getInfo(yt, watchUrl(key))
            if (streamCache.size > 40) streamCache.clear()
            streamCache[key] = Cached(info, System.currentTimeMillis())
            info
        }.also { d -> inflight[key] = d; d.invokeOnCompletion { inflight.remove(key, d) } }
        return job.await()
    }

    private var prefetchJob: kotlinx.coroutines.Job? = null

    /** Adelanta la carga de un video (en la tele, cuando una tarjeta se queda enfocada). */
    fun prefetch(url: String) {
        val key = videoId(url) ?: return
        if (streamCache.containsKey(key) || inflight.containsKey(key)) return
        prefetchJob?.cancel()
        prefetchJob = loadScope.launch(Net.background) { runCatching { stream(url) } }
    }

    fun related(info: StreamInfo): List<Video> = info.relatedItems.filterIsInstance<StreamInfoItem>().map { toVideo(it) }

    // ------------------------------------------------------------------
    // Canales
    // ------------------------------------------------------------------
    class ChannelPage(val chan: Chan, val banner: String, val description: String, val videos: Paged<Video>)

    suspend fun channel(url: String): ChannelPage = withContext(Net.io()) {
        val info = ChannelInfo.getInfo(yt, url)
        val chan = Chan(info.url, info.name.orEmpty(), bestImage(info.avatars, 240), info.subscriberCount)
        val tab: ListLinkHandler? = info.tabs.firstOrNull { it.contentFilters.contains(ChannelTabs.VIDEOS) }
            ?: info.tabs.firstOrNull { it.contentFilters.contains(ChannelTabs.LIVESTREAMS) }
        val videos = if (tab == null) Paged(emptyList(), null) else {
            val t = ChannelTabInfo.getInfo(yt, tab)
            fun next(page: Page?): (suspend () -> Paged<Video>)? = if (!Page.isValid(page)) null else suspend {
                withContext(Net.io()) {
                    val p = ChannelTabInfo.getMoreItems(yt, tab, page!!)
                    Paged(p.items.filterIsInstance<StreamInfoItem>().map { toVideo(it).copy(channel = chan.name, channelUrl = chan.url) }, next(p.nextPage))
                }
            }
            Paged(t.relatedItems.filterIsInstance<StreamInfoItem>().map { toVideo(it).copy(channel = chan.name, channelUrl = chan.url) }, next(t.nextPage))
        }
        ChannelPage(chan, bestImage(info.banners, 1080), info.description.orEmpty(), videos)
    }

    /** Convierte cualquier liga de canal (@nombre, /c/…, /user/…) a la forma /channel/UC…. */
    suspend fun resolveChannel(url: String): Chan? = withContext(Net.io()) {
        runCatching {
            val info = ChannelInfo.getInfo(yt, url)
            Chan("https://www.youtube.com/channel/${info.id}", info.name.orEmpty(), bestImage(info.avatars, 240), info.subscriberCount)
        }.getOrNull()
    }

    // ------------------------------------------------------------------
    // Tendencias y en vivo
    // ------------------------------------------------------------------
    suspend fun kiosk(id: String): List<Video> = withContext(Net.io()) {
        val ex = yt.kioskList.getExtractorById(id, null)
        ex.fetchPage()
        val info = KioskInfo.getInfo(ex)
        info.relatedItems.map { toVideo(it) }
    }

    // ------------------------------------------------------------------
    // Errores en palabras normales
    // ------------------------------------------------------------------
    fun friendly(e: Throwable): String {
        var c: Throwable? = e
        while (c != null) {
            when (c) {
                is AgeRestrictedContentException -> return "Este video tiene restricción de edad y YouTube pide iniciar sesión para verlo."
                is PrivateContentException -> return "Este video es privado."
                is PaidContentException -> return "Este video es de paga o solo para miembros del canal."
                is GeographicRestrictionException -> return "Este video no está disponible en México."
                is SignInConfirmNotBotException, is ReCaptchaException -> return "YouTube está pidiendo verificación a esta red. Espera unos minutos e inténtalo de nuevo."
                is ContentNotAvailableException -> return "Este video ya no está disponible."
                is java.net.UnknownHostException, is java.net.SocketTimeoutException, is java.net.ConnectException -> return "No hay conexión a internet."
            }
            c = c.cause
        }
        return "No se pudo cargar (${e.javaClass.simpleName}). Si sigue pasando, puede que YouTube haya cambiado algo y haga falta actualizar Vista."
    }
}
