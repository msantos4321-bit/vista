package com.mauricio.vista.ui

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.net.Uri
import android.view.ViewGroup
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.media3.common.C as MC
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.mauricio.vista.data.Downloads
import com.mauricio.vista.data.SponsorBlock
import com.mauricio.vista.data.Store
import com.mauricio.vista.data.Video
import com.mauricio.vista.data.Yt
import com.mauricio.vista.player.Sources
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.SubtitlesStream

/** Estado que también necesita la actividad (imagen en imagen, segundo plano). */
object PlayerState {
    var active by mutableStateOf(false)
    var playing by mutableStateOf(false)
    var audioOnly by mutableStateOf(false)
    var inPip by mutableStateOf(false)
}

/** Todo lo que la pantalla del reproductor comparte con sus controles. */
class PlayerUi(val player: ExoPlayer) {
    var info by mutableStateOf<StreamInfo?>(null)
    var video by mutableStateOf(Video(url = "", title = ""))
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(true)
    var offline by mutableStateOf(false)
    var audioOnly by mutableStateOf(false)
    /** 0 = automática. */
    var chosenQuality by mutableIntStateOf(0)
    /** Tras un error del decodificador: solo H.264 (lo aguanta cualquier aparato). */
    var safeMode by mutableStateOf(false)
    var autoFixes = 0
    var height by mutableIntStateOf(0)
    var subtitle by mutableStateOf<SubtitlesStream?>(null)
    var speed by mutableFloatStateOf(Store.speed)
    var segments by mutableStateOf<List<SponsorBlock.Segment>>(emptyList())
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var buffered by mutableLongStateOf(0L)
    var isPlaying by mutableStateOf(false)
    var buffering by mutableStateOf(true)
    var ended by mutableStateOf(false)
    var controls by mutableStateOf(true)
    var controlsTick by mutableIntStateOf(0)
    /** Panel abierto: "menu", "quality", "speed", "subs", "chapters", "sleep", "related". */
    var panel by mutableStateOf<String?>(null)
    var hint by mutableStateOf("")
    var hintTick by mutableIntStateOf(0)
    var fullscreen by mutableStateOf(false)
    var next by mutableStateOf<Video?>(null)
    var related by mutableStateOf<List<Video>>(emptyList())

    fun showControls() { controls = true; controlsTick++ }
    fun flash(t: String) { hint = t; hintTick++ }
    fun toggle() { if (player.isPlaying) player.pause() else { if (ended) { player.seekTo(0); ended = false }; player.play() } }
    fun seekBy(ms: Long) {
        val d = player.duration
        val t = (player.currentPosition + ms).coerceAtLeast(0).let { if (d != MC.TIME_UNSET && d > 0) it.coerceAtMost(d - 500) else it }
        player.seekTo(t); position = t
        flash((if (ms > 0) "+" else "−") + "${kotlin.math.abs(ms) / 1000} s")
    }
}

