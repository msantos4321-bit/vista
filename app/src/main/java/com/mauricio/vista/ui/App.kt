package com.mauricio.vista.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.mauricio.vista.data.Chan
import com.mauricio.vista.data.Remote
import com.mauricio.vista.data.Video
import com.mauricio.vista.data.Yt
import kotlinx.coroutines.launch

sealed interface Route {
    data object Home : Route
    data object Subs : Route
    data class Search(val q: String = "") : Route
    data object Library : Route
    data object Downloads : Route
    data object Settings : Route
    data object Connect : Route
    /** Control de la tele desde el celular. */
    data object RemoteTv : Route
    data class Channel(val url: String, val name: String = "") : Route
    /** Lista de reproducción de YouTube. */
    data class Playlist(val url: String, val name: String = "") : Route
    /** Lista guardada por la persona. */
    data class MyList(val id: String) : Route
    data class Player(val video: Video, val queue: List<Video> = emptyList(), val audioOnly: Boolean = false) : Route
}

/** Acciones de navegación para todas las pantallas. */
class Nav(
    val go: (Route) -> Unit,
    val back: () -> Unit,
    val memory: (String) -> FocusMemory,
) {
    /** Abre un video; [queue] es la fila de donde vino (se usa si la cola «A continuación» está vacía). */
    fun play(v: Video, queue: List<Video> = emptyList(), audioOnly: Boolean = false) {
        com.mauricio.vista.data.Queue.context = queue.filter { it.id != v.id }
        go(Route.Player(v, emptyList(), audioOnly))
    }

    /** Pasa al siguiente sin cambiar la fila de origen (lo usa el reproductor). */
    fun continueWith(v: Video, audioOnly: Boolean = false) { activePlayer.value = Route.Player(v, emptyList(), audioOnly) }

    /** Reproduce una lista completa en orden (o revuelta). */
    fun playAll(list: List<Video>, shuffle: Boolean = false) {
        val l = if (shuffle) list.shuffled() else list
        val first = l.firstOrNull() ?: return
        play(first, l.drop(1))
    }
    fun channel(c: Chan) = go(Route.Channel(c.url, c.name))
    fun channel(url: String, name: String) = go(Route.Channel(url, name))
}

val LocalNav = staticCompositionLocalOf<Nav> { error("sin navegación") }

/** Liga que llegó de fuera (compartir desde YouTube, link, control desde el celular). */
val incomingLink = mutableStateOf<String?>(null)

/** Aparato recién conectado con el enlace de su página: se ofrece pasarle la cuenta. */
val linkedDevice = mutableStateOf<com.mauricio.vista.data.Store.Device?>(null)

/** Video abierto en la tele a pedido del celular, para el menú de opciones (clic largo). */
val videoMenu = mutableStateOf<Video?>(null)

/** El video que se está viendo (vive aparte de las pantallas para poder minimizarlo en el celular). */
val activePlayer = mutableStateOf<Route.Player?>(null)
/** true = reproductor minimizado abajo (celular), se sigue navegando. */
val playerMini = mutableStateOf(false)

