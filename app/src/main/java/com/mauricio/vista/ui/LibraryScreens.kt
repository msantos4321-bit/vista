package com.mauricio.vista.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WatchLater
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.mauricio.vista.BuildConfig
import com.mauricio.vista.data.Downloads
import com.mauricio.vista.data.Fmt
import com.mauricio.vista.data.Remote
import com.mauricio.vista.data.SponsorBlock
import com.mauricio.vista.data.Store
import com.mauricio.vista.data.Video
import kotlinx.coroutines.launch

// ======================================================================
// Biblioteca: historial y «Ver después»
// ======================================================================
@Composable
fun LibraryScreen() {
    val nav = LocalNav.current
    val history by Store.history.collectAsState()
    val later by Store.later.collectAsState()
    val tv = DeviceUi.isTv
    val first = remember { FocusRequester() }
    val mem = remember { nav.memory("library") }

    if (tv) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(top = 26.dp, bottom = 60.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            item { Text("Biblioteca", color = C.Hi, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 28.dp)) }
            if (history.isEmpty() && later.isEmpty()) item { EmptyHint("Todavía no has visto nada", "Aquí aparecerá lo que veas y lo que guardes para después.") }
            if (later.isNotEmpty()) item { TvRow("Ver después", later, emptyMap(), mem, first) }
            if (history.isNotEmpty()) item { TvRow("Historial", history.map { it.video }, history.associate { it.video.id to it.progress }, mem, if (later.isEmpty()) first else null) }
            if (history.isNotEmpty()) item { Box(Modifier.padding(start = 28.dp)) { ActionButton("Borrar historial", { Store.clearHistory(); Toast.show("Historial borrado") }, icon = Icons.Filled.Delete) } }
        }
        RestoreFocus(mem, first)
        return
    }

    var tab by remember { mutableStateOf(0) }
    val list: List<Video> = if (tab == 0) history.map { it.video } else later
    val progress = history.associate { it.video.id to it.progress }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { PhoneTopBar("Biblioteca") { ActionButton("", { nav.go(Route.Settings) }, icon = Icons.Filled.Settings) } }
        item {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LinkRow(Icons.Filled.Download, "Descargas", Fmt.bytes(Downloads.items.value.filter { it.done }.sumOf { it.size })) { nav.go(Route.Downloads) }
                LinkRow(Icons.Filled.Cast, "Conectar con la tele", if (Store.tvAddress.isBlank()) "Sin conectar" else "Conectada") { nav.go(Route.Connect) }
                LinkRow(Icons.Filled.Settings, "Ajustes", "Calidad, subtítulos, importar…") { nav.go(Route.Settings) }
            }
        }
        item {
            Row(Modifier.padding(start = 12.dp, top = 18.dp, bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("Historial", tab == 0, { tab = 0 }, icon = Icons.Filled.History)
                Pill("Ver después", tab == 1, { tab = 1 }, icon = Icons.Filled.WatchLater)
            }
        }
        if (list.isEmpty()) item { EmptyHint(if (tab == 0) "Sin historial" else "Nada guardado", if (tab == 0) "Lo que veas aparecerá aquí." else "Mantén presionado un video y elige «Guardar en Ver después».") }
        itemsIndexed(list, key = { _, v -> v.id }) { i, v ->
            VideoRow(v, { nav.play(v, list.drop(i + 1).take(20)) }, Modifier.padding(horizontal = 8.dp), progress = progress[v.id] ?: 0f, onLongClick = { videoMenu.value = v })
        }
        if (tab == 0 && list.isNotEmpty()) item { Box(Modifier.padding(16.dp)) { ActionButton("Borrar historial", { Store.clearHistory() }, icon = Icons.Filled.Delete) } }
    }
}

@Composable
private fun TvRow(title: String, videos: List<Video>, progress: Map<String, Float>, mem: FocusMemory, first: FocusRequester?) {
    val nav = LocalNav.current
    Column {
        Text(title, color = C.Hi, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 28.dp, bottom = 10.dp))
        LazyRow(contentPadding = PaddingValues(horizontal = 28.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            itemsIndexed(videos, key = { _, v -> v.id }) { i, v ->
                VideoCard(
                    v, onClick = { nav.play(v, videos.drop(i + 1).take(20)) }, width = 220.dp, progress = progress[v.id] ?: 0f,
                    modifier = Modifier.rememberFocus(mem, title + v.id).then(if (i == 0 && first != null) Modifier.focusRequester(first) else Modifier),
                    onLongClick = { videoMenu.value = v },
                )
            }
        }
    }
}

