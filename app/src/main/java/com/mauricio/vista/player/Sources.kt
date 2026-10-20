package com.mauricio.vista.player

import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.dash.DashMediaSource
import androidx.media3.exoplayer.dash.DefaultDashChunkSource
import androidx.media3.exoplayer.dash.manifest.DashManifestParser
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleExtractor
import com.mauricio.vista.data.Store
import com.mauricio.vista.ui.DeviceUi
import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeProgressiveDashManifestCreator
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType
import org.schabi.newpipe.extractor.stream.SubtitlesStream
import org.schabi.newpipe.extractor.stream.VideoStream
import java.io.ByteArrayInputStream

/** Arma lo que el reproductor necesita a partir de los datos del video. */
@UnstableApi
object Sources {
    class Built(
        val source: MediaSource,
        /** Altura de la calidad que se eligió (0 = solo audio / en vivo). */
        val height: Int,
        val isLive: Boolean,
        /** La fuente trae varias calidades y el reproductor elige (HLS): hay que ponerle tope. */
        val adaptive: Boolean = false,
        /** Para diagnosticar: qué camino se usó. */
        val how: String = "",
    )

    private val dsf by lazy { YtDataSource.Factory() }
    /** HLS: pedidos normales (GET con Range), sin los parámetros especiales de los archivos sueltos. */
    private val hlsDsf by lazy { YtDataSource.Factory(paramMode = false) }
    private val dash by lazy { DashMediaSource.Factory(DefaultDashChunkSource.Factory(dsf), dsf) }

    fun isLive(info: StreamInfo) = info.streamType == StreamType.LIVE_STREAM || info.streamType == StreamType.AUDIO_LIVE_STREAM

    /** ¿Se está usando la red del celular (datos)? */
    fun onMobileData(ctx: Context): Boolean {
        if (DeviceUi.isTv) return false
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return runCatching { cm.isActiveNetworkMetered }.getOrDefault(false)
    }

    /** Calidad «automática»: la de Wi-Fi o la de datos según la red. */
    fun autoHeight(ctx: Context) = if (onMobileData(ctx)) Store.qualityData else Store.qualityWifi

    /** Archivo normal, o «OTF» (videos recién subidos: se arma su manifiesto aquí). */
    private fun playableDelivery(s: org.schabi.newpipe.extractor.stream.Stream) =
        s.itagItem != null && ((s.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && s.isUrl) || (s.deliveryMethod == DeliveryMethod.DASH && !s.isUrl))

    private fun usable(v: VideoStream): Boolean {
        val codec = v.codec.orEmpty()
        return playableDelivery(v) && !codec.startsWith("av01") && v.format != MediaFormat.v3GPP && v.height > 0
    }

    /** Calidades disponibles para el menú (de mayor a menor). */
    fun heights(info: StreamInfo): List<Int> =
        (info.videoOnlyStreams.filter { usable(it) }.map { it.height } + info.videoStreams.filter { it.isUrl && it.height > 0 }.map { it.height })
            .distinct().sortedDescending()

    /** El mejor video que no pase de [maxH]; a igual altura prefiere MP4 (lo aguantan todas las teles). */
    fun pickVideo(info: StreamInfo, maxH: Int, h264Only: Boolean = false): VideoStream? {
        val list = info.videoOnlyStreams.filter { usable(it) && (!h264Only || (it.format == MediaFormat.MPEG_4 && it.codec.orEmpty().startsWith("avc"))) }
        if (list.isEmpty()) return null
        val fit = list.filter { it.height <= maxH }.ifEmpty { listOf(list.minBy { it.height }) }
        val h = fit.maxOf { it.height }
        val same = fit.filter { it.height == h }
        // Arriba de 1080 solo hay VP9; en lo demás, MP4 (H.264) es lo más compatible
        return same.sortedWith(compareByDescending<VideoStream> { it.format == MediaFormat.MPEG_4 }.thenByDescending { it.fps }).first()
    }

