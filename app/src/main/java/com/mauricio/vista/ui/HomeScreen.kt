package com.mauricio.vista.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.mauricio.vista.data.Feed
import com.mauricio.vista.data.Fmt
import com.mauricio.vista.data.Recs
import com.mauricio.vista.data.Store
import com.mauricio.vista.data.Video
import com.mauricio.vista.data.Watched
import com.mauricio.vista.data.Yt
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/** Lo que se muestra en Inicio. Se queda en memoria para que regresar sea instantáneo. */
object HomeData {
    val kiosks = listOf(
        "trending_music" to "Tendencias en música",
        "trending_gaming" to "Videojuegos",
        "trending_movies_and_shows" to "Tráilers de películas y series",
        "live" to "En vivo ahora",
        "trending_podcasts_episodes" to "Pódcasts",
    )
    val rows = mutableStateMapOf<String, List<Video>>()
    private var loadedAt = 0L

    suspend fun load(force: Boolean = false) {
        if (!force && System.currentTimeMillis() - loadedAt < 20 * 60_000 && rows.isNotEmpty()) return
        loadedAt = System.currentTimeMillis()
        coroutineScope {
            val feed = async { runCatching { Feed.refresh(force) } }
            val recs = async { runCatching { Recs.refresh(force) } }
            kiosks.map { (id, _) ->
                async { runCatching { Yt.kiosk(id) }.getOrNull()?.let { rows[id] = it } }
            }.awaitAll()
            feed.await()
            recs.await()
        }
    }
}

private fun List<Video>.clean() = (if (Store.hideShorts) filterNot { it.short } else this).distinctBy { it.id }

@Composable
fun HomeScreen() {
    LaunchedEffect(Unit) {
        HomeData.load()
        // Lo que viste desde la última vez cambia las recomendaciones
        runCatching { Recs.refresh() }
    }
    val tick by Store.settingsTick.collectAsState()
    if (tick >= 0 && !Store.topicsAsked) { TopicPicker { }; return }
    if (DeviceUi.isTv) TvHome() else PhoneHome()
}

// ======================================================================
// Tele: portada grande que cambia con lo que tengas enfocado
// ======================================================================
private data class HomeRow(
    val key: String,
    val title: String,
    val videos: List<Video>,
    val progress: Map<String, Float> = emptyMap(),
    val chans: List<Recs.Suggested> = emptyList(),
)

@Composable
private fun buildRows(): List<HomeRow> {
    val feed by Feed.videos.collectAsState()
    val history by Store.history.collectAsState()
    val later by Store.later.collectAsState()
    val forYou by Recs.forYou.collectAsState()
    val chans by Recs.channels.collectAsState()
    val topics by Recs.topicRows.collectAsState()
    val tick by Store.settingsTick.collectAsState()
    val out = ArrayList<HomeRow>()
    if (tick < -1) return out
    val cont = history.filter { !it.finished && it.posMs > 15_000 }.take(20)
    if (cont.isNotEmpty()) out += HomeRow("cont", "Continuar viendo", cont.map { it.video }, cont.associate { it.video.id to it.progress })
    if (forYou.isNotEmpty()) out += HomeRow("foryou", "Para ti", forYou.take(30))
    val f = feed.clean()
    if (f.isNotEmpty()) out += HomeRow("feed", "Nuevos de tus suscripciones", f.take(30))
    if (chans.isNotEmpty()) out += HomeRow("chans", "Canales que quizá te gusten", emptyList(), chans = chans)
    Store.topics.forEach { t -> topics[t]?.clean()?.takeIf { it.isNotEmpty() }?.let { out += HomeRow("t:$t", t, it) } }
    if (later.isNotEmpty()) out += HomeRow("later", "Ver después", later.take(30))
    HomeData.kiosks.forEach { (id, title) -> HomeData.rows[id]?.clean()?.takeIf { it.isNotEmpty() }?.let { out += HomeRow(id, title, it.take(30)) } }
    return out
}

