package com.mauricio.vista.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.tv.material3.ClickableSurfaceBorder
import androidx.tv.material3.ClickableSurfaceColors
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ClickableSurfaceScale
import androidx.tv.material3.ClickableSurfaceShape

/**
 * Los botones de tele (tv-material) solo responden al control remoto, no al dedo.
 * Este Surface es igual, pero en celular y tableta además reconoce toques y toques largos,
 * y muestra el efecto de "presionado".
 */
@Composable
fun Surface(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    enabled: Boolean = true,
    shape: ClickableSurfaceShape = ClickableSurfaceDefaults.shape(),
    colors: ClickableSurfaceColors = ClickableSurfaceDefaults.colors(),
    scale: ClickableSurfaceScale = ClickableSurfaceDefaults.scale(),
    border: ClickableSurfaceBorder = ClickableSurfaceDefaults.border(),
    content: @Composable BoxScope.() -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val click by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLongClick)
    val touch = if (DeviceUi.realTv || !enabled) Modifier else Modifier.pointerInput(Unit) {
        detectTapGestures(
            onPress = { o ->
                val p = PressInteraction.Press(o)
                source.emit(p)
                val released = tryAwaitRelease()
                source.emit(if (released) PressInteraction.Release(p) else PressInteraction.Cancel(p))
            },
            onTap = { click() },
            onLongPress = { long?.invoke() ?: click() },
        )
    }
    androidx.tv.material3.Surface(
        onClick = onClick,
        modifier = modifier.then(touch),
        onLongClick = onLongClick,
        enabled = enabled,
        shape = shape,
        colors = colors,
        scale = scale,
        border = border,
        interactionSource = source,
        content = content,
    )
}
