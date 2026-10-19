package com.mauricio.vista.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.darkColorScheme

/**
 * Estilo de Vista: fondo casi negro (como una sala de cine), tarjetas grafito
 * y un acento coral cálido. Pensado para que el video sea lo protagonista.
 */
object C {
    val Bg = Color(0xFF0B0C10)
    val Bg2 = Color(0xFF111319)
    val Panel = Color(0xFF16181F)
    val Panel2 = Color(0xFF222530)
    val Line = Color(0xFF2E3240)
    val Accent = Color(0xFFFF5B3A)
    val Accent2 = Color(0xFFE82E6C)
    val AccentSoft = Color(0x33FF5B3A)
    val OnAccent = Color.White
    val Hi = Color(0xFFF5F5F7)
    val Mid = Color(0xFFB8BCC8)
    val Low = Color(0xFF7C8191)
    val Good = Color(0xFF34D399)
    val Warn = Color(0xFFFBBF24)
    val Live = Color(0xFFFF3B30)
    val Glass = Color(0xEB16181F)
    /** Botón blanco (el principal en cada pantalla). */
    val Strong = Color(0xFFF5F5F7)
    val OnStrong = Color(0xFF0B0C10)

    val gradient get() = Brush.linearGradient(listOf(Accent, Accent2))
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun VistaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.Accent, onPrimary = C.OnAccent, background = C.Bg, onBackground = C.Hi,
            surface = C.Panel, onSurface = C.Hi, surfaceVariant = C.Panel2, onSurfaceVariant = C.Mid, border = C.Line,
        ),
        content = content,
    )
}
