package com.mauricio.vista.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.DoNotDisturbOn
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import com.mauricio.vista.data.Downloads
import com.mauricio.vista.data.Feed
import com.mauricio.vista.data.Fmt
import com.mauricio.vista.data.Remote
import com.mauricio.vista.data.Store
import com.mauricio.vista.data.Video
import com.mauricio.vista.data.Yt
import kotlinx.coroutines.launch

// ======================================================================
// Menú de un video (clic largo / mantener presionado)
// ======================================================================
@Composable
fun VideoMenu(v: Video, onClose: () -> Unit) {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val fr = remember { FocusRequester() }
    val later by Store.later.collectAsState()
    var showQuality by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.width(if (DeviceUi.isTv) 440.dp else 340.dp).background(C.Panel, RoundedCornerShape(22.dp)).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(v.title, color = C.Hi, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (v.channel.isNotBlank()) Text(v.channel, color = C.Low, fontSize = 13.sp)
            Spacer(Modifier.height(4.dp))
            if (showQuality) {
                Text("¿En qué calidad?", color = C.Mid, fontSize = 14.sp)
                listOf(1080, 720, 480, 360).forEach { q ->
                    MenuRow(Icons.Filled.Download, Fmt.quality(q) + if (q == 1080) " (alta)" else if (q == 360) " (ocupa poco)" else "") {
                        Downloads.add(v, q, false); Toast.show("Descargando en ${Fmt.quality(q)}…"); onClose()
                    }
                }
                MenuRow(Icons.Filled.Headphones, "Solo audio (música, pódcast)") { Downloads.add(v, 0, true); Toast.show("Descargando el audio…"); onClose() }
            } else {
                MenuRow(Icons.Filled.PlayArrow, "Ver ahora", Modifier.focusRequester(fr)) { onClose(); nav.play(v) }
                MenuRow(Icons.Filled.Headphones, "Escuchar (solo audio)") { onClose(); nav.play(v, audioOnly = true) }
                val isLater = later.any { it.id == v.id }
                MenuRow(if (isLater) Icons.Filled.Check else Icons.Filled.WatchLater, if (isLater) "Quitar de «Ver después»" else "Guardar en «Ver después»") {
                    val now = Store.toggleLater(v); Toast.show(if (now) "Guardado en «Ver después»" else "Quitado de «Ver después»"); onClose()
                }
                if (v.channelUrl.isNotBlank()) MenuRow(Icons.Filled.Subscriptions, "Ir al canal") { onClose(); nav.channel(v.channelUrl, v.channel) }
                if (!DeviceUi.isTv) {
                    MenuRow(Icons.Filled.Download, "Descargar") { showQuality = true }
                    if (Store.tvAddress.isNotBlank()) MenuRow(Icons.Filled.Cast, "Ver en la tele") {
                        uiScope.launch { Toast.show(if (Remote.sendPlay(v.url)) "Listo, mira la tele" else "No encontré la tele. ¿Está prendida con Vista abierta?") }
                        onClose()
                    }
                    MenuRow(Icons.Filled.Share, "Compartir") {
                        runCatching {
                            ctx.startActivity(android.content.Intent.createChooser(
                                android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, v.title + "\n" + Yt.watchUrl(v.id)), "Compartir",
                            ))
                        }
                        onClose()
                    }
                }
                if (Store.watched(v.id) != null) MenuRow(Icons.Filled.History, "Quitar del historial") { Store.removeHistory(v.id); onClose() }
                MenuRow(Icons.Filled.Block, "No me interesa") {
                    Store.hideVideo(v.id); com.mauricio.vista.data.Recs.applyDislikes(); Toast.show("Listo, ya no te lo mostraré"); onClose()
                }
                if (v.channelUrl.isNotBlank()) MenuRow(Icons.Filled.DoNotDisturbOn, "No recomendar este canal") {
                    Store.blockChannel(v.channelUrl); com.mauricio.vista.data.Recs.applyDislikes(); Toast.show("No volveré a recomendar ${v.channel.ifBlank { "ese canal" }}"); onClose()
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}

@Composable
fun MenuRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick, modifier = modifier.fillMaxWidth(),
        shape = androidx.tv.material3.ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = androidx.tv.material3.ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = androidx.tv.material3.ClickableSurfaceDefaults.colors(
            containerColor = C.Panel2, contentColor = C.Hi, focusedContainerColor = C.Hi, focusedContentColor = C.Bg, pressedContainerColor = C.Line,
        ),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.tv.material3.Icon(icon, null, Modifier.padding(end = 12.dp))
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ======================================================================
// Suscripciones: canales arriba, novedades abajo
// ======================================================================
@Composable
fun SubsScreen() {
    val nav = LocalNav.current
    val subs by Store.subs.collectAsState()
    val feed by Feed.videos.collectAsState()
    val st by Feed.state.collectAsState()
    val scope = rememberCoroutineScope()
    val mem = remember { nav.memory("subs") }
    val first = remember { FocusRequester() }
    LaunchedEffect(subs.size) { Feed.refresh() }
    val list = if (Store.hideShorts) feed.filterNot { it.short } else feed
    val tv = DeviceUi.isTv

    if (subs.isEmpty()) {
        Column(Modifier.fillMaxSize()) {
            if (!tv) PhoneTopBar("Suscripciones")
            CenterMessage(
                "Aún no sigues ningún canal",
                if (tv) "Busca un canal y presiona «Suscribirme», o pasa tus suscripciones de YouTube desde el celular en Ajustes → Conectar el celular."
                else "Busca un canal y toca «Suscribirme», o importa tus suscripciones de YouTube en Biblioteca → Ajustes.",
            ) { ActionButton("Buscar canales", { nav.go(Route.Search()) }, primary = true) }
        }
        return
    }

    val cols = if (tv) 4 else 1
    val grid = rememberLazyGridState()
    LazyVerticalGrid(
        columns = GridCells.Fixed(cols), state = grid, modifier = Modifier.fillMaxSize(),
        contentPadding = if (tv) PaddingValues(start = 28.dp, end = 28.dp, top = 24.dp, bottom = 60.dp) else PaddingValues(bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(if (tv) 22.dp else 0.dp),
    ) {
        item(span = { GridItemSpan(cols) }) {
            Column {
                if (tv) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Suscripciones", color = C.Hi, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        ActionButton("Actualizar", { scope.launch { Feed.refresh(force = true) } }, icon = Icons.Filled.Refresh)
                        Spacer(Modifier.width(120.dp))
                    }
                    Spacer(Modifier.height(14.dp))
                } else PhoneTopBar("Suscripciones") { ActionButton("", { scope.launch { Feed.refresh(force = true) } }, icon = Icons.Filled.Refresh) }
                LazyRow(
                    contentPadding = PaddingValues(horizontal = if (tv) 0.dp else 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(if (tv) 10.dp else 4.dp),
                ) {
                    itemsIndexed(subs, key = { _, c -> c.url }) { i, c ->
                        ChannelBubble(
                            c, onClick = { nav.channel(c) }, size = if (tv) 72.dp else 58.dp,
                            modifier = Modifier.rememberFocus(mem, "c:" + c.url).then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                        )
                    }
                }
                (st as? Feed.State.Loading)?.let { s ->
                    Text("Revisando ${s.done} de ${s.total} canales…", color = C.Low, fontSize = 12.sp, modifier = Modifier.padding(start = if (tv) 0.dp else 16.dp, top = 8.dp))
                }
                Spacer(Modifier.height(if (tv) 4.dp else 12.dp))
            }
        }
        itemsIndexed(list, key = { _, v -> "v:" + v.id }) { i, v ->
            val queue = list.drop(i + 1).take(20)
            if (tv) VideoCard(v, onClick = { nav.play(v, queue) }, width = 190.dp, modifier = Modifier.rememberFocus(mem, "v:" + v.id), onLongClick = { videoMenu.value = v })
            else PhoneVideoCard(v, onClick = { nav.play(v, queue) }, onLongClick = { videoMenu.value = v })
        }
    }
    RestoreFocus(mem, first)
}

// ======================================================================
// Canal
// ======================================================================
@Composable
fun ChannelScreen(url: String, name: String) {
    val nav = LocalNav.current
    val subs by Store.subs.collectAsState()
    var page by remember { mutableStateOf<Yt.ChannelPage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val videos = remember { mutableStateListOf<Video>() }
    var more by remember { mutableStateOf<(suspend () -> com.mauricio.vista.data.Paged<Video>)?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val subFr = remember { FocusRequester() }
    val tv = DeviceUi.isTv

    LaunchedEffect(url) {
        runCatching { Yt.channel(url) }
            .onSuccess { p -> page = p; videos.clear(); videos.addAll(p.videos.items); more = p.videos.more; Store.refreshChan(p.chan) }
            .onFailure { error = Yt.friendly(it) }
    }

    fun loadMore() {
        val m = more ?: return
        if (loadingMore) return
        loadingMore = true
        scope.launch {
            runCatching { m() }.onSuccess { p -> videos.addAll(p.items.filter { n -> videos.none { it.id == n.id } }); more = p.more }
            loadingMore = false
        }
    }

    val p = page
    when {
        error != null -> CenterMessage("No se pudo abrir el canal", error.orEmpty()) { ActionButton("Regresar", { nav.back() }) }
        p == null -> CenterMessage(name.ifBlank { "Canal" }, "Cargando…", loading = true)
        else -> {
            val subscribed = subs.any { s -> s.url == p.chan.url || (s.channelId != null && s.channelId == p.chan.channelId) }
            val cols = if (tv) 4 else 1
            val grid = rememberLazyGridState()
            LaunchedEffect(grid) {
                snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
                    if (last >= videos.size - 6) loadMore()
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(cols), state = grid, modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = if (tv) 28.dp else 0.dp, end = if (tv) 28.dp else 0.dp, bottom = 60.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(if (tv) 22.dp else 0.dp),
            ) {
                item(span = { GridItemSpan(cols) }) {
                    Box(Modifier.fillMaxWidth().height(if (tv) 230.dp else 210.dp)) {
                        HeroBackdrop(p.banner, null, Modifier.fillMaxWidth().height(if (tv) 150.dp else 110.dp))
                        if (!tv) ActionButton("", { nav.back() }, Modifier.padding(10.dp), icon = Icons.AutoMirrored.Filled.ArrowBack)
                        Row(
                            Modifier.align(Alignment.BottomStart).padding(horizontal = if (tv) 0.dp else 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Avatar(p.chan.avatar, p.chan.name, if (tv) 88.dp else 64.dp)
                            Spacer(Modifier.width(16.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.chan.name, color = C.Hi, fontSize = if (tv) 26.sp else 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(listOf(Fmt.subs(p.chan.subs)).filter { it.isNotBlank() }.joinToString(" · "), color = C.Mid, fontSize = 13.sp)
                            }
                            ActionButton(
                                if (subscribed) "Suscrito" else "Suscribirme",
                                {
                                    if (subscribed) { Store.unsubscribe(p.chan.url); Toast.show("Ya no sigues a ${p.chan.name}") }
                                    else { Store.subscribe(p.chan); Toast.show("Suscrito a ${p.chan.name}") }
                                },
                                Modifier.focusRequester(subFr), icon = if (subscribed) Icons.Filled.Check else Icons.Filled.Subscriptions, primary = !subscribed,
                            )
                            if (tv) Spacer(Modifier.width(100.dp))
                        }
                    }
                }
                if (videos.isEmpty()) item(span = { GridItemSpan(cols) }) { EmptyHint("Sin videos", "Este canal no tiene videos públicos.") }
                itemsIndexed(videos, key = { _, v -> v.id }) { i, v ->
                    val queue = videos.drop(i + 1).take(20)
                    if (tv) VideoCard(v, onClick = { nav.play(v, queue) }, width = 190.dp, showChannel = false, onLongClick = { videoMenu.value = v })
                    else PhoneVideoCard(v, onClick = { nav.play(v, queue) }, onLongClick = { videoMenu.value = v }, showChannel = false)
                }
                if (loadingMore) item(span = { GridItemSpan(cols) }) { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Spinner() } }
            }
            LaunchedEffect(Unit) { kotlinx.coroutines.delay(150); runCatching { subFr.requestFocus() } }
        }
    }
}
