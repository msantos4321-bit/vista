package com.mauricio.vista.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.tv.material3.Text
import com.mauricio.vista.data.License
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Abre la ventana «Tu suscripción» desde Ajustes. */
val subscriptionOpen = mutableStateOf(false)

/** Pantalla para activar Vista: escribir el código o pedir la prueba gratis. */
@Composable
fun ActivationScreen(state: License.State) {
    val codeFr = remember { FocusRequester() }
    var code by remember { mutableStateOf(License.code) }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf((state as? License.State.Need)?.why) }

    if (state == License.State.Checking) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Revisando tu suscripción…", color = C.Mid, fontSize = 18.sp)
        }
        return
    }

    fun go() {
        if (busy) return
        busy = true; msg = null
        uiScope.launch { msg = License.activate(code); busy = false }
    }

    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("Vista", color = C.Accent, fontSize = 40.sp, fontWeight = FontWeight.Black)
            Text("Activa tu aparato", color = C.Hi, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                "Escribe el código que te dieron (por ejemplo VI-AB12-CD34). Se activa una sola vez en este aparato.",
                color = C.Mid, fontSize = 15.sp,
            )
            TvTextField(
                code, { code = it.uppercase().take(16) }, "Código de activación",
                Modifier.fillMaxWidth(), imeAction = ImeAction.Done, onImeAction = { go() },
                boxRequester = codeFr, startEditing = !DeviceUi.isTv,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionButton(if (busy) "Activando…" else "Activar", { go() }, primary = true)
                ActionButton("Probar gratis", {
                    if (!busy) {
                        busy = true; msg = null
                        uiScope.launch { msg = License.trial(); busy = false }
                    }
                })
            }
            msg?.let {
                Text(it, color = C.Hi, fontSize = 15.sp, modifier = Modifier.background(C.Panel, RoundedCornerShape(14.dp)).padding(14.dp))
            }
            val addr = remember { com.mauricio.vista.data.Remote.address() }
            if (addr != null) Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                QrCode(addr, Modifier.size(150.dp))
                Text(
                    "¿Ya tienes Vista activada en tu celular? Con el mismo Wi-Fi, escanea este código con la cámara del celular y toca «Pasar mi cuenta»: este aparato se activa solo con tu código.",
                    color = C.Mid, fontSize = 14.sp, modifier = Modifier.weight(1f),
                )
            }
            Text("¿No tienes código? Pídelo a quien te recomendó Vista.", color = C.Low, fontSize = 13.sp)
        }
    }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(150); runCatching { codeFr.requestFocus() } }
}

/** «Tu suscripción»: código, tipo, vencimiento y aparatos. */
@Composable
fun SubscriptionDialog(onClose: () -> Unit) {
    val fr = remember { FocusRequester() }
    var info by remember { mutableStateOf<JSONObject?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var confirmForget by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { info = License.info(); loaded = true }
    Dialog(onDismissRequest = onClose) {
        Column(Modifier.widthIn(max = 420.dp).background(C.Panel, RoundedCornerShape(22.dp)).padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Tu suscripción", color = C.Hi, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(License.code.ifBlank { "Sin código" }, color = C.Accent, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            val i = info
            when {
                !loaded -> Text("Consultando…", color = C.Mid, fontSize = 14.sp)
                i == null -> Text("No se pudo consultar ahora. Vista sigue funcionando; se revisa sola cuando haya internet.", color = C.Mid, fontSize = 14.sp)
                else -> {
                    val kind = when (i.optString("kind")) { "trial" -> "Prueba gratis"; "gift" -> "Cortesía"; else -> "Anual" }
                    val exp = i.optString("expires").takeIf { it.isNotBlank() && it != "null" }?.take(10)?.let { d ->
                        runCatching { java.time.LocalDate.parse(d).format(java.time.format.DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", java.util.Locale("es", "MX"))) }.getOrDefault(d)
                    }
                    Text("$kind · ${if (exp != null) "vence el $exp" else "no vence"}", color = C.Hi, fontSize = 15.sp)
                    Text("Aparatos: ${i.optInt("used")} de ${i.optInt("max")}", color = C.Mid, fontSize = 14.sp)
                    i.optString("seller").takeIf { it.isNotBlank() && it != "null" }?.let { Text("Te la vendió: $it", color = C.Mid, fontSize = 14.sp) }
                    i.optString("help_phone").takeIf { it.isNotBlank() && it != "null" }?.let { Text("Ayuda y renovación: WhatsApp $it", color = C.Mid, fontSize = 14.sp) }
                }
            }
            Text("Aparato: ${License.deviceName}", color = C.Low, fontSize = 12.sp)
            if (confirmForget) Text("¿Quitar el código de este aparato? Tendrás que escribirlo otra vez para usar Vista.", color = C.Hi, fontSize = 14.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("Cerrar", onClose, Modifier.focusRequester(fr), primary = true)
                ActionButton(if (confirmForget) "Sí, quitar" else "Cambiar código", {
                    if (confirmForget) { License.forget(); onClose() } else confirmForget = true
                })
            }
        }
    }
    LaunchedEffect(Unit) { runCatching { fr.requestFocus() } }
}
