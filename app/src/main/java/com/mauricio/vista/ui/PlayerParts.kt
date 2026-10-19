package com.mauricio.vista.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ClosedCaption
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.mauricio.vista.data.Downloads
import com.mauricio.vista.data.Fmt
import com.mauricio.vista.data.Remote
import com.mauricio.vista.data.Store
import com.mauricio.vista.data.Yt
import com.mauricio.vista.player.Sources
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val White = Color.White
private val Dim = Color(0xFFD1D1D6)

// ======================================================================
// Capa de controles sobre el video
// ======================================================================
@Composable
fun PlayerOverlay(ui: PlayerUi, tv: Boolean, immersive: Boolean, onBack: () -> Unit, onRetry: () -> Unit) {
    // Toques en el celular: uno muestra/oculta, doble a los lados adelanta o regresa
    if (!tv) {
        Box(
            Modifier.fillMaxSize().pointerInput(Unit) {
                detectTapGestures(
                    onTap = { if (ui.controls) ui.controls = false else ui.showControls() },
                    onDoubleTap = { o -> if (o.x < size.width / 2) ui.seekBy(-10_000) else ui.seekBy(10_000) },
                )
            },
        )
    }
    // Cargando
    if ((ui.loading || ui.buffering) && ui.error == null && !ui.ended) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner(size = if (tv) 54.dp else 44.dp) }
    }
    // Aviso de ±10 s
    if (ui.hint.isNotEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(ui.hint, color = White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.background(Color(0xAA000000), RoundedCornerShape(50)).padding(horizontal = 22.dp, vertical = 10.dp))
        }
    }

    AnimatedVisibility(ui.controls || ui.ended || ui.error != null, enter = fadeIn(), exit = fadeOut()) {
        Box(Modifier.fillMaxSize()) {
            // Sombras arriba y abajo para leer el texto
            Box(Modifier.fillMaxWidth().height(if (tv) 150.dp else 110.dp).background(Brush.verticalGradient(listOf(Color(0xCC000000), Color.Transparent))))
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(if (tv) 220.dp else 140.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE6000000)))))

            // Arriba: título
            Row(Modifier.fillMaxWidth().padding(horizontal = if (tv) 40.dp else 8.dp, vertical = if (tv) 26.dp else 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!tv) IconBtn(Icons.AutoMirrored.Filled.ArrowBack, "Regresar", onBack)
                Column(Modifier.weight(1f).padding(start = if (tv) 0.dp else 4.dp)) {
                    if (immersive) {
                        Text(ui.video.title, color = White, fontSize = if (tv) 22.sp else 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(ui.video.channel, color = Dim, fontSize = if (tv) 14.sp else 12.sp, maxLines = 1)
                    }
                }
                if (!tv) {
                    if (Store.tvAddress.isNotBlank()) IconBtn(Icons.Filled.Cast, "Ver en la tele", onClick = {
                        uiScope.launch {
                            val ok = Remote.sendPlay(Yt.watchUrl(ui.video.id))
                            Toast.show(if (ok) "Listo, mira la tele" else "No encontré la tele")
                            if (ok) ui.player.pause()
                        }
                    })
                    IconBtn(Icons.Filled.Settings, "Opciones", onClick = { ui.panel = "menu" })
                } else {
                    ClockLabel()
                }
            }

            // Centro (celular): botones grandes
            if (!tv && ui.error == null) {
                Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(36.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBtn(Icons.Filled.Replay10, "Regresar 10 s", { ui.seekBy(-10_000) }, 34.dp)
                    if (!(ui.loading || ui.buffering) || ui.ended) {
                        IconBtn(if (ui.ended) Icons.Filled.Replay else if (ui.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Reproducir", { ui.toggle(); ui.showControls() }, 54.dp)
                    } else Spacer(Modifier.size(70.dp))
                    IconBtn(Icons.Filled.Forward10, "Adelantar 10 s", { ui.seekBy(10_000) }, 34.dp)
                }
            }

            // Abajo: barra de avance y botones
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(horizontal = if (tv) 40.dp else 12.dp, vertical = if (tv) 24.dp else 4.dp)) {
                if (!ui.video.live) {
                    SeekBar(ui, tv)
                    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(Fmt.clock(ui.position) + " / " + Fmt.clock(ui.duration), color = Dim, fontSize = if (tv) 13.sp else 12.sp)
                        currentChapter(ui)?.let { Text("  ·  $it", color = Dim, fontSize = if (tv) 13.sp else 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
                        Spacer(Modifier.weight(1f))
                        if (!tv) {
                            if (ui.height > 0) Text(Fmt.quality(ui.height), color = Dim, fontSize = 12.sp, modifier = Modifier.padding(end = 6.dp))
                            IconBtn(if (ui.fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, "Pantalla completa", { ui.fullscreen = !ui.fullscreen }, 26.dp)
                        }
                    }
                } else {
                    Text("● EN VIVO", color = C.Live, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                if (tv) {
                    Spacer(Modifier.height(14.dp))
                    TvButtons(ui)
                }
            }

            // Error
            ui.error?.let { msg ->
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        Modifier.width(if (tv) 520.dp else 320.dp).background(Color(0xF0101114), RoundedCornerShape(18.dp)).padding(22.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(msg, color = White, fontSize = 15.sp)
                        val fr = remember { FocusRequester() }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            ActionButton("Reintentar", onRetry, Modifier.focusRequester(fr), primary = true)
                            ActionButton("Salir", onBack)
                        }
                        LaunchedEffect(msg) { delay(100); runCatching { fr.requestFocus() } }
                    }
                }
            }
        }
    }

    // Al terminar: siguiente video
    if (ui.ended && ui.error == null) EndCard(ui, tv)

    // Paneles de opciones
    if (ui.panel != null) {
        if (tv) TvPanel(ui) else PhoneOptions(ui)
    }
}

private fun currentChapter(ui: PlayerUi): String? {
    val segs = ui.info?.streamSegments.orEmpty()
    if (segs.isEmpty()) return null
    val s = (ui.position / 1000).toInt()
    return segs.lastOrNull { it.startTimeSeconds <= s }?.title
}

@Composable
private fun IconBtn(icon: ImageVector, label: String, onClick: () -> Unit, size: Dp = 24.dp) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f, pressedScale = 0.9f),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, contentColor = White, focusedContainerColor = Color(0x33FFFFFF), pressedContainerColor = Color(0x33FFFFFF)),
    ) { Icon(icon, label, Modifier.padding(8.dp).size(size)) }
}