@Composable
fun App() {
    val stack = remember { mutableStateListOf<Route>(Route.Home) }
    val memories = remember { HashMap<String, FocusMemory>() }

    val nav = remember {
        Nav(
            go = { r ->
                when {
                    // El reproductor no se apila: reemplaza al anterior y se abre en grande
                    r is Route.Player -> { activePlayer.value = r; playerMini.value = false }
                    else -> {
                        // Navegar desde el reproductor grande lo minimiza (celular) o lo cierra (tele)
                        if (activePlayer.value != null && !playerMini.value) {
                            if (DeviceUi.isTv) activePlayer.value = null else playerMini.value = true
                        }
                        if (isTopLevel(r)) { stack.clear(); stack.add(r) } else stack.add(r)
                    }
                }
            },
            back = {
                when {
                    // Atrás desde el video: en el celular se hace pequeño y sigue sonando; en la tele se cierra
                    activePlayer.value != null && !playerMini.value -> if (DeviceUi.isTv) activePlayer.value = null else playerMini.value = true
                    stack.size > 1 -> stack.removeAt(stack.lastIndex)
                    stack.last() != Route.Home -> { stack.clear(); stack.add(Route.Home) }
                }
            },
            memory = { k -> memories.getOrPut(k) { FocusMemory() } },
        )
    }

    // Lo que llega desde el celular por la red de la casa
    LaunchedEffect(Unit) {
        Remote.cmds.collect { c ->
            when (c) {
                is Remote.Cmd.Play -> {
                    if (c.startMs > 0) com.mauricio.vista.data.Yt.videoId(c.url)?.let { PlayerState.castStart = it to c.startMs }
                    nav.play(Video(url = c.url, title = "Cargando…"))
                }
                is Remote.Cmd.Search -> nav.go(Route.Search(c.q))
                is Remote.Cmd.Imported -> Toast.show(if (c.added > 0) "Se agregaron ${c.added} canales a tus suscripciones" else "Esos canales ya estaban en tus suscripciones")
                is Remote.Cmd.Account -> {
                    Toast.show("Cuenta recibida: ${c.summary}")
                    uiScope.launch {
                        runCatching { com.mauricio.vista.data.Feed.refresh(force = true) }
                        runCatching { com.mauricio.vista.data.Recs.refresh(force = true) }
                    }
                }
            }
        }
    }
    // Ligas compartidas desde otra app
    val link = incomingLink.value
    LaunchedEffect(link) {
        if (link != null) {
            incomingLink.value = null
            Yt.findLink(link)?.let { nav.play(Video(url = it, title = "Cargando…")) } ?: Toast.show("Esa liga no es de un video de YouTube")
        }
    }

    // Colores: se revisa cada minuto (modo automático) y al cambiar el ajuste
    val tick by com.mauricio.vista.data.Store.settingsTick.collectAsState()
    LaunchedEffect(tick) { C.apply(com.mauricio.vista.data.Store.theme) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(60_000); C.apply(com.mauricio.vista.data.Store.theme) } }
    LaunchedEffect(C.dark) { DeviceUi.styleBars(C.dark) }

    // Si la app se cerró sola hace poco, se abre en modo seguro: primero ofrece actualizar
    var recovering by remember { mutableStateOf(com.mauricio.vista.data.Store.crashedRecently()) }
    // ¿Quién está viendo? (si hay varios perfiles)
    var choosing by remember { mutableStateOf(com.mauricio.vista.data.Profiles.shouldAsk()) }

    val top = stack.last()
    val player = activePlayer.value
    val mini = playerMini.value && !DeviceUi.isTv
    BackHandler(enabled = (player == null || mini) && (stack.size > 1 || top != Route.Home)) { nav.back() }
    LaunchedEffect(mini, player) { PlayerState.mini = mini && player != null }

    // El mismo reproductor se mueve entre pantalla grande y mini sin cortarse
    val playerContent = remember(player) {
        movableContentOf { isMini: Boolean ->
            if (player != null) PlayerScreen(player.video, player.queue, player.audioOnly, mini = isMini, onClose = { activePlayer.value = null })
        }
    }

    val lic by com.mauricio.vista.data.License.state.collectAsState()
    val profile by com.mauricio.vista.data.Profiles.current.collectAsState()
    LaunchedEffect(profile.id) { activePlayer.value = null; stack.clear(); stack.add(Route.Home); memories.clear() }

    CompositionLocalProvider(LocalNav provides nav) {
        key(profile.id) { Box(Modifier.fillMaxSize().background(C.Bg)) {
            when {
                recovering -> RecoveryScreen { recovering = false; com.mauricio.vista.data.Store.clearCrash() }
                lic != com.mauricio.vista.data.License.State.Ok -> ActivationScreen(lic)
                choosing || profilePickerOpen.value -> ProfilePicker { choosing = false; profilePickerOpen.value = false }
                player != null && !mini -> playerContent(false)
                DeviceUi.isTv -> TvShell(top)
                else -> PhoneShell(top, miniPlayer = if (player != null) ({ playerContent(true) }) else null)
            }
            videoMenu.value?.let { v -> VideoMenu(v) { videoMenu.value = null } }
            linkedDevice.value?.let { d -> LinkedDeviceDialog(d) { linkedDevice.value = null } }
            saveToListVideo.value?.let { vs -> SaveToListDialog(vs) { saveToListVideo.value = null } }
            if (profileManagerOpen.value) ProfileManager { profileManagerOpen.value = false }
            if (subscriptionOpen.value) SubscriptionDialog { subscriptionOpen.value = false }
            Toast.Host()
            AutoUpdateCheck()
        } }
    }
}

