package com.mauricio.vista.ui

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NorthWest
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.mauricio.vista.data.Fmt
import com.mauricio.vista.data.Item
import com.mauricio.vista.data.Paged
import com.mauricio.vista.data.Store
import com.mauricio.vista.data.Yt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun SearchScreen(initial: String) {
    val nav = LocalNav.current
    val tv = DeviceUi.isTv
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf(initial) }
    var filter by remember { mutableStateOf("") }
    var suggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    val results = remember { mutableStateListOf<Item>() }
    var more by remember { mutableStateOf<(suspend () -> Paged<Item>)?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val recent by Store.searches.collectAsState()
    val fieldFr = remember { FocusRequester() }
    val mem = remember { nav.memory("search") }

    fun run(q: String) {
        val t = q.trim()
        if (t.isBlank()) return
        text = t; query = t; suggestions = emptyList()
        Store.addSearch(t)
    }

    LaunchedEffect(query, filter) {
        if (query.isBlank()) return@LaunchedEffect
        loading = true; error = null; results.clear(); more = null
        runCatching { Yt.search(query, filter = filter) }
            .onSuccess { p -> results.addAll(p.items.filter { it !is Item.V || !(Store.hideShorts && it.v.short) }); more = p.more }
            .onFailure { error = Yt.friendly(it) }
        loading = false
    }
    LaunchedEffect(text) {
        if (text == query) return@LaunchedEffect
        delay(250)
        suggestions = Yt.suggestions(text)
    }

    fun loadMore() {
        val m = more ?: return
        if (loadingMore) return
        loadingMore = true
        scope.launch {
            runCatching { m() }.onSuccess { p -> results.addAll(p.items.filter { it !is Item.V || !(Store.hideShorts && it.v.short) }); more = p.more }
            loadingMore = false
        }
    }

    val voice = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK) {
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { run(it) }
        }
    }
    fun listen() {
        runCatching {
            voice.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-MX")
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "¿Qué quieres ver?"),
            )
        }.onFailure { Toast.show("Este aparato no tiene búsqueda por voz") }
    }

    val cols = if (tv) 4 else phoneCols()
    val grid = rememberLazyGridState()
    LaunchedEffect(grid) {
        snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }.collect { last ->
            if (results.isNotEmpty() && last >= results.size - 6) loadMore()
        }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(cols), state = grid, modifier = Modifier.fillMaxSize(),
        contentPadding = if (tv) PaddingValues(start = 28.dp, end = 28.dp, top = 22.dp, bottom = 60.dp) else PaddingValues(bottom = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(if (tv) 22.dp else 0.dp),
    ) {
        item(span = { GridItemSpan(cols) }) {
            Column(Modifier.padding(horizontal = if (tv) 0.dp else 12.dp, vertical = if (tv) 0.dp else 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TvTextField(
                        text, { text = it }, "Buscar videos y canales",
                        Modifier.weight(1f), onImeAction = { run(text) }, leading = Icons.Filled.Search,
                        boxRequester = fieldFr, startEditing = !tv && initial.isBlank(),
                    )
                    Spacer(Modifier.width(10.dp))
                    if (text.isNotEmpty() && !tv) { ActionButton("", { text = ""; query = ""; results.clear() }, icon = Icons.Filled.Close); Spacer(Modifier.width(6.dp)) }
                    ActionButton("", { listen() }, icon = Icons.Filled.Mic)
                    if (tv) Spacer(Modifier.width(110.dp))
                }
                // Sugerencias mientras se escribe
                if (suggestions.isNotEmpty() && text != query) {
                    Spacer(Modifier.height(10.dp))
                    if (tv) Wrap { suggestions.forEach { s -> Pill(s, false, { run(s) }, icon = Icons.Filled.Search) } }
                    else Column { suggestions.forEach { s -> SuggestionRow(s, Icons.Filled.Search, { run(s) }, { text = s }) } }
                } else if (query.isBlank() && recent.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text("Búsquedas recientes", color = C.Mid, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))
                    if (tv) Wrap { recent.take(12).forEach { s -> Pill(s, false, { run(s) }, icon = Icons.Filled.History) } }
                    else Column { recent.take(12).forEach { s -> SuggestionRow(s, Icons.Filled.History, { run(s) }, { text = s }) } }
                    Spacer(Modifier.height(8.dp))
                    ActionButton("Borrar búsquedas", { Store.clearSearches() })
                }
                if (query.isNotBlank() && (suggestions.isEmpty() || text == query)) {
                    Spacer(Modifier.height(10.dp))
                    Wrap {
                        listOf("" to "Todo", "videos" to "Videos", "channels" to "Canales", "playlists" to "Listas").forEach { (k, label) ->
                            Pill(label, filter == k, { filter = k })
                        }
                    }
                }
                if (tv) Spacer(Modifier.height(8.dp))
            }
        }
        when {
            loading -> item(span = { GridItemSpan(cols) }) { Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) { Spinner() } }
            error != null -> item(span = { GridItemSpan(cols) }) { EmptyHint("No se pudo buscar", error.orEmpty()) }
            query.isNotBlank() && results.isEmpty() -> item(span = { GridItemSpan(cols) }) { EmptyHint("Sin resultados", "Prueba con otras palabras.") }
            else -> itemsIndexed(results, key = { i, r -> i.toString() + (when (r) { is Item.V -> r.v.id; is Item.C -> r.c.url; is Item.P -> r.url }) }) { i, r ->
                when (r) {
                    is Item.V -> {
                        val queue = results.drop(i + 1).filterIsInstance<Item.V>().map { x -> x.v }.take(20)
                        if (tv) VideoCard(r.v, onClick = { nav.play(r.v, queue) }, width = 190.dp, modifier = Modifier.rememberFocus(mem, r.v.id), onLongClick = { videoMenu.value = r.v })
                        else PhoneVideoCard(r.v, onClick = { nav.play(r.v, queue) }, onLongClick = { videoMenu.value = r.v })
                    }
                    is Item.C -> ChannelResult(r.c) { nav.channel(r.c) }
                    is Item.P -> PlaylistCard(r, { nav.go(Route.Playlist(r.url, r.name)) })
                }
            }
        }
        if (loadingMore) item(span = { GridItemSpan(cols) }) { Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Spinner() } }
    }
    LaunchedEffect(Unit) { if (tv && initial.isBlank()) { delay(120); runCatching { fieldFr.requestFocus() } } }
}