    /** Audio original (no doblado), primero M4A, luego el de mejor calidad. */
    fun pickAudio(info: StreamInfo, m4aOnly: Boolean = false, progressiveOnly: Boolean = false): AudioStream? {
        val list = info.audioStreams.filter { playableDelivery(it) && (!progressiveOnly || it.isUrl) }
            .filter { !m4aOnly || it.format == MediaFormat.M4A }
        if (list.isEmpty()) return null
        val original = list.filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }.ifEmpty { list }
        return original.sortedWith(compareByDescending<AudioStream> { it.format == MediaFormat.M4A }.thenByDescending { it.averageBitrate }).first()
    }

    /** Subtítulos disponibles, sin repetir idioma (primero los escritos por personas). */
    fun subtitleOptions(info: StreamInfo): List<SubtitlesStream> =
        info.subtitles.filter { it.isUrl && (it.format == MediaFormat.TTML || it.format == MediaFormat.VTT) }
            .sortedBy { it.isAutoGenerated }
            .distinctBy { it.languageTag + it.isAutoGenerated }

    fun subtitleName(s: SubtitlesStream): String {
        val base = runCatching { s.displayLanguageName }.getOrNull().orEmpty().ifBlank { s.languageTag }
        return if (s.isAutoGenerated) "$base (automáticos)" else base.replaceFirstChar { it.uppercase() }
    }

    fun findSubtitle(info: StreamInfo, lang: String): SubtitlesStream? {
        if (lang.isBlank()) return null
        val opts = subtitleOptions(info)
        return opts.firstOrNull { it.languageTag == lang } ?: opts.firstOrNull { it.languageTag.startsWith(lang) }
    }

    private fun manualDash(content: String, itag: org.schabi.newpipe.extractor.services.youtube.ItagItem, durationSec: Long, otf: Boolean = false): MediaSource {
        val manifest = if (otf) org.schabi.newpipe.extractor.services.youtube.dashmanifestcreators.YoutubeOtfDashManifestCreator.fromOtfStreamingUrl(content, itag, durationSec)
        else YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(content, itag, durationSec)
        val parsed = DashManifestParser().parse(Uri.parse(content), ByteArrayInputStream(manifest.toByteArray()))
        return dash.createMediaSource(parsed, MediaItem.Builder().setUri(Uri.parse(content)).build())
    }

    private fun progressive(url: String): MediaSource =
        ProgressiveMediaSource.Factory(dsf).createMediaSource(MediaItem.fromUri(url))

    private fun subtitleSource(s: SubtitlesStream): MediaSource {
        val mime = if (s.format == MediaFormat.VTT) MimeTypes.TEXT_VTT else MimeTypes.APPLICATION_TTML
        val format = Format.Builder()
            .setSampleMimeType(mime)
            .setLanguage(s.languageTag)
            .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
            .setLabel(subtitleName(s))
            .setId("sub:" + s.languageTag)
            .build()
        val parsers = DefaultSubtitleParserFactory()
        val ef = ExtractorsFactory { arrayOf<Extractor>(SubtitleExtractor(parsers.create(format), format)) }
        return ProgressiveMediaSource.Factory(dsf, ef).createMediaSource(MediaItem.fromUri(s.content))
    }

    /**
     * @param maxH altura máxima de video (0 = solo audio)
     * @param sub subtítulo a mostrar (o null)
     */
    private fun streamSource(st: org.schabi.newpipe.extractor.stream.Stream, info: StreamInfo): MediaSource =
        if (!st.isUrl) manualDash(st.content, st.itagItem!!, info.duration, otf = true)
        else runCatching { manualDash(st.content, st.itagItem!!, info.duration) }.getOrElse { progressive(st.content) }

    /** Lo que trajo YouTube, para el reporte de errores. */
    fun describe(info: StreamInfo): String =
        "videoOnly=" + info.videoOnlyStreams.groupingBy { it.deliveryMethod.name + (if (it.isUrl) "" else "*") }.eachCount() +
            " audio=" + info.audioStreams.groupingBy { it.deliveryMethod.name + (if (it.isUrl) "" else "*") }.eachCount() +
            " muxed=" + info.videoStreams.size + " hls=" + (!info.hlsUrl.isNullOrBlank()) + " dash=" + (!info.dashMpdUrl.isNullOrBlank()) + " tipo=" + info.streamType

    /**
     * @param maxH altura máxima de video (0 = solo audio)
     * @param sub subtítulo a mostrar (o null)
     * @param h264Only solo formatos que aguanta cualquier aparato
     * @param skipSeparate brincar las pistas separadas e ir directo al respaldo (HLS)
     */
    fun build(info: StreamInfo, maxH: Int, sub: SubtitlesStream?, h264Only: Boolean = false, skipSeparate: Boolean = false): Built {
        if (isLive(info)) {
            val hls = info.hlsUrl
            val src = if (!hls.isNullOrBlank()) HlsMediaSource.Factory(hlsDsf).createMediaSource(MediaItem.fromUri(hls))
            else dash.createMediaSource(MediaItem.fromUri(info.dashMpdUrl))
            return Built(src, 0, true, adaptive = true, how = "en vivo")
        }
        // 1) Imagen y sonido por separado (la mejor calidad)
        if (!skipSeparate) runCatching {
            val audio = pickAudio(info)
            val video = if (maxH > 0) pickVideo(info, maxH, h264Only) else null
            val parts = ArrayList<MediaSource>()
            if (video != null) parts += streamSource(video, info)
            if (audio != null) parts += streamSource(audio, info)
            val ok = if (maxH > 0) video != null && audio != null else audio != null
            if (ok) {
                if (sub != null && maxH > 0) parts += subtitleSource(sub)
                val src = if (parts.size == 1) parts[0] else MergingMediaSource(true, *parts.toTypedArray())
                return Built(src, video?.height ?: 0, false, how = "separado" + if (video?.isUrl == false) " otf" else "")
            }
        }
        // 2) Archivo con todo junto (hasta 360p, pero casi siempre existe)
        if (!skipSeparate) {
            val muxed = info.videoStreams.filter { it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
                .let { l -> l.filter { it.height <= maxOf(maxH, 360) }.maxByOrNull { it.height } ?: l.minByOrNull { it.height } }
            if (muxed != null && maxH > 0) {
                val parts = arrayListOf<MediaSource>(progressive(muxed.content))
                if (sub != null) parts += subtitleSource(sub)
                return Built(if (parts.size == 1) parts[0] else MergingMediaSource(true, *parts.toTypedArray()), muxed.height, false, how = "junto")
            }
        }
        // 3) HLS (lista con varias calidades; el reproductor elige sin pasar del tope)
        if (!info.hlsUrl.isNullOrBlank()) {
            val parts = arrayListOf<MediaSource>(HlsMediaSource.Factory(hlsDsf).createMediaSource(MediaItem.fromUri(info.hlsUrl)))
            if (sub != null && maxH > 0) parts += subtitleSource(sub)
            return Built(if (parts.size == 1) parts[0] else MergingMediaSource(true, *parts.toTypedArray()), maxH, false, adaptive = true, how = "hls")
        }
        // 4) DASH de YouTube
        if (!info.dashMpdUrl.isNullOrBlank()) {
            return Built(dash.createMediaSource(MediaItem.fromUri(info.dashMpdUrl)), maxH, false, adaptive = true, how = "dash")
        }
        throw IllegalStateException("YouTube no mandó ningún formato que se pueda reproducir para este video.")
    }
}