// ======================================================================
// Barra de avance (con capítulos y partes que se saltan)
// ======================================================================
@Composable
private fun SeekBar(ui: PlayerUi, tv: Boolean) {
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    var dragFrac by remember { mutableFloatStateOf(-1f) }
    val dur = ui.duration.coerceAtLeast(1)
    val frac = if (dragFrac >= 0) dragFrac else ui.position.toFloat() / dur
    val segs = ui.segments
    val chapters = ui.info?.streamSegments.orEmpty()
    val fr = remember { FocusRequester() }
    Box(
        Modifier
            .fillMaxWidth()
            .height(if (tv) 26.dp else 30.dp)
            .then(
                if (tv) Modifier
                    .focusRequester(fr)
                    .onKeyEvent { e ->
                        if (e.type != KeyEventType.KeyDown) false else when (e.key) {
                            Key.DirectionLeft -> { ui.seekBy(-10_000); ui.controlsTick++; true }
                            Key.DirectionRight -> { ui.seekBy(10_000); ui.controlsTick++; true }
                            Key.DirectionCenter, Key.Enter -> { ui.toggle(); true }
                            else -> false
                        }
                    }
                    .focusable(interactionSource = src)
                else Modifier
                    .pointerInput(Unit) {
                        detectTapGestures { o -> ui.player.seekTo((o.x / size.width * dur).toLong()); ui.showControls() }
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragStart = { o -> dragFrac = (o.x / size.width).coerceIn(0f, 1f); ui.controlsTick++ },
                            onDragEnd = { if (dragFrac >= 0) ui.player.seekTo((dragFrac * dur).toLong()); dragFrac = -1f },
                            onDragCancel = { dragFrac = -1f },
                            onHorizontalDrag = { ch, _ -> dragFrac = (ch.position.x / size.width).coerceIn(0f, 1f); ui.controlsTick++ },
                        )
                    },
            ),
        contentAlignment = Alignment.Center,
    ) {
        val h = if (focused || dragFrac >= 0) 7.dp else 4.dp
        Canvas(Modifier.fillMaxWidth().height(h)) {
            val w = size.width
            val r = CornerRadius(size.height / 2, size.height / 2)
            drawRoundRect(Color(0x55FFFFFF), size = size, cornerRadius = r)
            drawRoundRect(Color(0x88FFFFFF), size = Size(w * (ui.buffered.toFloat() / dur).coerceIn(0f, 1f), size.height), cornerRadius = r)
            segs.forEach { s ->
                val x0 = w * s.startMs / dur; val x1 = w * s.endMs / dur
                drawRect(Color(0xFF34D399), topLeft = Offset(x0, 0f), size = Size((x1 - x0).coerceAtLeast(2f), size.height))
            }
            drawRoundRect(C.Accent, size = Size(w * frac.coerceIn(0f, 1f), size.height), cornerRadius = r)
            chapters.drop(1).forEach { c ->
                val x = w * (c.startTimeSeconds * 1000f) / dur
                drawRect(Color.Black, topLeft = Offset(x - 1.5f, 0f), size = Size(3f, size.height))
            }
        }
        // Bolita
        Canvas(Modifier.fillMaxWidth().height(18.dp)) {
            val d = if (focused || dragFrac >= 0) 18f * density / 1.6f else 12f * density / 1.6f
            drawCircle(Color.White, radius = d, center = Offset(size.width * frac.coerceIn(0f, 1f), size.height / 2))
        }
        if (dragFrac >= 0) {
            Text(Fmt.clock((dragFrac * dur).toLong()), color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.TopCenter).padding(bottom = 30.dp).background(Color(0xCC000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp))
        }
    }
    if (tv) LaunchedEffect(ui.controls) { if (ui.controls) { delay(60); runCatching { fr.requestFocus() } } }
}

