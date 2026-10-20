package com.mauricio.vista.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import com.mauricio.vista.data.Profiles

/** Abre la administración de perfiles (desde Ajustes). */
val profileManagerOpen = mutableStateOf(false)
/** Abre «¿Quién está viendo?» para cambiar de perfil. */
val profilePickerOpen = mutableStateOf(false)

@Composable
fun ProfileBadge(p: Profiles.Profile, size: Dp) {
    Box(Modifier.size(size).background(Color(p.color), CircleShape), contentAlignment = Alignment.Center) {
        if (p.kids) Icon(Icons.Filled.ChildCare, null, Modifier.size(size * 0.55f), tint = Color(0xFF161A20))
        else Text(p.initial, color = Color(0xFF161A20), fontSize = (size.value * 0.42f).sp, fontWeight = FontWeight.Bold)
    }
}

/** «¿Quién está viendo?» */
@Composable
fun ProfilePicker(onDone: () -> Unit) {
    val list by Profiles.list.collectAsState()
    var askPin by remember { mutableStateOf<Profiles.Profile?>(null) }
    val first = remember { FocusRequester() }
    val tv = DeviceUi.isTv
    fun choose(p: Profiles.Profile) {
        if (p.pin.isNotBlank() && p.id != Profiles.current.value.id) askPin = p
        else { if (p.id != Profiles.current.value.id) Profiles.select(p); onDone() }
    }
    Box(Modifier.fillMaxSize().background(C.Bg), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp), modifier = Modifier.padding(20.dp)) {
            Text("¿Quién está viendo?", color = C.Hi, fontSize = if (tv) 32.sp else 26.sp, fontWeight = FontWeight.Bold)
            Wrap {
                list.forEachIndexed { i, p ->
                    var focused by remember { mutableStateOf(false) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(if (tv) 130.dp else 100.dp)) {
                        Surface(
                            onClick = { choose(p) },
                            modifier = Modifier.size(if (tv) 110.dp else 84.dp).onFocusChanged { focused = it.isFocused }
                                .then(if (i == 0) Modifier.focusRequester(first) else Modifier),
                            shape = ClickableSurfaceDefaults.shape(CircleShape),
                            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
                            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(4.dp, C.Hi), shape = CircleShape)),
                            colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = Color.Transparent),
                        ) { ProfileBadge(p, if (tv) 110.dp else 84.dp) }
                        Spacer(Modifier.height(8.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (p.pin.isNotBlank()) Icon(Icons.Filled.Lock, null, Modifier.size(13.dp).padding(end = 3.dp), tint = C.Low)
                            Text(p.name, color = if (focused || !tv) C.Hi else C.Mid, fontSize = 15.sp, maxLines = 1, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
            ActionButton("Administrar perfiles", { profileManagerOpen.value = true })
        }
    }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(120); runCatching { first.requestFocus() } }
    askPin?.let { p ->
        PinDialog("PIN de ${p.name}", onOk = { pin ->
            if (pin == p.pin) { askPin = null; Profiles.select(p); onDone(); true } else false
        }) { askPin = null }
    }
}

/** Teclado numérico (funciona con el control de la tele y con el dedo). */
@Composable
fun PinDialog(title: String, onOk: (String) -> Boolean, onDismiss: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    Dialog(onDismissRequest = onDismiss) {
        Column(Modifier.width(320.dp).background(C.Panel, RoundedCornerShape(22.dp)).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, color = C.Hi, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(if (pin.isEmpty()) "Escribe los 4 números" else "●".repeat(pin.length) + "○".repeat(4 - pin.length), color = if (wrong) C.Live else C.Mid, fontSize = 22.sp)
            if (wrong) Text("PIN incorrecto", color = C.Live, fontSize = 13.sp)
            listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "✕")).forEachIndexed { r, row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEachIndexed { c, k ->
                        ActionButton(k, {
                            when (k) {
                                "⌫" -> pin = pin.dropLast(1)
                                "✕" -> onDismiss()
                                else -> if (pin.length < 4) {
                                    pin += k
                                    if (pin.length == 4) { wrong = !onOk(pin); if (wrong) pin = "" }
                                }
                            }
                        }, if (r == 0 && c == 0) Modifier.width(70.dp).focusRequester(fr) else Modifier.width(70.dp))
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}

/** Crear, editar y borrar perfiles. */
@Composable
fun ProfileManager(onClose: () -> Unit) {
    val list by Profiles.list.collectAsState()
    var editing by remember { mutableStateOf<Profiles.Profile?>(null) }
    var creating by remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }
    if (Profiles.isKids) {
        PinOrBlock(onClose); return
    }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.width(440.dp).background(C.Panel, RoundedCornerShape(22.dp)).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Perfiles", color = C.Hi, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Text("Cada perfil tiene sus propias suscripciones, historial, recomendaciones, listas y ajustes. El infantil usa el modo restringido de YouTube.", color = C.Mid, fontSize = 13.sp)
            list.forEachIndexed { i, p ->
                Surface(
                    onClick = { editing = p }, modifier = Modifier.fillMaxWidth().then(if (i == 0) Modifier.focusRequester(fr) else Modifier),
                    shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(14.dp)),
                    scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
                    colors = ClickableSurfaceDefaults.colors(containerColor = C.Panel2, contentColor = C.Hi, focusedContainerColor = C.Hi, focusedContentColor = C.Bg),
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        ProfileBadge(p, 40.dp)
                        Column(Modifier.weight(1f).padding(start = 12.dp)) {
                            Text(p.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                            Text(listOfNotNull(if (p.kids) "Infantil" else null, if (p.pin.isNotBlank()) "Con PIN" else null, if (p.id == Profiles.current.value.id) "En uso" else null).joinToString(" · ").ifBlank { "Toca para editar" }, fontSize = 12.sp)
                        }
                    }
                }
            }
            ActionButton("Agregar perfil", { creating = true }, icon = Icons.Filled.Add)
            SettingRow("Preguntar quién ve al abrir", if (Profiles.askOnStart) "Sí" else "No") { Profiles.askOnStart = !Profiles.askOnStart; editing = null }
            ActionButton("Listo", onClose, primary = true)
        }
    }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
    if (creating) ProfileEditor(null) { creating = false }
    editing?.let { p -> ProfileEditor(p) { editing = null } }
}

