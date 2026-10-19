package com.mauricio.vista.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Subscriptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Text
import com.mauricio.vista.data.Recs
import com.mauricio.vista.data.Store
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Si está abierto el selector de temas (desde Inicio o Ajustes). */
val topicPickerOpen = mutableStateOf(false)

/** «¿Qué te gusta ver?»: cada tema se vuelve una fila en Inicio. */
@Composable
fun TopicPicker(onClose: () -> Unit) {
    val chosen = remember { mutableStateListOf<String>().apply { addAll(Store.topics) } }
    var custom by remember { mutableStateOf("") }
    val first = remember { FocusRequester() }
    val tv = DeviceUi.isTv
    val all = (Recs.topicQueries.keys + chosen.filter { it !in Recs.topicQueries.keys }).toList()

    fun done() {
        Store.topics = chosen.toList()
        Store.topicsAsked = true
        onClose()
        uiScope.launch { Recs.refreshTopics(force = true) }
    }

    Box(Modifier.fillMaxSize().background(C.Bg.copy(alpha = 0.97f)), contentAlignment = Alignment.Center) {
        Column(
            Modifier.then(if (tv) Modifier.width(760.dp) else Modifier.fillMaxWidth()).verticalScroll(rememberScrollState()).padding(if (tv) 32.dp else 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("¿Qué te gusta ver?", color = C.Hi, fontSize = if (tv) 30.sp else 26.sp, fontWeight = FontWeight.Bold)
            Text("Elige tus temas: cada uno será una fila en Inicio con lo más nuevo. Puedes cambiarlos cuando quieras en Ajustes.", color = C.Mid, fontSize = 15.sp)
            Wrap {
                all.forEachIndexed { i, t ->
                    val on = t in chosen
                    Pill(t, on, { if (on) chosen.remove(t) else chosen.add(t) }, if (i == 0) Modifier.focusRequester(first) else Modifier, icon = if (on) Icons.Filled.Check else Icons.Filled.Add)
                }
            }
            Text("¿Otro tema? Escríbelo:", color = C.Low, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TvTextField(custom, { custom = it }, "Ej. pesca, bienes raíces, guitarra…", Modifier.weight(1f), onImeAction = {
                    val t = custom.trim().replaceFirstChar { it.uppercase() }
                    if (t.isNotBlank() && t !in chosen) chosen.add(t)
                    custom = ""
                })
                Spacer(Modifier.width(10.dp))
                ActionButton("Agregar", {
                    val t = custom.trim().replaceFirstChar { it.uppercase() }
                    if (t.isNotBlank() && t !in chosen) chosen.add(t)
                    custom = ""
                }, icon = Icons.Filled.Add)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(if (chosen.isEmpty()) "Listo" else "Listo (${chosen.size})", { done() }, primary = true)
                ActionButton("Ahora no", { Store.topicsAsked = true; onClose() })
            }
        }
    }
    LaunchedEffect(Unit) { delay(120); runCatching { first.requestFocus() } }
}

/** Tarjeta de un canal recomendado: un video suyo de fondo, su nombre y botón para seguirlo. */
@Composable
fun SuggestedChannelCard(s: Recs.Suggested, width: Dp, modifier: Modifier = Modifier, onFocused: () -> Unit = {}) {
    val nav = LocalNav.current
    val subs by Store.subs.collectAsState()
    val subscribed = subs.any { Store.channelKey(it.url) == Store.channelKey(s.chan.url) }
    var focused by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(14.dp)
    Column(modifier.width(width)) {
        Surface(
            onClick = { nav.channel(s.chan) },
            onLongClick = { Store.blockChannel(s.chan.url); Recs.applyDislikes(); Toast.show("No volveré a recomendar ${s.chan.name}") },
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() },
            shape = ClickableSurfaceDefaults.shape(shape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.07f),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, C.Hi), shape = shape)),
            colors = ClickableSurfaceDefaults.colors(containerColor = C.Panel2, focusedContainerColor = C.Panel2),
        ) {
            Box(Modifier.fillMaxSize()) {
                Img(s.sample.thumbHq, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.2f to Color(0x33000000), 1f to Color(0xE6000000))))
                Column(Modifier.align(Alignment.BottomStart).padding(12.dp)) {
                    Text("CANAL", color = C.Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Text(s.chan.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (DeviceUi.isTv) {
            Text("Le gusta a gente que ve lo mismo que tú", color = if (focused) C.Mid else C.Low, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        } else {
            ActionButton(
                if (subscribed) "Suscrito" else "Suscribirme",
                { if (subscribed) Store.unsubscribe(s.chan.url) else { Store.subscribe(s.chan); Toast.show("Suscrito a ${s.chan.name}") } },
                Modifier.clip(RoundedCornerShape(50)),
                icon = if (subscribed) Icons.Filled.Check else Icons.Filled.Subscriptions, primary = !subscribed,
            )
        }
    }
}