// ======================================================================
// Botones de la tele
// ======================================================================
@Composable
private fun TvButtons(ui: PlayerUi) {
    val nav = LocalNav.current
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        ActionButton(if (ui.isPlaying) "Pausa" else "Reproducir", { ui.toggle() }, icon = if (ui.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, primary = true)
        ui.next?.let { n -> ActionButton("Siguiente", { nav.play(n) }, icon = Icons.Filled.SkipNext) }
        if (!ui.video.live && !ui.offline) ActionButton(if (ui.audioOnly) "Solo audio" else Fmt.quality(ui.height), { ui.panel = "quality" }, icon = Icons.Filled.HighQuality)
        ActionButton(speedText(ui.speed), { ui.panel = "speed" }, icon = Icons.Filled.Speed)
        if (ui.info?.let { Sources.subtitleOptions(it).isNotEmpty() } == true) ActionButton(ui.subtitle?.let { "Subtítulos" } ?: "Sin subt.", { ui.panel = "subs" }, icon = Icons.Filled.ClosedCaption, active = ui.subtitle != null)
        if (ui.info?.streamSegments?.isNotEmpty() == true) ActionButton("Capítulos", { ui.panel = "chapters" }, icon = Icons.Filled.ListAlt)
        if (ui.related.isNotEmpty()) ActionButton("Más videos", { ui.panel = "related" }, icon = Icons.Filled.VideoLibrary)
        ActionButton("", { ui.panel = "sleep" }, icon = Icons.Filled.Bedtime, active = Store.sleepAt > 0)
    }
}