private fun isTopLevel(r: Route) = r == Route.Home || r == Route.Subs || r is Route.Search || r == Route.Library ||
    r == Route.Settings || r == Route.Downloads

@Composable
private fun Screen(top: Route) {
    when (top) {
        Route.Home -> HomeScreen()
        Route.Subs -> SubsScreen()
        is Route.Search -> key(top) { SearchScreen(top.q) }
        Route.Library -> LibraryScreen()
        Route.Downloads -> DownloadsScreen()
        Route.Settings -> SettingsScreen()
        Route.Connect -> ConnectScreen()
        Route.RemoteTv -> RemoteTvScreen()
        is Route.Channel -> key(top) { ChannelScreen(top.url, top.name) }
        is Route.Playlist -> key(top) { YtPlaylistScreen(top.url, top.name) }
        is Route.MyList -> key(top) { MyListScreen(top.id) }
        is Route.Player -> {}
    }
}

// ======================================================================
// Tele: barra lateral que se abre al llegar a ella
// ======================================================================
private data class NavItem(val label: String, val icon: ImageVector, val route: Route, val match: (Route) -> Boolean)

private val tvItems = listOf(
    NavItem("Buscar", Icons.Filled.Search, Route.Search()) { it is Route.Search },
    NavItem("Inicio", Icons.Filled.Home, Route.Home) { it == Route.Home },
    NavItem("Suscripciones", Icons.Filled.Subscriptions, Route.Subs) { it == Route.Subs || it is Route.Channel },
    NavItem("Biblioteca", Icons.Filled.VideoLibrary, Route.Library) { it == Route.Library || it is Route.MyList },
    NavItem("Ajustes", Icons.Filled.Settings, Route.Settings) { it == Route.Settings || it == Route.Connect },
)