@Composable
private fun SuggestionRow(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, onFill: () -> Unit) {
    Surface(
        onClick = onClick, modifier = Modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(10.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent, pressedContainerColor = C.Panel, focusedContainerColor = C.Panel),
    ) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = C.Low)
            Text(text, color = C.Hi, fontSize = 16.sp, modifier = Modifier.weight(1f).padding(start = 14.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Surface(
                onClick = onFill, shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                colors = ClickableSurfaceDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent, focusedContainerColor = C.Panel2),
            ) { Icon(Icons.Filled.NorthWest, null, Modifier.padding(6.dp), tint = C.Low) }
        }
    }
}

@Composable
private fun ChannelResult(c: com.mauricio.vista.data.Chan, onClick: () -> Unit) {
    val subs by Store.subs.collectAsState()
    val subscribed = subs.any { s -> s.url == c.url || (s.channelId != null && s.channelId == c.channelId) }
    if (DeviceUi.isTv) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { ChannelBubble(c, onClick, size = 96.dp) }
    } else {
        Surface(
            onClick = onClick, modifier = Modifier.fillMaxWidth(),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(0.dp)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            colors = ClickableSurfaceDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent, pressedContainerColor = C.Panel, focusedContainerColor = C.Panel),
        ) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar(c.avatar, c.name, 64.dp)
                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                    Text(c.name, color = C.Hi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Fmt.subs(c.subs), color = C.Low, fontSize = 12.sp)
                }
                ActionButton(
                    if (subscribed) "Suscrito" else "Suscribirme",
                    { if (subscribed) Store.unsubscribe(c.url) else { Store.subscribe(c); Toast.show("Suscrito a ${c.name}") } },
                    primary = !subscribed,
                )
            }
        }
    }
}

/** Fila que se acomoda en varias líneas si no cabe. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun Wrap(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    androidx.compose.foundation.layout.FlowRow(
        modifier, horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp),
    ) { content() }
}