@SuppressLint("UnsafeOptInUsageError")
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(start: Video, queue: List<Video>, startAudioOnly: Boolean) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val tv = DeviceUi.isTv
    val player = remember {
        ExoPlayer.Builder(ctx, DefaultRenderersFactory(ctx).setEnableDecoderFallback(true))
            .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(15_000, 60_000, 1_200, 2_500).build())
            .setSeekBackIncrementMs(10_000)
            .setSeekForwardIncrementMs(10_000)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(MC.WAKE_MODE_NETWORK)
            .build()
    }
    val ui = remember { PlayerUi(player).also { it.video = start; it.audioOnly = startAudioOnly } }
    var retry by remember { mutableIntStateOf(0) }
    val rootFr = remember { FocusRequester() }

    /** Carga (o recarga) la fuente con la calidad, el audio y los subtítulos actuales. */
    fun load(posMs: Long, play: Boolean = true) {
        val info = ui.info ?: return
        try {
            val maxH = if (ui.audioOnly) 0 else if (ui.chosenQuality > 0) ui.chosenQuality else Sources.autoHeight(ctx)
            val built = Sources.build(info, maxH, ui.subtitle, ui.safeMode)
            ui.height = built.height
            player.setMediaSource(built.source, posMs.coerceAtLeast(0))
            player.prepare()
            player.playWhenReady = play
            ui.error = null
        } catch (e: Exception) {
            ui.error = Yt.friendly(e)
        }
    }

    // 1) Datos del video
    LaunchedEffect(retry) {
        ui.loading = true
        ui.error = null
        val local = Downloads.localFile(start.id)
        val saved = Store.watched(start.id)
        val resume = if (saved != null && !saved.finished && saved.posMs > 10_000) saved.posMs else 0L
        if (local != null && retry == 0) {
            // Descargado: se ve sin internet
            ui.offline = true
            Downloads.items.value.firstOrNull { it.video.id == start.id && it.done }?.let { ui.audioOnly = it.audioOnly || ui.audioOnly }
            val src = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(ctx)).createMediaSource(MediaItem.fromUri(Uri.fromFile(local)))
            player.setMediaSource(src, resume)
            player.prepare(); player.play()
            ui.loading = false
            runCatching { Yt.stream(start.url) }.onSuccess { info ->
                ui.info = info
                ui.related = Yt.related(info)
            }
        } else {
            runCatching { Yt.stream(start.url, fresh = retry > 0) }
                .onSuccess { info ->
                    ui.info = info
                    ui.video = ui.video.copy(
                        title = info.name.orEmpty(), channel = info.uploaderName.orEmpty(), channelUrl = info.uploaderUrl.orEmpty(),
                        duration = info.duration, views = info.viewCount, uploaded = info.textualUploadDate.orEmpty(),
                        thumb = ui.video.thumb.ifBlank { Yt.bestImage(info.thumbnails, 480) }, live = Sources.isLive(info),
                    )
                    ui.related = Yt.related(info).let { r -> if (Store.hideShorts) r.filterNot { it.short } else r }
                    com.mauricio.vista.data.Recs.remember(start.id, Yt.related(info))
                    ui.subtitle = Sources.findSubtitle(info, Store.subtitles)
                    load(if (Sources.isLive(info)) 0 else resume)
                    ui.loading = false
                }
                .onFailure { ui.error = Yt.friendly(it); ui.loading = false }
        }
        ui.segments = runCatching { SponsorBlock.segments(start.id) }.getOrDefault(emptyList())
    }

    // Siguiente video: el de la fila de donde vino, o el primero recomendado que no hayas visto
    LaunchedEffect(ui.related) {
        ui.next = queue.firstOrNull() ?: ui.related.firstOrNull { r -> Store.watched(r.id)?.finished != true }
    }

    // 2) Eventos del reproductor
    DisposableEffect(Unit) {
        PlayerState.active = true
        com.mauricio.vista.data.Recs.playing = true
        player.setPlaybackSpeed(ui.speed)
        val l = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { ui.isPlaying = isPlaying; PlayerState.playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) {
                ui.buffering = state == Player.STATE_BUFFERING
                if (state == Player.STATE_ENDED) {
                    ui.ended = true
                    ui.showControls()
                    Store.saveProgress(ui.video, player.duration.coerceAtLeast(1), player.duration.coerceAtLeast(1))
                }
                if (state == Player.STATE_READY) ui.ended = false
            }
            override fun onPlayerError(error: PlaybackException) {
                val http = generateSequence(error as Throwable?) { it.cause }.filterIsInstance<androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException>().firstOrNull()
                val pos = player.currentPosition
                val decoder = error.errorCode in setOf(
                    PlaybackException.ERROR_CODE_DECODER_INIT_FAILED, PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
                    PlaybackException.ERROR_CODE_DECODING_FAILED, PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
                    PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
                )
                val network = error.errorCode in setOf(
                    PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                    PlaybackException.ERROR_CODE_TIMEOUT, PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
                )
                com.mauricio.vista.data.Store.lastError = "Video ${ui.video.id} · ${error.errorCodeName} · ${http?.responseCode ?: ""} · calidad ${ui.height} · " +
                    android.os.Build.MANUFACTURER + " " + android.os.Build.MODEL + " Android " + android.os.Build.VERSION.RELEASE + "\n" + android.util.Log.getStackTraceString(error).take(3000)
                when {
                    // Las ligas de YouTube caducan: se piden de nuevo en silencio
                    http != null && (http.responseCode == 403 || http.responseCode == 410) && retry < 2 && !ui.offline -> { ui.flash("Reconectando…"); retry++ }
                    // El aparato no pudo con ese formato: se cambia a uno más sencillo sin preguntar
                    decoder && !ui.offline && ui.autoFixes < 3 -> {
                        ui.autoFixes++
                        ui.flash("Ajustando el video para este aparato…")
                        ui.safeMode = true
                        val lower = (ui.info?.let { Sources.heights(it) }.orEmpty().filter { it < ui.height.coerceAtLeast(1) }.firstOrNull { it <= 720 }) ?: 360
                        if (ui.chosenQuality == lower) load(pos) else ui.chosenQuality = lower
                    }
                    // Se fue el internet un momento: se reintenta solo
                    network && ui.autoFixes < 4 -> {
                        ui.autoFixes++
                        ui.flash("Reconectando…")
                        uiScope.launch { delay(2500); player.prepare(); player.seekTo(pos); player.play() }
                    }
                    error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW -> { player.seekToDefaultPosition(); player.prepare() }
                    else -> ui.error = "No se pudo reproducir este video (" + error.errorCodeName.removePrefix("ERROR_CODE_").lowercase().replace('_', ' ') + (http?.let { " · ${it.responseCode}" } ?: "") + ")."
                }
            }
        }
        player.addListener(l)
        onDispose {
            val d = player.duration
            if (d != MC.TIME_UNSET && d > 0 && !ui.video.live && ui.video.title != "Cargando…") Store.saveProgress(ui.video, player.currentPosition, d)
            player.removeListener(l)
            player.release()
            PlayerState.active = false
            com.mauricio.vista.data.Recs.playing = false
            PlayerState.playing = false
            DeviceUi.setPlaying(false)
        }
    }
    LaunchedEffect(ui.audioOnly) { PlayerState.audioOnly = ui.audioOnly }

    // 3) Avance, guardado, SponsorBlock y temporizador
    LaunchedEffect(Unit) {
        var lastSave = 0L
        val skipped = HashSet<Long>()
        while (true) {
            delay(400)
            val pos = player.currentPosition
            ui.position = pos
            val d = player.duration
            ui.duration = if (d == MC.TIME_UNSET || d < 0) 0 else d
            ui.buffered = player.bufferedPosition
            ui.segments.firstOrNull { pos >= it.startMs && pos < it.endMs - 600 && it.startMs !in skipped }?.let { s ->
                skipped += s.startMs
                player.seekTo(s.endMs)
                Toast.show("Se saltó: " + SponsorBlock.label(s.category))
            }
            if (System.currentTimeMillis() - lastSave > 8000 && player.isPlaying && ui.duration > 0 && !ui.video.live) {
                lastSave = System.currentTimeMillis()
                Store.saveProgress(ui.video, pos, ui.duration)
            }
            if (Store.sleepAt > 0 && System.currentTimeMillis() >= Store.sleepAt) {
                Store.sleepAt = 0; player.pause(); ui.flash("Se pausó por el temporizador"); ui.showControls()
            }
        }
    }

    // Los controles se esconden solos después de unos segundos
    LaunchedEffect(ui.controls, ui.controlsTick, ui.isPlaying, ui.panel) {
        if (ui.controls && ui.isPlaying && ui.panel == null && !ui.audioOnly) {
            delay(if (tv) 5000 else 3500)
            ui.controls = false
            if (tv) runCatching { rootFr.requestFocus() }
        }
    }
    // En la tele: con los controles ocultos, el foco siempre vuelve al video para que el control responda
    LaunchedEffect(ui.controls, ui.panel, ui.ended, ui.error) {
        if (tv && !ui.controls && ui.panel == null && !ui.ended && ui.error == null) { delay(60); runCatching { rootFr.requestFocus() } }
    }
    LaunchedEffect(ui.hintTick) { if (ui.hint.isNotEmpty()) { delay(1100); ui.hint = "" } }

    // Al terminar: siguiente automático
    LaunchedEffect(ui.ended) {
        if (ui.ended && Store.autoplay && ui.next != null) {
            delay(8000)
            if (ui.ended) ui.next?.let { n -> nav.play(n, queue.drop(1)) }
        }
    }

    // Segundo plano: el video se pausa; «solo audio» sigue sonando
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_STOP && !ui.audioOnly && !PlayerState.inPip) player.pause()
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(ui.fullscreen) { if (!tv) DeviceUi.setPlaying(ui.fullscreen) }

    BackHandler {
        when {
            ui.panel != null -> ui.panel = null
            tv && ui.controls && ui.isPlaying -> { ui.controls = false; runCatching { rootFr.requestFocus() } }
            !tv && ui.fullscreen -> ui.fullscreen = false
            else -> nav.back()
        }
    }

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val immersive = tv || ui.fullscreen || landscape || PlayerState.inPip

    val videoSurface: @Composable (Modifier) -> Unit = { m ->
        Box(m.background(Color.Black)) {
            AndroidView(
                factory = { c ->
                    PlayerView(c).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        useController = false
                        this.player = player
                        keepScreenOn = true
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                        setShutterBackgroundColor(android.graphics.Color.BLACK)
                        isFocusable = false
                        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                        subtitleView?.apply {
                            setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * Store.subtitleScale)
                            setStyle(CaptionStyleCompat(android.graphics.Color.WHITE, 0x99000000.toInt(), android.graphics.Color.TRANSPARENT, CaptionStyleCompat.EDGE_TYPE_DROP_SHADOW, android.graphics.Color.BLACK, null))
                        }
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (ui.audioOnly) AudioArtwork(ui)
            if (!PlayerState.inPip) PlayerOverlay(ui, tv, immersive, onBack = { if (!tv && ui.fullscreen) ui.fullscreen = false else nav.back() }, onRetry = { retry++ })
        }
    }

    if (immersive) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(rootFr)
                .focusable()
                .onPreviewKeyEvent { e -> if (tv) tvKeys(ui, e.key, e.type, rootFr) else false },
        ) { videoSurface(Modifier.fillMaxSize()) }
        LaunchedEffect(Unit) { if (tv) { delay(100); runCatching { rootFr.requestFocus() } } }
    } else {
        PhoneWatchPage(ui, videoSurface = { videoSurface(Modifier.fillMaxWidth().aspectRatio(16f / 9f)) }, onReload = { load(player.currentPosition) })
    }

    // Cambios de calidad / subtítulos / solo audio desde los menús
    LaunchedEffect(ui.chosenQuality, ui.subtitle, ui.audioOnly) {
        if (ui.info != null && !ui.offline && !ui.loading) load(player.currentPosition, player.playWhenReady)
    }
    LaunchedEffect(ui.speed) { player.setPlaybackSpeed(ui.speed) }
}

/** Control remoto de la tele. Devuelve true si la tecla ya se usó. */
private fun tvKeys(ui: PlayerUi, key: Key, type: KeyEventType, root: FocusRequester): Boolean {
    if (type != KeyEventType.KeyDown) return false
    if (ui.panel != null) { ui.controlsTick++; return false }
    if (!ui.controls) {
        return when (key) {
            Key.DirectionLeft -> { ui.seekBy(-10_000); true }
            Key.DirectionRight -> { ui.seekBy(10_000); true }
            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { ui.toggle(); ui.showControls(); true }
            Key.DirectionUp, Key.DirectionDown, Key.Menu, Key.Info -> { ui.showControls(); true }
            Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { ui.toggle(); ui.showControls(); true }
            Key.MediaFastForward -> { ui.seekBy(30_000); true }
            Key.MediaRewind -> { ui.seekBy(-30_000); true }
            else -> false
        }
    }
    ui.controlsTick++
    return when (key) {
        Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause -> { ui.toggle(); true }
        Key.MediaFastForward -> { ui.seekBy(30_000); true }
        Key.MediaRewind -> { ui.seekBy(-30_000); true }
        else -> { root.hashCode(); false }
    }
}