/** En el perfil infantil, administrar perfiles pide el PIN del perfil principal (si tiene). */
@Composable
private fun PinOrBlock(onClose: () -> Unit) {
    val main = Profiles.list.value.firstOrNull { !it.kids && it.pin.isNotBlank() }
    if (main == null) {
        ConfirmDialog("Perfil infantil", "Para administrar perfiles, cambia primero a un perfil de adulto.", "Cambiar de perfil", { onClose(); profilePickerOpen.value = true }, onClose)
    } else {
        PinDialog("PIN de ${main.name}", onOk = { pin -> if (pin == main.pin) { onClose(); Profiles.select(main); true } else false }, onDismiss = onClose)
    }
}

@Composable
private fun ProfileEditor(p: Profiles.Profile?, onClose: () -> Unit) {
    var name by remember { mutableStateOf(p?.name ?: "") }
    var kids by remember { mutableStateOf(p?.kids ?: false) }
    var pin by remember { mutableStateOf(p?.pin ?: "") }
    var color by remember { mutableStateOf(p?.color ?: Profiles.colors[Profiles.list.value.size % Profiles.colors.size]) }
    var setPin by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.width(420.dp).background(C.Panel, RoundedCornerShape(22.dp)).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(if (p == null) "Nuevo perfil" else "Editar perfil", color = C.Hi, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            TvTextField(name, { name = it }, "Nombre (ej. Papá, Niños)", Modifier.fillMaxWidth(), imeAction = androidx.compose.ui.text.input.ImeAction.Done, startEditing = p == null && !DeviceUi.isTv)
            Text("Color", color = C.Low, fontSize = 12.sp)
            Wrap {
                Profiles.colors.forEach { c ->
                    Surface(
                        onClick = { color = c }, modifier = Modifier.size(36.dp),
                        shape = ClickableSurfaceDefaults.shape(CircleShape),
                        border = ClickableSurfaceDefaults.border(
                            border = if (c == color) Border(BorderStroke(3.dp, C.Hi), shape = CircleShape) else Border.None,
                            focusedBorder = Border(BorderStroke(3.dp, C.Accent), shape = CircleShape),
                        ),
                        colors = ClickableSurfaceDefaults.colors(containerColor = Color(c), focusedContainerColor = Color(c)),
                    ) {}
                }
            }
            if (p?.id != "p0") SettingRow("Perfil infantil (modo restringido)", if (kids) "Sí" else "No") { kids = !kids }
            SettingRow("PIN para entrar", if (pin.isBlank()) "Sin PIN" else "●●●●") { if (pin.isBlank()) setPin = true else pin = "" }
            if (pin.isNotBlank()) Text("Toca de nuevo para quitar el PIN.", color = C.Low, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("Guardar", {
                    if (p == null) Profiles.add(name, kids, pin).let { np -> Profiles.update(np.copy(color = color)) }
                    else Profiles.update(p.copy(name = name.trim().ifBlank { p.name }, kids = kids, pin = pin, color = color))
                    onClose()
                }, primary = true)
                ActionButton("Cancelar", onClose)
                if (p != null && p.id != "p0") ActionButton("Borrar", { confirmDelete = true })
            }
        }
    }
    if (setPin) PinDialog("Nuevo PIN (4 números)", onOk = { pin = it; setPin = false; true }) { setPin = false }
    if (confirmDelete && p != null) ConfirmDialog("¿Borrar a ${p.name}?", "Se borran sus suscripciones, historial y listas de este aparato.", "Borrar", { Profiles.delete(p.id); confirmDelete = false; onClose() }, { confirmDelete = false })
}
