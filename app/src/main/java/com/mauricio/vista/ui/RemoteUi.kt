package com.mauricio.vista.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Replay30
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.mauricio.vista.data.Fmt
import com.mauricio.vista.data.Item
import com.mauricio.vista.data.Remote
import com.mauricio.vista.data.Store
import com.mauricio.vista.data.Video
import com.mauricio.vista.data.Yt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private fun JSONArray?.videos(): List<Video> = if (this == null) emptyList() else List(length()) { Video.fromJson(getJSONObject(it)) }

/** Manda un video a la cola de la tele (o lo pone a continuación). */
fun sendToTvQueue(v: Video, next: Boolean = false) {
    uiScope.launch {
        val ok = Remote.queue(if (next) "next" else "add", v)
        Toast.show(if (!ok) "No encontré la tele" else if (next) "Será lo siguiente en la tele" else "Agregado a la cola de la tele")
    }
}

/** Abre un video en la tele. */
fun playOnTv(v: Video, nav: Nav? = null) {
    uiScope.launch {
        val ok = Remote.sendPlay(v.url)
        Toast.show(if (ok) "Listo, mira la tele" else "No encontré la tele. ¿Está prendida con Vista abierta y en el mismo Wi-Fi?")
        if (ok && nav != null) nav.go(Route.RemoteTv)
    }
}

/**
 * Control de la tele desde el celular: lo que se está viendo, pausa, adelantar/regresar (con barra que se arrastra),
 * siguiente, la cola «A continuación» de la tele y buscar videos para agregarlos.
 */