@Composable
private fun TvHome() {
    val nav = LocalNav.current
    val rows = buildRows()
    val mem = remember { nav.memory("home") }
    var hero by remember { mutableStateOf<Video?>(null) }
    val first = remember { FocusRequester() }
    val shown = hero ?: rows.firstOrNull()?.videos?.firstOrNull()

    Box(Modifier.fillMaxSize()) {
        // Fondo: la imagen del video enfocado, difuminada hacia los lados
        Crossfade(shown?.id, animationSpec = tween(450), label = "hero") { id ->
            val v = rows.asSequence().flatMap { it.videos }.firstOrNull { it.id == id } ?: shown
            if (v != null) Box(Modifier.fillMaxWidth().height(330.dp)) {
                Img(v.thumbMax, Modifier.fillMaxSize(), fallback = v.thumbHq)
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to C.Bg, 0.42f to C.Bg.copy(alpha = 0.75f), 1f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.35f to Color.Transparent, 1f to C.Bg)))
            }
        }
        Column(Modifier.fillMaxSize()) {
            // Datos del video enfocado
            Column(Modifier.fillMaxWidth().height(232.dp).padding(start = 28.dp, end = 300.dp, top = 40.dp)) {
                if (shown == null) {
                    Text("Vista", color = C.Hi, fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    Text("Cargando lo nuevo…", color = C.Mid, fontSize = 15.sp)
                } else {
                    Text(shown.channel.uppercase(), color = C.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                    Spacer(Modifier.height(6.dp))
                    Text(shown.title, color = C.Hi, fontSize = 28.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 33.sp)
                    Spacer(Modifier.height(8.dp))
                    val meta = listOf(Fmt.duration(shown.duration), Fmt.meta(shown, withChannel = false)).filter { it.isNotBlank() }.joinToString("  ·  ")
                    Text(meta, color = C.Mid, fontSize = 14.sp, maxLines = 1)
                }
            }
            if (rows.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner() }
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 60.dp),
                    verticalArrangement = Arrangement.spacedBy(22.dp),
                ) {
                    itemsIndexed(rows, key = { _, r -> r.key }) { ri, r ->
                        Column {
                            Text(r.title, color = C.Hi, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 28.dp, bottom = 10.dp))
                            LazyRow(contentPadding = PaddingValues(horizontal = 28.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                itemsIndexed(r.chans, key = { _, c -> "c" + c.chan.url }) { _, c ->
                                    SuggestedChannelCard(c, 220.dp, Modifier.rememberFocus(mem, "${r.key}:${c.chan.url}"), onFocused = { hero = c.sample })
                                }
                                itemsIndexed(r.videos, key = { _, v -> v.id }) { i, v ->
                                    VideoCard(
                                        v, onClick = { nav.play(v, r.videos.drop(i + 1)) },
                                        modifier = Modifier.rememberFocus(mem, "${r.key}:${v.id}").then(if (ri == 0 && i == 0) Modifier.focusRequester(first) else Modifier),
                                        width = 220.dp, progress = r.progress[v.id] ?: 0f,
                                        onFocused = { hero = v },
                                        onLongClick = { videoMenu.value = v },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (rows.isNotEmpty()) RestoreFocus(mem, first)
}

// ======================================================================
// Celular: estilo YouTube, con temas arriba
// ======================================================================
@Composable
private fun PhoneHome() {
    val nav = LocalNav.current
    val feed by Feed.videos.collectAsState()
    val feedState by Feed.state.collectAsState()
    val history by Store.history.collectAsState()
    val forYou by Recs.forYou.collectAsState()
    val chans by Recs.channels.collectAsState()
    val topicRows by Recs.topicRows.collectAsState()
    val tick by Store.settingsTick.collectAsState()
    var tab by remember { mutableStateOf("foryou") }
    val topics = if (tick >= 0) Store.topics else emptyList()
    val tabs = listOf("foryou" to "Para ti", "feed" to "Suscripciones") +
        topics.map { "t:$it" to it } +
        HomeData.kiosks.map { it.first to it.second.substringBefore(" de ").replace("Tendencias en música", "Música") }
    val cont = history.filter { !it.finished && it.posMs > 15_000 }.take(15)
    // «Para ti»: recomendaciones con lo nuevo de tus canales intercalado (2 y 1)
    val mixed = remember(forYou, feed) {
        val a = forYou.clean(); val b = feed.clean().take(40)
        val out = ArrayList<Video>(); val seen = HashSet<String>()
        var i = 0; var j = 0
        while (i < a.size || j < b.size) {
            repeat(2) { if (i < a.size) { val v = a[i++]; if (seen.add(v.id)) out += v } }
            if (j < b.size) { val v = b[j++]; if (seen.add(v.id)) out += v }
        }
        out
    }
    val list = when {
        tab == "foryou" -> mixed
        tab == "feed" -> feed.clean()
        tab.startsWith("t:") -> topicRows[tab.removePrefix("t:")]?.clean().orEmpty()
        else -> HomeData.rows[tab]?.clean().orEmpty()
    }
    val state = rememberLazyListState()

    LazyColumn(Modifier.fillMaxSize(), state = state) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(C.gradient), contentAlignment = Alignment.Center) {
                    Text("▶", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
                Text("  Vista", color = C.Hi, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (Store.tvAddress.isNotBlank()) ActionButton("", { nav.go(Route.Connect) }, icon = Icons.Filled.Cast)
                Spacer(Modifier.width(6.dp))
                ActionButton("", { nav.go(Route.Search()) }, icon = Icons.Filled.Search)
            }
        }
        item {
            LazyRow(contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(tabs, key = { it.first }) { (id, label) -> Pill(label, id == tab, { tab = id }) }
                item { Pill("Temas", false, { topicPickerOpen.value = true }, icon = Icons.Filled.Add) }
            }
        }
        if (tab == "foryou" && cont.isNotEmpty()) {
            item { Text("Continuar viendo", color = C.Hi, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, top = 6.dp, bottom = 8.dp)) }
            item {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(cont, key = { it.video.id }) { w: Watched ->
                        VideoCard(w.video, onClick = { nav.play(w.video) }, width = 210.dp, progress = w.progress, onLongClick = { videoMenu.value = w.video })
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
        when {
            list.isEmpty() && tab == "foryou" && Store.subs.value.isEmpty() && history.isEmpty() -> item {
                EmptyHint(
                    "Empieza a ver y aquí aparecerá lo tuyo",
                    "Mientras más videos veas, mejores serán tus recomendaciones. También puedes importar tus suscripciones en Biblioteca → Ajustes.",
                )
            }
            list.isEmpty() && tab == "feed" && Store.subs.value.isEmpty() -> item {
                EmptyHint("Aún no tienes suscripciones", "Busca canales y toca «Suscribirme», o importa las de YouTube en Biblioteca → Ajustes.")
            }
            list.isEmpty() -> item {
                Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Spinner()
                        (feedState as? Feed.State.Loading)?.let { s -> if (tab == "feed" || tab == "foryou") Text("Revisando ${s.done} de ${s.total} canales…", color = C.Low, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
                    }
                }
            }
            else -> {
                list.forEachIndexed { idx, v ->
                    item(key = "v:" + v.id) {
                        PhoneVideoCard(v, onClick = { nav.play(v, list.drop(idx + 1).take(20)) }, onLongClick = { videoMenu.value = v })
                    }
                    // Después de los primeros videos: canales nuevos para descubrir
                    if (tab == "foryou" && idx == 3 && chans.isNotEmpty()) {
                        item(key = "chans") {
                            Column(Modifier.padding(bottom = 18.dp)) {
                                Text("Canales que quizá te gusten", color = C.Hi, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 16.dp, bottom = 8.dp))
                                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    items(chans, key = { it.chan.url }) { c -> SuggestedChannelCard(c, 220.dp) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    LaunchedEffect(tab) { state.scrollToItem(0) }
    if (topicPickerOpen.value) TopicPicker { topicPickerOpen.value = false }
}

@Composable
fun EmptyHint(title: String, sub: String) {
    Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(title, color = C.Hi, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(sub, color = C.Mid, fontSize = 14.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

/** Fondo difuminado que se usa detrás de algunos encabezados. */
@Composable
fun HeroBackdrop(url: String?, fallback: String?, modifier: Modifier = Modifier) {
    Box(modifier) {
        Img(url, Modifier.fillMaxSize(), fallback = fallback)
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color(0x55000000), 1f to C.Bg)))
    }
}