@Composable
fun LinkRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, value: String = "", modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick, modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(containerColor = C.Panel, contentColor = C.Hi, focusedContainerColor = C.Hi, focusedContentColor = C.Bg, pressedContainerColor = C.Panel2),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(32.dp).background(C.gradient, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(18.dp), tint = androidx.compose.ui.graphics.Color.White)
            }
            Spacer(Modifier.width(12.dp))
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (value.isNotBlank()) Text(value, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

// ======================================================================
// Descargas (celular)
// ======================================================================
@Composable
fun DownloadsScreen() {
    val nav = LocalNav.current
    val items by Downloads.items.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { PhoneTopBar("Descargas") }
        item {
            Text(
                "Para ver sin internet. Libre en el teléfono: ${Fmt.bytes(Downloads.freeSpace())}. Para descargar, mantén presionado un video o usa el botón «Descargar» al verlo.",
                color = C.Low, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (items.isEmpty()) item { EmptyHint("Sin descargas", "Lo que descargues aparecerá aquí y se verá aunque no tengas señal.") }
        items(items, key = { it.id }) { d ->
            val status = when (d.status) {
                "espera" -> "En espera"
                "bajando" -> "Descargando ${(d.progress * 100).toInt()}%"
                "uniendo" -> "Terminando…"
                "error" -> d.error.ifBlank { "No se pudo descargar" }
                else -> listOf(if (d.audioOnly) "Solo audio" else Fmt.quality(d.quality), Fmt.bytes(d.size)).filter { it.isNotBlank() }.joinToString(" · ")
            }
            Column(Modifier.padding(horizontal = 8.dp)) {
                VideoRow(
                    d.video,
                    onClick = { if (d.done) nav.play(d.video, audioOnly = d.audioOnly) else if (d.status == "error") Downloads.retry(d.id) },
                    trailing = {
                        Surface(
                            onClick = { Downloads.remove(d.id) },
                            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
                            colors = ClickableSurfaceDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent, pressedContainerColor = C.Panel2),
                        ) { Icon(Icons.Filled.Delete, "Borrar", Modifier.padding(10.dp), tint = C.Low) }
                    },
                )
                Row(Modifier.padding(start = 12.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (d.audioOnly) Icon(Icons.Filled.Headphones, null, Modifier.size(14.dp).padding(end = 4.dp), tint = C.Low)
                    Text(status, color = if (d.status == "error") C.Warn else if (d.done) C.Good else C.Mid, fontSize = 12.sp)
                    if (d.status == "error") Text("  · Toca para reintentar", color = C.Low, fontSize = 12.sp)
                }
                if (d.status == "bajando") ProgressBar(d.progress, Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 0.dp), 3.dp)
            }
        }
    }
}

// ======================================================================
// Ajustes
// ======================================================================
private class Choice(val title: String, val options: List<Pair<Any, String>>, val current: Any, val set: (Any) -> Unit)

@Composable
fun SettingsScreen() {
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val tick by Store.settingsTick.collectAsState()
    val subs by Store.subs.collectAsState()
    val tv = DeviceUi.isTv
    var choice by remember { mutableStateOf<Choice?>(null) }
    var sbOpen by remember { mutableStateOf(false) }
    var importing by remember { mutableStateOf(false) }
    val first = remember { FocusRequester() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            importing = true
            uiScope.launch {
                val bytes = runCatching { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }.getOrNull()
                val r = if (bytes == null) null else runCatching { Remote.importBytes(bytes) }.getOrNull()
                importing = false
                Toast.show(
                    when {
                        r == null -> "No pude leer ese archivo"
                        r.second == 0 && Remote.lastZipFiles.isNotBlank() -> "Ese .zip no trae tus suscripciones (trae: ${Remote.lastZipFiles.take(120)}). En Takeout marca «suscripciones»."
                        r.second == 0 -> "No encontré canales en ese archivo"
                        else -> "Listo: ${r.first} canales nuevos (de ${r.second})"
                    },
                )
            }
        }
    }

    if (tick < -1) return
    val qualities = listOf(2160, 1440, 1080, 720, 480, 360, 240, 144)
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = if (tv) 28.dp else 12.dp, vertical = if (tv) 26.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (tv) Text("Ajustes", color = C.Hi, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 8.dp))
        else Row(verticalAlignment = Alignment.CenterVertically) {
            ActionButton("", { nav.back() }, icon = Icons.AutoMirrored.Filled.ArrowBack)
            PhoneTopBar("Ajustes")
        }
        Group("Video")
        SettingRow(if (tv) "Calidad máxima" else "Calidad en Wi-Fi", Fmt.quality(Store.qualityWifi), Modifier.focusRequester(first)) {
            choice = Choice("Calidad máxima" + if (tv) "" else " en Wi-Fi", qualities.map { it to Fmt.quality(it) }, Store.qualityWifi) { Store.qualityWifi = it as Int }
        }
        if (!tv) SettingRow("Calidad con datos del celular", Fmt.quality(Store.qualityData)) {
            choice = Choice("Calidad con datos (gasta menos)", qualities.drop(2).map { it to Fmt.quality(it) }, Store.qualityData) { Store.qualityData = it as Int }
        }
        SettingRow("Velocidad", speedLabel(Store.speed)) {
            choice = Choice("Velocidad de reproducción", listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f).map { it to speedLabel(it) }, Store.speed) { Store.speed = it as Float }
        }
        SettingRow("Subtítulos", subLabel(Store.subtitles)) {
            choice = Choice("Subtítulos", listOf("" to "Apagados", "es" to "Español", "en" to "Inglés"), Store.subtitles) { Store.subtitles = it as String }
        }
        SettingRow("Tamaño de subtítulos", scaleLabel(Store.subtitleScale)) {
            choice = Choice("Tamaño de subtítulos", listOf(1f, 1.3f, 1.6f, 2f).map { it to scaleLabel(it) }, Store.subtitleScale) { Store.subtitleScale = it as Float }
        }
        SettingRow("Siguiente video automático", if (Store.autoplay) "Sí" else "No") { Store.autoplay = !Store.autoplay }
        SettingRow("Ocultar Shorts", if (Store.hideShorts) "Sí" else "No") { Store.hideShorts = !Store.hideShorts }

        Group("Saltar partes (SponsorBlock)")
        SettingRow("Saltar patrocinios y más", if (Store.sponsorBlock) "Sí" else "No") { Store.sponsorBlock = !Store.sponsorBlock }
        if (Store.sponsorBlock) SettingRow("Qué se salta", Store.sbCategories.size.toString() + " tipos") { sbOpen = true }

        Group("Suscripciones (${subs.size})")
        if (tv) {
            SettingRow("Pasar suscripciones desde el celular", "Abrir") { nav.go(Route.Connect) }
        } else {
            SettingRow("Importar de YouTube (Google Takeout)", if (importing) "Importando…" else "Elegir archivo") { picker.launch("*/*") }
            Text(
                "En takeout.google.com elige solo «YouTube y YouTube Music» → «suscripciones». Te llega un .zip: elígelo aquí tal cual.",
                color = C.Low, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 6.dp),
            )
            SettingRow("Compartir mis suscripciones", "Archivo") {
                runCatching {
                    val f = java.io.File(ctx.cacheDir, "update").apply { mkdirs() }.resolve("vista-suscripciones.json")
                    f.writeText(Remote.exportJson())
                    val uri = androidx.core.content.FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
                    ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("application/json").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "Compartir suscripciones"))
                }
            }
            SettingRow("Conectar con la tele", if (Store.tvAddress.isBlank()) "Sin conectar" else "Conectada") { nav.go(Route.Connect) }
        }

        Group("Otros")
        SettingRow("Borrar historial", "") { Store.clearHistory(); Toast.show("Historial borrado") }
        SettingRow("Buscar actualizaciones", "Versión ${BuildConfig.VERSION_NAME}") { updateDialogOpen.value = true; uiScope.launch { com.mauricio.vista.data.Updater.check() } }
        Text(
            "Vista no usa cuenta de Google: no hay anuncios ni rastreo. Tus suscripciones e historial se guardan solo en este aparato. " +
                "Los videos se leen con NewPipe Extractor (código abierto) y los saltos con SponsorBlock.",
            color = C.Low, fontSize = 12.sp, modifier = Modifier.padding(6.dp),
        )
        Spacer(Modifier.height(40.dp))
    }
    if (tv) LaunchedEffect(Unit) { kotlinx.coroutines.delay(120); runCatching { first.requestFocus() } }

    choice?.let { c -> ChoiceDialog(c) { choice = null } }
    if (sbOpen) SponsorDialog { sbOpen = false }
}