@Composable
fun RemoteTvScreen() {
    val nav = LocalNav.current
    var state by remember { mutableStateOf<JSONObject?>(null) }
    var reachable by remember { mutableStateOf(true) }
    var tick by remember { mutableIntStateOf(0) }
    var dragging by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Video>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val tvName = Store.devices.let { l -> (l.firstOrNull { it.tv } ?: l.firstOrNull())?.name }.orEmpty().ifBlank { "la tele" }

    // Se pregunta a la tele cada segundo (sin estorbar mientras arrastras la barra)
    LaunchedEffect(tick) {
        while (true) {
            if (!dragging) {
                val s = Remote.now()
                if (s != null) { state = s; reachable = true } else if (state == null || reachable) reachable = false
            }
            delay(1000)
        }
    }
    fun act(a: String, v: Long = 0) {
        uiScope.launch {
            if (!Remote.control(a, v)) Toast.show("La tele no respondió")
            Remote.now()?.let { state = it }
        }
    }
    fun queueAct(a: String, id: String = "") {
        uiScope.launch { Remote.queue(a, id = id); Remote.now()?.let { state = it } }
    }

    val s = state
    val video = s?.optJSONObject("video")?.let { Video.fromJson(it) }
    val queue = s?.optJSONArray("cola").videos()
    val related = s?.optJSONArray("related").videos()
    val next = s?.optJSONObject("next")?.let { Video.fromJson(it) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 30.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, end = 12.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                ActionButton("", { nav.back() }, icon = Icons.AutoMirrored.Filled.ArrowBack)
                Column(Modifier.weight(1f).padding(start = 6.dp)) {
                    Text("Control de la tele", color = C.Hi, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text(if (reachable) tvName else "Buscando $tvName…", color = if (reachable) C.Low else C.Live, fontSize = 12.sp)
                }
            }
        }
        if (!reachable && s == null) {
            item {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("No encuentro la tele. Revisa que esté prendida, con Vista abierta y en el mismo Wi-Fi que este celular.", color = C.Mid, fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        ActionButton("Volver a buscar", { tick++ }, primary = true)
                        ActionButton("Conectar otra vez", { nav.go(Route.Connect) })
                    }
                }
            }
        }
        // Lo que se está viendo
        if (s != null) item {
            if (video == null || video.url.isBlank()) {
                Text("La tele no está reproduciendo nada. Busca abajo un video o mándalo desde cualquier video con «En la tele».",
                    color = C.Mid, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
            } else {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)).background(C.Panel2)) {
                        Img(video.thumbHq, Modifier.fillMaxSize())
                        if (s.optBoolean("buffering")) Box(Modifier.fillMaxSize().background(Color(0x66000000)), contentAlignment = Alignment.Center) { Spinner() }
                    }
                    Text(video.title, color = C.Hi, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 10.dp))
                    Text(video.channel, color = C.Low, fontSize = 13.sp, maxLines = 1)
                    s.optString("error").takeIf { it.isNotBlank() }?.let { Text(it, color = C.Live, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp)) }
                    Spacer(Modifier.height(8.dp))
                    RemoteSeek(
                        pos = s.optLong("pos"), dur = s.optLong("dur"), live = s.optBoolean("live"),
                        onDrag = { dragging = it },
                        onSeek = { ms -> state = JSONObject(s.toString()).put("pos", ms); act("seekto", ms) },
                        onLive = { act("live") },
                    )
                    // Botones grandes
                    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        RemoteBtn(Icons.Filled.Replay30, "Regresar 30 s", 28.dp) { act("seek", -30_000) }
                        RemoteBtn(Icons.Filled.Replay10, "Regresar 10 s", 30.dp) { act("seek", -10_000) }
                        RemoteBtn(if (s.optBoolean("playing")) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Pausa", 44.dp, primary = true) {
                            state = JSONObject(s.toString()).put("playing", !s.optBoolean("playing")); act("toggle")
                        }
                        RemoteBtn(Icons.Filled.Forward10, "Adelantar 10 s", 30.dp) { act("seek", 10_000) }
                        RemoteBtn(Icons.Filled.Forward30, "Adelantar 30 s", 28.dp) { act("seek", 30_000) }
                    }
                    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (next != null) ActionButton("Siguiente", { act("next") }, icon = Icons.Filled.SkipNext)
                        ActionButton("Desde el inicio", { act("restart") }, icon = Icons.Filled.Replay)
                        ActionButton(if (s.optBoolean("audio")) "Ver video" else "Solo audio", { act("audio") }, icon = Icons.Filled.Headphones, active = s.optBoolean("audio"))
                        ActionButton("", { act("stop") }, icon = Icons.Filled.Stop)
                    }
                    if (next != null) Text("Sigue: ${next.title}", color = C.Low, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        // La cola de la tele
        if (s != null) {
            item {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 10.dp, top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("A continuación en la tele" + if (queue.isNotEmpty()) " (${queue.size})" else "", color = C.Hi, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    val rep = s.optString("repetir", "off")
                    SmallIcon(if (rep == "one") Icons.Filled.RepeatOne else Icons.Filled.Repeat, "Repetir") { queueAct("repeat") }
                    SmallIcon(Icons.Filled.Shuffle, "Revolver") { queueAct("shuffle") }
                    if (queue.isNotEmpty()) SmallIcon(Icons.Filled.Close, "Vaciar") { queueAct("clear") }
                }
            }
            if (queue.isEmpty()) item {
                Text("Vacía. Agrega videos con «+» aquí abajo, o desde cualquier video del celular (mantén presionado → «Agregar a la cola de la tele»).",
                    color = C.Low, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            }
            itemsIndexed(queue, key = { _, v -> "q" + v.id }) { i, v ->
                VideoRow(v, { playOnTv(v) }, Modifier.padding(horizontal = 8.dp), trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", color = C.Low, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
                        SmallIcon(Icons.Filled.KeyboardArrowUp, "Subir") { queueAct("up", v.id) }
                        SmallIcon(Icons.Filled.KeyboardArrowDown, "Bajar") { queueAct("down", v.id) }
                        SmallIcon(Icons.Filled.Close, "Quitar") { queueAct("remove", v.id) }
                    }
                })
            }
        }
        // Buscar y agregar
        item {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp)) {
                Text("Buscar para la tele", color = C.Hi, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
                TvTextField(query, { query = it }, "¿Qué quieres ver en la tele?", Modifier.fillMaxWidth(), imeAction = ImeAction.Search, leading = Icons.Filled.Search,
                    onImeAction = {
                        if (query.isNotBlank()) {
                            searching = true
                            uiScope.launch {
                                results = runCatching { Yt.search(query.trim(), filter = "videos").items.filterIsInstance<Item.V>().map { it.v } }.getOrDefault(emptyList())
                                    .let { r -> if (Store.hideShorts) r.filterNot { it.short } else r }
                                searching = false
                            }
                        }
                    })
                if (searching) Spinner(Modifier.padding(top = 10.dp))
            }
        }
        items(results, key = { "s" + it.id }) { v -> TvVideoRow(v) }
        // Recomendados del video que está en la tele
        if (related.isNotEmpty() && results.isEmpty()) {
            item { Text("Más videos", color = C.Hi, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp)) }
            items(related, key = { "r" + it.id }) { v -> TvVideoRow(v) }
        }
    }
}