private fun speedText(s: Float) = if (s == 1f) "1×" else ("" + s).trimEnd('0').trimEnd('.') + "×"

/** Panel lateral de la tele (calidad, velocidad, subtítulos, capítulos, más videos, temporizador). */
@Composable
private fun TvPanel(ui: PlayerUi) {
    val nav = LocalNav.current
    val fr = remember { FocusRequester() }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        AnimatedVisibility(true, enter = slideInHorizontally { it }, exit = slideOutHorizontally { it }) {
            Column(
                Modifier.fillMaxHeight().width(if (ui.panel == "related") 420.dp else 330.dp).background(C.Glass).padding(22.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val title = when (ui.panel) {
                    "quality" -> "Calidad"; "speed" -> "Velocidad"; "subs" -> "Subtítulos"; "chapters" -> "Capítulos"
                    "related" -> "Más videos"; "sleep" -> "Apagar en…"; else -> "Opciones"
                }
                Text(title, color = C.Hi, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    panelItems(ui, firstFocus = fr, onPlay = { v -> ui.panel = null; nav.play(v) })
                }
            }
        }
    }
    LaunchedEffect(ui.panel) { delay(80); runCatching { fr.requestFocus() } }
}

/** Opciones en el celular: hoja que sale abajo. */
@Composable
private fun PhoneOptions(ui: PlayerUi) {
    val nav = LocalNav.current
    Dialog(onDismissRequest = { ui.panel = null }) {
        Column(Modifier.fillMaxWidth().background(C.Panel, RoundedCornerShape(22.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                panelItems(ui, firstFocus = null, onPlay = { v -> ui.panel = null; nav.play(v) })
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.panelItems(ui: PlayerUi, firstFocus: FocusRequester?, onPlay: (com.mauricio.vista.data.Video) -> Unit) {
    val info = ui.info
    fun opt(key: String, label: String, selected: Boolean, first: Boolean, action: () -> Unit) {
        item(key = key) {
            OptionRow(label, selected, if (first && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier, action)
        }
    }
    when (ui.panel) {
        "menu" -> {
            if (!ui.video.live && !ui.offline) opt("q", "Calidad: " + (if (ui.audioOnly) "solo audio" else (if (ui.chosenQuality == 0) "automática (" + Fmt.quality(ui.height) + ")" else Fmt.quality(ui.height))), false, true) { ui.panel = "quality" }
            opt("s", "Velocidad: " + speedText(ui.speed), false, ui.video.live || ui.offline) { ui.panel = "speed" }
            if (info != null && Sources.subtitleOptions(info).isNotEmpty()) opt("c", "Subtítulos: " + (ui.subtitle?.let { Sources.subtitleName(it) } ?: "apagados"), false, false) { ui.panel = "subs" }
            if (info?.streamSegments?.isNotEmpty() == true) opt("ch", "Capítulos", false, false) { ui.panel = "chapters" }
            opt("a", if (ui.audioOnly) "Volver a ver el video" else "Solo audio (gasta poco, sigue con la pantalla apagada)", ui.audioOnly, false) { ui.audioOnly = !ui.audioOnly; ui.panel = null }
            opt("t", "Temporizador: " + (if (Store.sleepAt > 0) "en " + ((Store.sleepAt - System.currentTimeMillis()) / 60_000 + 1) + " min" else "apagado"), false, false) { ui.panel = "sleep" }
        }
        "quality" -> {
            opt("auto", "Automática", ui.chosenQuality == 0 && !ui.audioOnly, ui.chosenQuality == 0) { ui.audioOnly = false; ui.chosenQuality = 0; ui.panel = null }
            info?.let { Sources.heights(it) }.orEmpty().forEachIndexed { i, h ->
                opt("h$h", Fmt.quality(h) + if (h >= 1080) "  HD" else "", ui.chosenQuality == h && !ui.audioOnly, ui.chosenQuality == h || false) {
                    ui.audioOnly = false; ui.chosenQuality = h; ui.panel = null
                }
            }
            opt("audio", "Solo audio", ui.audioOnly, false) { ui.audioOnly = true; ui.panel = null }
        }
        "speed" -> listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).forEach { s ->
            opt("sp$s", if (s == 1f) "Normal" else speedText(s), ui.speed == s, ui.speed == s) { ui.speed = s; ui.panel = null }
        }
        "subs" -> {
            opt("off", "Apagados", ui.subtitle == null, ui.subtitle == null) { ui.subtitle = null; ui.panel = null }
            info?.let { Sources.subtitleOptions(it) }.orEmpty().forEach { s ->
                val sel = ui.subtitle?.languageTag == s.languageTag && ui.subtitle?.isAutoGenerated == s.isAutoGenerated
                opt("s:" + s.languageTag + s.isAutoGenerated, Sources.subtitleName(s), sel, sel) { ui.subtitle = s; ui.panel = null }
            }
        }
        "chapters" -> info?.streamSegments.orEmpty().forEachIndexed { i, c ->
            val cur = currentChapter(ui) == c.title
            opt("ch$i", Fmt.clock(c.startTimeSeconds * 1000L) + "   " + c.title, cur, cur || i == 0) {
                ui.player.seekTo(c.startTimeSeconds * 1000L); ui.panel = null
            }
        }
        "sleep" -> listOf(0, 15, 30, 45, 60, 90).forEach { m ->
            opt("z$m", if (m == 0) "Apagado" else "$m minutos", (m == 0 && Store.sleepAt == 0L), m == 0) {
                Store.sleepAt = if (m == 0) 0 else System.currentTimeMillis() + m * 60_000L
                Toast.show(if (m == 0) "Temporizador apagado" else "Se pausará en $m minutos")
                ui.panel = null
            }
        }
        "related" -> itemsIndexed(ui.related.take(20), key = { _, v -> "r" + v.id }) { i, v ->
            VideoRow(v, { onPlay(v) }, if (i == 0 && firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier)
        }
    }
}

@Composable
private fun OptionRow(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick, modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) C.AccentSoft else C.Panel2, contentColor = if (selected) C.Accent else C.Hi,
            focusedContainerColor = C.Hi, focusedContentColor = C.Bg, pressedContainerColor = C.Line,
        ),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 15.sp, fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (selected) Icon(Icons.Filled.Check, null, Modifier.size(18.dp))
        }
    }
}

// ======================================================================
// Al terminar
// ======================================================================
@Composable
private fun EndCard(ui: PlayerUi, tv: Boolean) {
    val nav = LocalNav.current
    val n = ui.next ?: return
    var left by remember { mutableStateOf(8) }
    LaunchedEffect(n.id) { while (left > 0 && Store.autoplay) { delay(1000); left-- } }
    val fr = remember { FocusRequester() }
    Box(Modifier.fillMaxSize().padding(if (tv) 40.dp else 12.dp), contentAlignment = if (tv) Alignment.BottomEnd else Alignment.Center) {
        Column(Modifier.width(if (tv) 380.dp else 300.dp).background(Color(0xF0101114), RoundedCornerShape(18.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (Store.autoplay) "Siguiente en $left s" else "Siguiente", color = C.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Box(Modifier.fillMaxWidth().height(if (tv) 190.dp else 150.dp).clip(RoundedCornerShape(12.dp))) { Thumb(n, 0f, Modifier.fillMaxSize()) }
            Text(n.title, color = White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("Ver ya", { nav.play(n) }, Modifier.focusRequester(fr), icon = Icons.Filled.PlayArrow, primary = true)
                ActionButton("Cancelar", { ui.next = null })
            }
        }
    }
    LaunchedEffect(n.id) { delay(120); runCatching { fr.requestFocus() } }
}

/** Con «solo audio»: portada grande sobre fondo difuminado. */
@Composable
fun AudioArtwork(ui: PlayerUi) {
    Box(Modifier.fillMaxSize().background(C.Bg), contentAlignment = Alignment.Center) {
        Img(ui.video.thumbHq, Modifier.fillMaxSize().blur(40.dp))
        Box(Modifier.fillMaxSize().background(Color(0x99000000)))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(220.dp).height(124.dp).clip(RoundedCornerShape(16.dp))) { Img(ui.video.thumbHq, Modifier.fillMaxSize()) }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Headphones, null, Modifier.size(16.dp), tint = Dim)
                Text("  Solo audio", color = Dim, fontSize = 13.sp)
            }
        }
    }
}

