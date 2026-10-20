package com.mauricio.vista.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.mauricio.vista.data.Fmt
import com.mauricio.vista.data.Item
import com.mauricio.vista.data.Paged
import com.mauricio.vista.data.Playlists
import com.mauricio.vista.data.Queue
import com.mauricio.vista.data.Video
import com.mauricio.vista.data.Yt
import kotlinx.coroutines.launch

/** Video al que se le está escogiendo lista (desde el menú del video). */
val saveToListVideo = mutableStateOf<List<Video>?>(null)

// ======================================================================
// Tarjeta de lista (búsqueda) y tarjeta de lista guardada (biblioteca)
// ======================================================================
@Composable
fun PlaylistCard(p: Item.P, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp? = null) {
    ListTile(p.name, listOf(p.owner, if (p.count > 0) "${p.count} videos" else "").filter { it.isNotBlank() }.joinToString(" · "), p.thumb, onClick, modifier, width)
}

@Composable
fun ListTile(title: String, sub: String, thumb: String?, onClick: () -> Unit, modifier: Modifier = Modifier, width: Dp? = null, onLongClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(12.dp)
    var focused by remember { mutableStateOf(false) }
    Column(modifier.then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth().padding(horizontal = if (DeviceUi.isTv) 0.dp else 12.dp, vertical = 6.dp))) {
        Surface(
            onClick = onClick, onLongClick = onLongClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).onFocusChanged { focused = it.isFocused },
            shape = ClickableSurfaceDefaults.shape(shape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, C.Hi), shape = shape)),
            colors = ClickableSurfaceDefaults.colors(containerColor = C.Panel2, focusedContainerColor = C.Panel2),
        ) {
            Box(Modifier.fillMaxSize()) {
                Img(thumb, Modifier.fillMaxSize())
                // Franja a la derecha, como las listas de YouTube
                Box(Modifier.align(Alignment.CenterEnd).fillMaxSize().padding(start = 0.dp).background(Brush.horizontalGradient(0.55f to Color.Transparent, 1f to Color(0xCC000000))))
                Row(Modifier.align(Alignment.BottomEnd).padding(8.dp).background(Color(0xCC000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.QueueMusic, null, Modifier.size(14.dp), tint = Color.White)
                    Text("  Lista", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
        Text(title, color = if (focused || !DeviceUi.isTv) C.Hi else C.Mid, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp, start = 4.dp))
        if (sub.isNotBlank()) Text(sub, color = C.Low, fontSize = 12.sp, maxLines = 1, modifier = Modifier.padding(start = 4.dp))
    }
}

// ======================================================================
// Encabezado común de una lista (YouTube o propia)
// ======================================================================
@Composable
private fun ListHeader(
    title: String, sub: String, thumb: String?, videos: List<Video>,
    extra: (@Composable () -> Unit)? = null, firstFocus: FocusRequester? = null,
) {
    val nav = LocalNav.current
    val tv = DeviceUi.isTv
    Column(Modifier.fillMaxWidth().padding(horizontal = if (tv) 28.dp else 16.dp, vertical = if (tv) 24.dp else 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!tv) { ActionButton("", { nav.back() }, icon = Icons.AutoMirrored.Filled.ArrowBack); Spacer(Modifier.width(8.dp)) }
            Box(Modifier.width(if (tv) 200.dp else 120.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(C.Panel2)) { Img(thumb, Modifier.fillMaxSize()) }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, color = C.Hi, fontSize = if (tv) 26.sp else 20.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (sub.isNotBlank()) Text(sub, color = C.Mid, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Spacer(Modifier.height(14.dp))
        Wrap {
            ActionButton("Reproducir todo", { nav.playAll(videos) }, if (firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier, icon = Icons.Filled.PlayArrow, primary = true)
            ActionButton("Aleatorio", { nav.playAll(videos, shuffle = true) }, icon = Icons.Filled.Shuffle)
            ActionButton("Agregar a la cola", { Queue.addAll(videos); Toast.show("${videos.size} videos agregados a «A continuación»") }, icon = Icons.AutoMirrored.Filled.PlaylistAdd)
            extra?.invoke()
        }
    }
}

// ======================================================================
// Lista de YouTube
// ======================================================================
@Composable
fun YtPlaylistScreen(url: String, name: String) {
    val nav = LocalNav.current
    var page by remember { mutableStateOf<Yt.PlaylistPage?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val videos = remember { mutableStateListOf<Video>() }
    var more by remember { mutableStateOf<(suspend () -> Paged<Video>)?>(null) }
    var loadingMore by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val first = remember { FocusRequester() }
    LaunchedEffect(url) {
        runCatching { Yt.playlist(url) }
            .onSuccess { p -> page = p; videos.clear(); videos.addAll(p.videos.items); more = p.videos.more }
            .onFailure { error = Yt.friendly(it) }
    }
    val state = rememberLazyListState()
    LaunchedEffect(state) {
        snapshotFlow { state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            val m = more
            if (m != null && !loadingMore && last >= videos.size - 4) {
                loadingMore = true
                scope.launch { runCatching { m() }.onSuccess { p -> videos.addAll(p.items.filter { n -> videos.none { it.id == n.id } }); more = p.more }; loadingMore = false }
            }
        }
    }
    val p = page
    when {
        error != null -> CenterMessage("No se pudo abrir la lista", error.orEmpty()) { ActionButton("Regresar", { nav.back() }) }
        p == null -> CenterMessage(name.ifBlank { "Lista" }, "Cargando…", loading = true)
        else -> LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = 40.dp)) {
            item {
                ListHeader(p.name, listOf(p.owner, if (p.count > 0) "${p.count} videos" else "").filter { it.isNotBlank() }.joinToString(" · "), p.thumb.ifBlank { videos.firstOrNull()?.thumbHq }, videos, firstFocus = first, extra = {
                    ActionButton("Guardar en mis listas", {
                        val pl = Playlists.create(p.name, videos)
                        Toast.show("Guardada en tus listas: ${pl.name}")
                    }, icon = Icons.Filled.Add)
                })
            }
            itemsIndexed(videos, key = { _, v -> v.id }) { i, v ->
                VideoRow(v, { nav.play(v, videos.drop(i + 1)) }, Modifier.padding(horizontal = if (DeviceUi.isTv) 22.dp else 8.dp), progress = com.mauricio.vista.data.Store.watched(v.id)?.progress ?: 0f, onLongClick = { videoMenu.value = v })
            }
            if (loadingMore) item { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Spinner() } }
        }
    }
    LaunchedEffect(p != null) { if (p != null) { kotlinx.coroutines.delay(120); runCatching { first.requestFocus() } } }
}

// ======================================================================
// Lista guardada
// ======================================================================
@Composable
fun MyListScreen(id: String) {
    val nav = LocalNav.current
    val lists by Playlists.lists.collectAsState()
    val pl = lists.firstOrNull { it.id == id }
    val first = remember { FocusRequester() }
    var confirmDelete by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    if (pl == null) { CenterMessage("Esta lista ya no existe") { ActionButton("Regresar", { nav.back() }) }; return }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 40.dp)) {
        item {
            ListHeader(pl.name, "${pl.videos.size} videos · tu lista", pl.videos.firstOrNull()?.thumbHq, pl.videos, firstFocus = first, extra = {
                ActionButton("Cambiar nombre", { renaming = true })
                ActionButton("Borrar lista", { confirmDelete = true }, icon = Icons.Filled.Delete)
            })
        }
        if (pl.videos.isEmpty()) item { EmptyHint("Lista vacía", "Mantén presionado cualquier video y elige «Guardar en una lista».") }
        itemsIndexed(pl.videos, key = { _, v -> v.id }) { i, v ->
            VideoRow(
                v, { nav.play(v, pl.videos.drop(i + 1)) }, Modifier.padding(horizontal = if (DeviceUi.isTv) 22.dp else 8.dp),
                progress = com.mauricio.vista.data.Store.watched(v.id)?.progress ?: 0f, onLongClick = { videoMenu.value = v },
                trailing = {
                    Row {
                        SmallIcon(Icons.Filled.KeyboardArrowUp, "Subir") { Playlists.move(pl.id, v.id, -1) }
                        SmallIcon(Icons.Filled.KeyboardArrowDown, "Bajar") { Playlists.move(pl.id, v.id, 1) }
                        SmallIcon(Icons.Filled.Close, "Quitar") { Playlists.removeVideo(pl.id, v.id) }
                    }
                },
            )
        }
    }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(120); runCatching { first.requestFocus() } }
    if (confirmDelete) ConfirmDialog("¿Borrar «${pl.name}»?", "Los videos no se borran de YouTube, solo esta lista.", "Borrar", { confirmDelete = false; nav.back(); Playlists.delete(pl.id) }, { confirmDelete = false })
    if (renaming) NameDialog("Nombre de la lista", pl.name, { Playlists.rename(pl.id, it); renaming = false }) { renaming = false }
}