/** Renglón: tocar = ver en la tele; «+» = a la cola de la tele. */
@Composable
private fun TvVideoRow(v: Video) {
    VideoRow(v, { playOnTv(v) }, Modifier.padding(horizontal = 8.dp), onLongClick = { sendToTvQueue(v, next = true) }, trailing = {
        SmallIcon(Icons.AutoMirrored.Filled.PlaylistAdd, "Agregar a la cola de la tele") { sendToTvQueue(v) }
    })
}

@Composable
private fun RemoteBtn(icon: ImageVector, label: String, size: Dp, primary: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(CircleShape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 0.9f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) C.Accent else C.Panel2, contentColor = if (primary) C.OnAccent else C.Hi,
            focusedContainerColor = if (primary) C.Accent else C.Panel2, pressedContainerColor = C.Line,
        ),
    ) { Icon(icon, label, Modifier.padding(size * 0.35f).size(size)) }
}

/** Barra que se arrastra con el dedo; en vivo, permite regresar hasta donde guarde la transmisión. */
@Composable
private fun RemoteSeek(pos: Long, dur: Long, live: Boolean, onDrag: (Boolean) -> Unit, onSeek: (Long) -> Unit, onLive: () -> Unit) {
    var dragFrac by remember { mutableFloatStateOf(-1f) }
    val d = dur.coerceAtLeast(1)
    val frac = if (dragFrac >= 0) dragFrac else (pos.toFloat() / d).coerceIn(0f, 1f)
    if (dur > 0) Box(
        Modifier.fillMaxWidth().height(34.dp)
            .pointerInput(dur) { detectTapGestures { o -> onSeek((o.x / size.width * d).toLong()) } }
            .pointerInput(dur) {
                detectHorizontalDragGestures(
                    onDragStart = { o -> dragFrac = (o.x / size.width).coerceIn(0f, 1f); onDrag(true) },
                    onDragEnd = { if (dragFrac >= 0) onSeek((dragFrac * d).toLong()); dragFrac = -1f; onDrag(false) },
                    onDragCancel = { dragFrac = -1f; onDrag(false) },
                    onHorizontalDrag = { ch, _ -> dragFrac = (ch.position.x / size.width).coerceIn(0f, 1f) },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(if (dragFrac >= 0) 8.dp else 5.dp)) {
            val r = CornerRadius(size.height / 2, size.height / 2)
            drawRoundRect(C.Line, size = size, cornerRadius = r)
            drawRoundRect(if (live) C.Live else C.Accent, size = Size(size.width * frac, size.height), cornerRadius = r)
            drawCircle(Color.White, radius = size.height * 1.4f, center = Offset(size.width * frac, size.height / 2))
        }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val shown = if (dragFrac >= 0) (dragFrac * d).toLong() else pos
        if (live) {
            val behind = (dur - shown).coerceAtLeast(0)
            Text(if (dur <= 0 || behind < 20_000) "En el directo" else "−" + Fmt.clock(behind) + " del directo", color = C.Low, fontSize = 12.sp, modifier = Modifier.weight(1f))
            ActionButton("● En vivo", onLive, active = dur <= 0 || behind < 20_000)
        } else {
            Text(Fmt.clock(shown), color = C.Low, fontSize = 12.sp, modifier = Modifier.weight(1f))
            Text(Fmt.clock(dur), color = C.Low, fontSize = 12.sp)
        }
    }
}

