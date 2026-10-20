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
    )

    private val dsf by lazy { YtDataSource.Factory() }
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

    private fun usable(v: VideoStream): Boolean {
        val codec = v.codec.orEmpty()
        return v.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && v.isUrl && v.itagItem != null &&
            !codec.startsWith("av01") && v.format != MediaFormat.v3GPP && v.height > 0
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
    fun pickAudio(info: StreamInfo, m4aOnly: Boolean = false): AudioStream? {
        val list = info.audioStreams.filter { it.isUrl && it.itagItem != null && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
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

    private fun manualDash(content: String, itag: org.schabi.newpipe.extractor.services.youtube.ItagItem, durationSec: Long): MediaSource {
        val manifest = YoutubeProgressiveDashManifestCreator.fromProgressiveStreamingUrl(content, itag, durationSec)
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
    fun build(info: StreamInfo, maxH: Int, sub: SubtitlesStream?, h264Only: Boolean = false): Built {
        if (isLive(info)) {
            val hls = info.hlsUrl
            val src = if (!hls.isNullOrBlank()) HlsMediaSource.Factory(dsf).createMediaSource(MediaItem.fromUri(hls))
            else dash.createMediaSource(MediaItem.fromUri(info.dashMpdUrl))
            return Built(src, 0, true)
        }
        val parts = ArrayList<MediaSource>()
        var height = 0
        val audio = pickAudio(info)
        val video = if (maxH > 0) pickVideo(info, maxH, h264Only) else null
        if (video != null) {
            parts += runCatching { manualDash(video.content, video.itagItem!!, info.duration) }.getOrElse { progressive(video.content) }
            height = video.height
        }
        if (audio != null) {
            parts += runCatching { manualDash(audio.content, audio.itagItem!!, info.duration) }.getOrElse { progressive(audio.content) }
        }
        if (parts.isEmpty() || (maxH > 0 && video == null)) {
            // Sin pistas separadas: el archivo de siempre con video y audio juntos
            val muxed = info.videoStreams.filter { it.isUrl }.let { l -> l.filter { it.height <= maxOf(maxH, 360) }.maxByOrNull { it.height } ?: l.minByOrNull { it.height } }
            if (muxed != null) {
                parts.clear(); parts += progressive(muxed.content); height = muxed.height
            }
        }
        if (parts.isEmpty()) throw IllegalStateException("Este video no trae ningún formato que se pueda reproducir.")
        if (sub != null && maxH > 0) parts += subtitleSource(sub)
        val src = if (parts.size == 1) parts[0] else MergingMediaSource(true, *parts.toTypedArray())
        return Built(src, height, false)
    }
}