private fun speedLabel(s: Float) = if (s == 1f) "Normal" else ("" + s).trimEnd('0').trimEnd('.') + "×"
private fun subLabel(s: String) = when (s) { "" -> "Apagados"; "es" -> "Español"; "en" -> "Inglés"; else -> s }
private fun scaleLabel(s: Float) = when { s <= 1f -> "Normal"; s <= 1.3f -> "Mediano"; s <= 1.6f -> "Grande"; else -> "Muy grande" }

@Composable
private fun Group(title: String) {
    Text(title.uppercase(), color = C.Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 6.dp, top = 14.dp, bottom = 2.dp))
}

@Composable
fun SettingRow(title: String, value: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick, modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.01f),
        colors = ClickableSurfaceDefaults.colors(containerColor = C.Panel, contentColor = C.Hi, focusedContainerColor = C.Hi, focusedContentColor = C.Bg, pressedContainerColor = C.Panel2),
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (value.isNotBlank()) Text(value, fontSize = 14.sp, maxLines = 1)
        }
    }
}

@Composable
private fun ChoiceDialog(c: Choice, onClose: () -> Unit) {
    val fr = remember { FocusRequester() }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.width(380.dp).background(C.Panel, RoundedCornerShape(22.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(c.title, color = C.Hi, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(bottom = 6.dp))
            c.options.forEach { (v, label) ->
                val sel = v == c.current
                Surface(
                    onClick = { c.set(v); onClose() },
                    modifier = Modifier.fillMaxWidth().then(if (sel) Modifier.focusRequester(fr) else Modifier),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = if (sel) C.AccentSoft else C.Panel2, contentColor = if (sel) C.Accent else C.Hi,
                        focusedContainerColor = C.Hi, focusedContentColor = C.Bg,
                    ),
                ) { Text(label, Modifier.padding(horizontal = 16.dp, vertical = 12.dp), fontSize = 15.sp, fontWeight = if (sel) FontWeight.Bold else FontWeight.Medium) }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}

@Composable
private fun SponsorDialog(onClose: () -> Unit) {
    var cats by remember { mutableStateOf(Store.sbCategories) }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.width(420.dp).background(C.Panel, RoundedCornerShape(22.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("¿Qué partes se saltan?", color = C.Hi, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("Las marca la comunidad de SponsorBlock. Si alguna no te gusta, apágala.", color = C.Low, fontSize = 13.sp)
            SponsorBlock.names.forEach { (k, label) ->
                val on = k in cats
                SettingRow(label, if (on) "Sí" else "No") {
                    cats = if (on) cats - k else cats + k
                    Store.sbCategories = cats
                }
            }
            ActionButton("Listo", onClose, primary = true)
        }
    }
}

// ======================================================================
// Conectar celular y tele
// ======================================================================
@Composable
fun ConnectScreen() {
    val nav = LocalNav.current
    val tv = DeviceUi.isTv
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    if (tv) {
        val addr = remember { Remote.address() }
        Row(Modifier.fillMaxSize().padding(40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Usa tu celular como control", color = C.Hi, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Text("Escanea el código con la cámara del celular (conectado al mismo Wi-Fi que la tele). Desde ahí puedes:", color = C.Mid, fontSize = 16.sp)
                listOf(
                    "Mandar a la tele un video que encontraste en el celular",
                    "Escribir búsquedas sin usar el control",
                    "Pasar todas tus suscripciones de YouTube de una vez",
                    "Conectar la app Vista del celular para el botón «Ver en la tele»",
                ).forEach { Text("•  $it", color = C.Hi, fontSize = 15.sp) }
                Spacer(Modifier.height(8.dp))
                if (addr != null) Text("O escribe en el navegador del celular:  $addr", color = C.Low, fontSize = 14.sp)
                else Text("La tele no está conectada a la red de la casa.", color = C.Warn, fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                ActionButton("Regresar", { nav.back() })
            }
            if (addr != null) QrCode(addr, Modifier.size(240.dp))
        }
        return
    }

    var host by remember { mutableStateOf(Store.tvAddress) }
    var status by remember { mutableStateOf("") }
    LaunchedEffect(host) {
        if (host.isBlank()) return@LaunchedEffect
        status = "Buscando la tele…"
        status = if (Remote.ping(host)) "Conectada ✓" else "No responde. ¿Está prendida con Vista abierta y en el mismo Wi-Fi?"
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ActionButton("", { nav.back() }, icon = Icons.AutoMirrored.Filled.ArrowBack)
            PhoneTopBar("Conectar con la tele")
        }
        Text(
            "1. En la tele abre Vista → Ajustes → «Pasar suscripciones desde el celular».\n" +
                "2. Escanea con la cámara el código que aparece.\n" +
                "3. En la página que se abre toca «Conectar mi app Vista».",
            color = C.Mid, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 6.dp),
        )
        if (host.isNotBlank()) {
            SettingRow("Tele", host) {}
            Text(status, color = if (status.startsWith("Conectada")) C.Good else C.Low, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 6.dp))
            SettingRow("Mandar mis suscripciones a la tele", "Enviar") {
                scope.launch { Toast.show(if (Remote.sendSubs()) "Listo, ya están en la tele" else "No encontré la tele") }
            }
            SettingRow("Olvidar esta tele", "") { Store.tvAddress = ""; host = "" }
        }
    }
}