@Composable
fun SmallIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, contentColor = C.Low, focusedContainerColor = C.Hi, focusedContentColor = C.Bg, pressedContainerColor = C.Panel2),
    ) { Icon(icon, label, Modifier.padding(8.dp).size(20.dp)) }
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val fr = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(400.dp).background(C.Panel, RoundedCornerShape(22.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = C.Hi, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(message, color = C.Mid, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("Cancelar", onDismiss, Modifier.focusRequester(fr))
                ActionButton(confirm, onConfirm, primary = true)
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}

@Composable
fun NameDialog(title: String, initial: String, onDone: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(initial) }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(420.dp).background(C.Panel, RoundedCornerShape(22.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, color = C.Hi, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            TvTextField(text, { text = it }, "Ej. Para el gym", Modifier.fillMaxWidth(), imeAction = androidx.compose.ui.text.input.ImeAction.Done, onImeAction = { if (text.isNotBlank()) onDone(text) }, startEditing = !DeviceUi.isTv)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("Listo", { if (text.isNotBlank()) onDone(text) }, primary = true)
                ActionButton("Cancelar", onDismiss)
            }
        }
    }
}

/** «Guardar en una lista»: escoger una lista o crear una nueva. */
@Composable
fun SaveToListDialog(videos: List<Video>, onClose: () -> Unit) {
    val lists by Playlists.lists.collectAsState()
    var creating by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    if (creating) {
        NameDialog("Nueva lista", "", { name ->
            val pl = Playlists.create(name, videos)
            Toast.show("Guardado en «${pl.name}»"); onClose()
        }) { creating = false }
        return
    }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.width(400.dp).background(C.Panel, RoundedCornerShape(22.dp)).verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Guardar en una lista", color = C.Hi, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            MenuRow(Icons.Filled.Add, "Nueva lista…", Modifier.focusRequester(fr)) { creating = true }
            lists.forEach { pl ->
                val has = videos.size == 1 && pl.videos.any { it.id == videos[0].id }
                MenuRow(if (has) Icons.Filled.Check else Icons.AutoMirrored.Filled.QueueMusic, pl.name + "  (${pl.videos.size})") {
                    if (has) { Playlists.removeVideo(pl.id, videos[0].id); Toast.show("Quitado de «${pl.name}»") }
                    else { Playlists.addAll(pl.id, videos); Toast.show("Guardado en «${pl.name}»") }
                    onClose()
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}

// ======================================================================
// «A continuación»: la cola (en el reproductor y en Biblioteca)
// ======================================================================
@Composable
fun QueueList(current: Video?, onPlay: (Video) -> Unit, modifier: Modifier = Modifier, firstFocus: FocusRequester? = null, compactHeader: Boolean = false) {
    val items by Queue.items.collectAsState()
    val repeat by Queue.repeat.collectAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!compactHeader) Text("A continuación", color = C.Hi, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            else Spacer(Modifier.weight(1f))
            ActionButton(
                "", { val r = Queue.cycleRepeat(); Toast.show(when (r) { "all" -> "Repetir la lista"; "one" -> "Repetir este video"; else -> "Sin repetir" }) },
                if (firstFocus != null) Modifier.focusRequester(firstFocus) else Modifier,
                icon = if (repeat == "one") Icons.Filled.RepeatOne else Icons.Filled.Repeat, active = repeat != "off",
            )
            Spacer(Modifier.width(6.dp))
            ActionButton("", { Queue.shuffle(); Toast.show("Cola revuelta") }, icon = Icons.Filled.Shuffle)
            Spacer(Modifier.width(6.dp))
            ActionButton("", { saveToListVideo.value = items }, icon = Icons.AutoMirrored.Filled.PlaylistAdd)
            Spacer(Modifier.width(6.dp))
            ActionButton("Vaciar", { Queue.clear() })
        }
        if (items.isEmpty()) {
            Text(
                "La cola está vacía. Toca «+» junto a cualquier video recomendado (o mantén presionado un video → «Agregar a la cola») y se verán en ese orden.",
                color = C.Low, fontSize = 13.sp, modifier = Modifier.padding(vertical = 6.dp),
            )
        }
        items.forEachIndexed { i, v ->
            VideoRow(
                v, { onPlay(v) }, onLongClick = { videoMenu.value = v },
                trailing = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("${i + 1}", color = C.Low, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 4.dp))
                        SmallIcon(Icons.Filled.KeyboardArrowUp, "Subir") { Queue.move(v.id, -1) }
                        SmallIcon(Icons.Filled.KeyboardArrowDown, "Bajar") { Queue.move(v.id, 1) }
                        SmallIcon(Icons.Filled.Close, "Quitar") { Queue.remove(v.id) }
                    }
                },
            )
        }
        if (current != null && items.isNotEmpty()) Text("Después de la cola sigue lo recomendado.", color = C.Low, fontSize = 12.sp)
    }
}

/** Botón «+» para agregar a la cola desde cualquier renglón de video. */
@Composable
fun AddToQueueButton(v: Video) {
    val items by Queue.items.collectAsState()
    val inQueue = items.any { it.id == v.id }
    SmallIcon(if (inQueue) Icons.Filled.Check else Icons.AutoMirrored.Filled.PlaylistAdd, if (inQueue) "En la cola" else "Agregar a la cola") {
        if (inQueue) { Queue.remove(v.id); Toast.show("Quitado de la cola") }
        else { Queue.add(v); Toast.show("Agregado a la cola (${Queue.items.value.size})") }
    }
}
