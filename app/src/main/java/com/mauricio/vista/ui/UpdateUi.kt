package com.mauricio.vista.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.runtime.DisposableEffect
import androidx.tv.material3.Text
import com.mauricio.vista.BuildConfig
import com.mauricio.vista.data.Updater
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Si el diálogo de actualización está abierto (se abre solo al haber versión nueva, o desde Ajustes). */
val updateDialogOpen = mutableStateOf(false)

/** Revisa al abrir la app y muestra el aviso. Va en la raíz para que funcione hasta en la pantalla de entrada. */
@Composable
fun AutoUpdateCheck() {
    LaunchedEffect(Unit) {
        // Si la app se cerró sola la vez pasada, se revisa de inmediato (puede que ya esté el arreglo)
        delay(if (com.mauricio.vista.data.Store.crashedRecently()) 0 else 1500)
        val r = Updater.check()
        if (r != null && r.versionCode != Updater.dismissedCode) updateDialogOpen.value = true
    }
    if (updateDialogOpen.value) UpdateDialog { updateDialogOpen.value = false }
}

@Composable
fun UpdateDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val st by Updater.state.collectAsState()
    val needPermission = remember { mutableStateOf(false) }
    val fr = remember { FocusRequester() }

    // Al regresar de Ajustes (después de dar el permiso), instalar directo
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME && needPermission.value && Updater.canInstall(ctx)) {
                needPermission.value = false
                (Updater.state.value as? Updater.State.Ready)?.let { Updater.install(ctx, it.file) }
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    fun startInstall(file: java.io.File) {
        if (Updater.canInstall(ctx)) Updater.install(ctx, file) else needPermission.value = true
    }

    Dialog(onDismissRequest = onClose) {
        Column(
            Modifier.width(480.dp).background(C.Panel, RoundedCornerShape(22.dp)).padding(28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row {
                androidx.tv.material3.Icon(Icons.Filled.SystemUpdate, null, tint = C.Accent)
                Text("  Actualización de Vista", color = C.Hi, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
            when (val s = st) {
                Updater.State.Checking, Updater.State.Idle -> {
                    Row { Spinner(size = 24.dp); Text("  Revisando…", color = C.Mid, fontSize = 15.sp) }
                    ActionButton("Cerrar", onClose, Modifier.focusRequester(fr))
                }
                Updater.State.UpToDate -> {
                    Text("Ya tienes la versión más nueva (${BuildConfig.VERSION_NAME}).", color = C.Mid, fontSize = 15.sp)
                    ActionButton("Listo", onClose, Modifier.focusRequester(fr), primary = true)
                }
                is Updater.State.Available -> {
                    Text("Hay una versión nueva: ${s.r.name}. Tienes la ${BuildConfig.VERSION_NAME}.", color = C.Mid, fontSize = 15.sp)
                    Text("Se descarga e instala en menos de un minuto. Tus suscripciones, historial y descargas se conservan.", color = C.Low, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionButton("Actualizar ahora", {
                            uiScope.launch { Updater.download(ctx, s.r)?.let { startInstall(it) } }
                        }, Modifier.focusRequester(fr), primary = true)
                        ActionButton("Más tarde", { Updater.dismissedCode = s.r.versionCode; onClose() })
                    }
                }
                is Updater.State.Downloading -> {
                    Text("Descargando… ${s.pct}%", color = C.Mid, fontSize = 15.sp)
                    ProgressBar(s.pct / 100f, Modifier.fillMaxWidth(), 6.dp)
                }
                is Updater.State.Ready -> {
                    if (needPermission.value) {
                        Text("Falta un permiso (solo esta vez)", color = C.Hi, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Para que Vista pueda instalar sus propias actualizaciones: presiona «Dar permiso», " +
                                "activa Vista en la lista y regresa con el botón Atrás. La instalación sigue sola.",
                            color = C.Mid, fontSize = 14.sp,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ActionButton("Dar permiso", { Updater.openInstallPermission(ctx) }, Modifier.focusRequester(fr), primary = true)
                            ActionButton("Instalar", { startInstall(s.file) })
                        }
                    } else {
                        Text("Descarga lista. En la ventana que aparece, elige «Instalar» y luego «Abrir».", color = C.Mid, fontSize = 15.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            ActionButton("Instalar", { startInstall(s.file) }, Modifier.focusRequester(fr), primary = true)
                            ActionButton("Cerrar", onClose)
                        }
                    }
                }
                is Updater.State.Failed -> {
                    Text(s.msg, color = C.Warn, fontSize = 14.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        ActionButton("Reintentar", { uiScope.launch { Updater.check() } }, Modifier.focusRequester(fr), primary = true)
                        ActionButton("Cerrar", onClose)
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
        }
    }
    LaunchedEffect(st::class, needPermission.value) { delay(80); runCatching { fr.requestFocus() } }
}