// ======================================================================
// Celular en vertical: video arriba y detalles abajo (como YouTube)
// ======================================================================
@Composable
fun PhoneWatchPage(ui: PlayerUi, videoSurface: @Composable () -> Unit, onReload: () -> Unit) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val subs by Store.subs.collectAsState()
    val later by Store.later.collectAsState()
    val downloads by Downloads.items.collectAsState()
    var descOpen by remember { mutableStateOf(false) }
    var dlOpen by remember { mutableStateOf(false) }
    val v = ui.video
    val subscribed = subs.any { s -> s.url == v.channelUrl || (s.channelId != null && v.channelUrl.contains(s.channelId!!)) }
    val dl = downloads.firstOrNull { it.video.id == v.id }

    Column(Modifier.fillMaxSize().background(C.Bg)) {
        Box(Modifier.fillMaxWidth().padding(top = 0.dp)) { videoSurface() }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(v.title, color = C.Hi, fontSize = 18.sp, fontWeight = FontWeight.Bold, lineHeight = 23.sp)
                    Text(
                        listOf(Fmt.views(v.views), v.uploaded).filter { it.isNotBlank() }.joinToString(" · ") + if (descOpen) "" else "  …más",
                        color = C.Low, fontSize = 13.sp,
                        modifier = Modifier.padding(top = 4.dp).pointerInput(Unit) { detectTapGestures { descOpen = !descOpen } },
                    )
                }
            }
            // Canal
            item {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        onClick = { if (v.channelUrl.isNotBlank()) nav.channel(v.channelUrl, v.channel) },
                        modifier = Modifier.weight(1f),
                        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, pressedContainerColor = C.Panel),
                    ) {
                        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(Yt.bestImage(ui.info?.uploaderAvatars, 120), v.channel, 40.dp)
                            Column(Modifier.padding(start = 10.dp)) {
                                Text(v.channel, color = C.Hi, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                ui.info?.uploaderSubscriberCount?.takeIf { it >= 0 }?.let { Text(Fmt.subs(it), color = C.Low, fontSize = 12.sp) }
                            }
                        }
                    }
                    if (v.channelUrl.isNotBlank()) ActionButton(
                        if (subscribed) "Suscrito" else "Suscribirme",
                        {
                            if (subscribed) Store.unsubscribe(v.channelUrl)
                            else {
                                Store.subscribe(com.mauricio.vista.data.Chan(v.channelUrl, v.channel, Yt.bestImage(ui.info?.uploaderAvatars, 240), ui.info?.uploaderSubscriberCount ?: -1))
                                Toast.show("Suscrito a ${v.channel}")
                            }
                        },
                        icon = if (subscribed) Icons.Filled.Check else Icons.Filled.Subscriptions, primary = !subscribed,
                    )
                }
            }
            // Acciones
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        ActionButton(
                            when { dl == null -> "Descargar"; dl.done -> "Descargado"; dl.status == "error" -> "Reintentar"; else -> "${(dl.progress * 100).toInt()}%" },
                            { if (dl == null) dlOpen = true else if (dl.status == "error") Downloads.retry(dl.id) else nav.go(Route.Downloads) },
                            icon = if (dl?.done == true) Icons.Filled.Check else Icons.Filled.Download, active = dl != null,
                        )
                    }
                    item { ActionButton(if (ui.audioOnly) "Ver video" else "Solo audio", { ui.audioOnly = !ui.audioOnly }, icon = Icons.Filled.Headphones, active = ui.audioOnly) }
                    item {
                        val isLater = later.any { it.id == v.id }
                        ActionButton(if (isLater) "Guardado" else "Ver después", { Store.toggleLater(v) }, icon = if (isLater) Icons.Filled.Check else Icons.Filled.WatchLater, active = isLater)
                    }
                    if (Store.tvAddress.isNotBlank()) item {
                        ActionButton("En la tele", {
                            uiScope.launch {
                                val ok = Remote.sendPlay(Yt.watchUrl(v.id))
                                Toast.show(if (ok) "Listo, mira la tele" else "No encontré la tele")
                                if (ok) ui.player.pause()
                            }
                        }, icon = Icons.Filled.Cast)
                    }
                    item {
                        ActionButton("Compartir", {
                            runCatching {
                                ctx.startActivity(android.content.Intent.createChooser(
                                    android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, v.title + "\n" + Yt.watchUrl(v.id)), "Compartir",
                                ))
                            }
                        }, icon = Icons.Filled.Share)
                    }
                    item { ActionButton("Opciones", { ui.panel = "menu" }, icon = Icons.Filled.Settings) }
                }
            }
            // Descripción
            if (descOpen) item {
                Text(
                    ui.info?.description?.content.orEmpty().replace(Regex("<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), "").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").trim(),
                    color = C.Mid, fontSize = 14.sp, lineHeight = 20.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp).background(C.Panel, RoundedCornerShape(14.dp)).padding(14.dp),
                )
            }
            // Capítulos
            val chapters = ui.info?.streamSegments.orEmpty()
            if (chapters.isNotEmpty()) {
                item { Text("Capítulos", color = C.Hi, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 10.dp, bottom = 6.dp)) }
                item {
                    LazyRow(contentPadding = PaddingValues(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(chapters) { c -> Pill(Fmt.clock(c.startTimeSeconds * 1000L) + "  " + c.title, currentChapter(ui) == c.title, { ui.player.seekTo(c.startTimeSeconds * 1000L) }) }
                    }
                }
            }
            // Siguiente y relacionados
            ui.next?.let { n ->
                item { Text("Siguiente", color = C.Hi, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp)) }
                item { VideoRow(n, { nav.play(n) }, Modifier.padding(horizontal = 8.dp), onLongClick = { videoMenu.value = n }) }
            }
            if (ui.related.isNotEmpty()) {
                item { Text("Más videos", color = C.Hi, fontSize = 16.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 14.dp, bottom = 4.dp)) }
                items(ui.related.filter { it.id != ui.next?.id }, key = { "rel" + it.id }) { r ->
                    VideoRow(r, { nav.play(r) }, Modifier.padding(horizontal = 8.dp), onLongClick = { videoMenu.value = r })
                }
            }
        }
    }

    if (dlOpen) {
        Dialog(onDismissRequest = { dlOpen = false }) {
            Column(Modifier.fillMaxWidth().background(C.Panel, RoundedCornerShape(22.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Descargar para ver sin internet", color = C.Hi, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                val hs = ui.info?.let { Sources.heights(it) }.orEmpty().filter { it in listOf(1080, 720, 480, 360) }.ifEmpty { listOf(720, 480, 360) }
                hs.forEach { h ->
                    OptionRow(Fmt.quality(h) + when (h) { 1080 -> "  · máxima nitidez"; 360 -> "  · ocupa poco"; else -> "" }, false, Modifier) {
                        Downloads.add(v, h, false); dlOpen = false; Toast.show("Descargando en ${Fmt.quality(h)}…")
                    }
                }
                OptionRow("Solo audio  · música y pódcast", false, Modifier) { Downloads.add(v, 0, true); dlOpen = false; Toast.show("Descargando el audio…") }
            }
        }
    }
    @Suppress("UNUSED_VARIABLE") val unused = onReload
}