@Composable
private fun TvShell(top: Route) {
    val nav = LocalNav.current
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().padding(start = 76.dp)) { Screen(top) }
        Sidebar(top) { nav.go(it) }
        ClockLabel(Modifier.align(Alignment.TopEnd).padding(top = 16.dp, end = 28.dp))
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Sidebar(current: Route, onSelect: (Route) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val requesters = remember { List(tvItems.size) { FocusRequester() } }
    val sel = tvItems.indexOfFirst { it.match(current) }.coerceAtLeast(0)
    Column(
        Modifier
            .fillMaxHeight()
            .width(if (expanded) 230.dp else 76.dp)
            .background(if (expanded) C.Glass else Color.Transparent)
            .onFocusChanged { expanded = it.hasFocus }
            .focusProperties { enter = { requesters[sel] } }
            .focusGroup()
            .padding(horizontal = 14.dp, vertical = 22.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        // Perfil (cambiar de persona)
        val me by com.mauricio.vista.data.Profiles.current.collectAsState()
        Surface(
            onClick = { profilePickerOpen.value = true },
            modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, contentColor = C.Mid, focusedContainerColor = C.Hi, focusedContentColor = C.Bg),
        ) {
            Row(Modifier.padding(horizontal = 6.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                ProfileBadge(me, 30.dp)
                if (expanded) Column(Modifier.padding(start = 12.dp)) {
                    Text(me.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text("Cambiar perfil", fontSize = 11.sp, maxLines = 1)
                }
            }
        }
        // Logo
        Row(Modifier.padding(start = 6.dp, bottom = 26.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(C.gradient), contentAlignment = Alignment.Center) {
                Text("▶", color = C.OnAccent, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
            if (expanded) Text("  Vista", color = C.Hi, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }
        tvItems.forEachIndexed { i, item ->
            Surface(
                onClick = { onSelect(item.route) },
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).focusRequester(requesters[i]),
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = if (i == sel && !expanded) C.Panel2 else Color.Transparent,
                    contentColor = if (i == sel) C.Hi else C.Low,
                    focusedContainerColor = C.Hi,
                    focusedContentColor = C.Bg,
                ),
            ) {
                Row(Modifier.padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(item.icon, item.label, Modifier.size(22.dp))
                    if (expanded) {
                        Spacer(Modifier.width(14.dp))
                        Text(item.label, fontSize = 15.sp, fontWeight = if (i == sel) FontWeight.Bold else FontWeight.Medium, maxLines = 1)
                    }
                }
            }
        }
    }
}

@Composable
fun ClockLabel(modifier: Modifier = Modifier) {
    var now by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        while (true) {
            now = java.text.SimpleDateFormat("h:mm a", java.util.Locale("es", "MX")).format(java.util.Date()).replace(". ", "").replace(".", "")
            kotlinx.coroutines.delay(15_000)
        }
    }
    Text(now, modifier, color = C.Mid, fontSize = 15.sp, fontWeight = FontWeight.Medium)
}

// ======================================================================
// Celular: pestañas abajo
// ======================================================================
private data class Tab(val label: String, val icon: ImageVector, val route: Route, val match: (Route) -> Boolean)

private val phoneTabs = listOf(
    Tab("Inicio", Icons.Filled.Home, Route.Home) { it == Route.Home },
    Tab("Suscripciones", Icons.Filled.Subscriptions, Route.Subs) { it == Route.Subs || it is Route.Channel },
    Tab("Buscar", Icons.Filled.Search, Route.Search()) { it is Route.Search },
    Tab("Descargas", Icons.Filled.Download, Route.Downloads) { it == Route.Downloads },
    Tab("Biblioteca", Icons.Filled.VideoLibrary, Route.Library) { it == Route.Library || it == Route.Settings || it == Route.Connect || it is Route.MyList },
)

@Composable
private fun PhoneShell(top: Route, miniPlayer: (@Composable () -> Unit)? = null) {
    val nav = LocalNav.current
    Column(Modifier.fillMaxSize().background(C.Bg).systemBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) { Screen(top) }
        miniPlayer?.invoke()
        Box(Modifier.fillMaxWidth().height(0.5.dp).background(C.Line))
        Row(Modifier.fillMaxWidth().background(C.Bg2).padding(top = 7.dp, bottom = 6.dp)) {
            phoneTabs.forEach { t ->
                val sel = t.match(top)
                Column(
                    Modifier.weight(1f).clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null,
                    ) { nav.go(t.route) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(t.icon, t.label, Modifier.size(24.dp), tint = if (sel) C.Hi else C.Low)
                    Text(t.label, fontSize = 10.sp, color = if (sel) C.Hi else C.Low, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1)
                }
            }
        }
    }
}

/** Título grande de pantalla en el celular. */
@Composable
fun PhoneTopBar(title: String, back: Boolean = true, action: (@Composable () -> Unit)? = null) {
    val nav = LocalNav.current
    Row(Modifier.fillMaxWidth().padding(start = if (back) 2.dp else 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        // Botón de regresar en cada pantalla (además del gesto)
        if (back) { ActionButton("", { nav.back() }, icon = androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack); Spacer(Modifier.width(4.dp)) }
        Text(title, color = C.Hi, fontSize = if (back) 24.sp else 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
        action?.invoke()
    }
}


/** Pantalla segura cuando la app se cerró sola: no carga nada pesado, ofrece actualizar y muestra el motivo. */
@Composable
private fun RecoveryScreen(onContinue: () -> Unit) {
    val fr = remember { FocusRequester() }
    var detail by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxSize().padding(40.dp).verticalScroll(androidx.compose.foundation.rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Vista se cerró la vez pasada", color = C.Hi, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Text("Lo más seguro es que ya esté el arreglo. Busca la actualización; si no hay, puedes abrir normal o ver el detalle para mandárselo a quien te ayuda.", color = C.Mid, fontSize = 15.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ActionButton("Buscar actualización", {
                updateDialogOpen.value = true
                uiScope.launch { com.mauricio.vista.data.Updater.check() }
            }, Modifier.focusRequester(fr), primary = true)
            ActionButton("Abrir normal", onContinue)
            ActionButton(if (detail) "Ocultar detalle" else "Ver detalle", { detail = !detail })
        }
        if (detail) Text(com.mauricio.vista.data.Store.lastError.take(2500), color = C.Low, fontSize = 11.sp)
    }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(100); runCatching { fr.requestFocus() } }
}
