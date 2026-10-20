package com.mauricio.vista.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme
import androidx.tv.material3.lightColorScheme
import java.util.Calendar

/**
 * Colores de Vista, pensados para descansar la vista:
 * - «Noche suave»: gris pizarra (no negro), letras blanco hueso y un azul cielo tranquilo.
 * - «Claro»: gris perla con tarjetas blancas y azul más firme.
 * Nada de rojo intenso ni negro puro. Se leen en vivo, así que el cambio es instantáneo.
 */
object C {
    var dark by mutableStateOf(true)

    val Bg get() = if (dark) Color(0xFF161A20) else Color(0xFFF3F4F6)
    val Bg2 get() = if (dark) Color(0xFF1B2028) else Color(0xFFFFFFFF)
    val Panel get() = if (dark) Color(0xFF222831) else Color(0xFFFFFFFF)
    val Panel2 get() = if (dark) Color(0xFF2B323D) else Color(0xFFE9ECF1)
    val Line get() = if (dark) Color(0xFF3A4250) else Color(0xFFD5DAE1)
    /** Azul cielo (oscuro) / azul firme (claro). */
    val Accent get() = if (dark) Color(0xFF7FB3FF) else Color(0xFF2F6FDE)
    /** Verde menta para los degradados. */
    val Accent2 get() = if (dark) Color(0xFF6FD3C3) else Color(0xFF15A39A)
    val AccentSoft get() = if (dark) Color(0x337FB3FF) else Color(0x1F2F6FDE)
    val OnAccent get() = if (dark) Color(0xFF0E1A2B) else Color.White
    val Hi get() = if (dark) Color(0xFFE9ECF1) else Color(0xFF1E2329)
    val Mid get() = if (dark) Color(0xFFB3BAC5) else Color(0xFF4B5563)
    val Low get() = if (dark) Color(0xFF858D9A) else Color(0xFF7A8391)
    val Good get() = if (dark) Color(0xFF5ED3A0) else Color(0xFF148A5A)
    val Warn get() = if (dark) Color(0xFFF2C56B) else Color(0xFFA56A00)
    val Live get() = if (dark) Color(0xFFFF6B6B) else Color(0xFFE5484D)
    val Glass get() = if (dark) Color(0xF0222831) else Color(0xF2FFFFFF)
    /** Botón principal. */
    val Strong get() = if (dark) Color(0xFFE9ECF1) else Color(0xFF1E2329)
    val OnStrong get() = if (dark) Color(0xFF161A20) else Color.White

    val gradient get() = Brush.linearGradient(listOf(Accent, Accent2))

    /** "dark", "light" o "auto" (claro de 7 am a 7 pm). Vacío = lo de siempre: oscuro en la tele, automático en el celular. */
    fun apply(mode: String) {
        val m = mode.ifBlank { if (DeviceUi.isTv) "dark" else "auto" }
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        dark = when (m) {
            "dark" -> true
            "light" -> false
            else -> h >= 19 || h < 7
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun VistaTheme(content: @Composable () -> Unit) {
    val scheme = if (C.dark) darkColorScheme(
        primary = C.Accent, onPrimary = C.OnAccent, background = C.Bg, onBackground = C.Hi,
        surface = C.Panel, onSurface = C.Hi, surfaceVariant = C.Panel2, onSurfaceVariant = C.Mid, border = C.Line,
    ) else lightColorScheme(
        primary = C.Accent, onPrimary = C.OnAccent, background = C.Bg, onBackground = C.Hi,
        surface = C.Panel, onSurface = C.Hi, surfaceVariant = C.Panel2, onSurfaceVariant = C.Mid, border = C.Line,
    )
    MaterialTheme(colorScheme = scheme, content = content)
}
