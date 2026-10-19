package com.mauricio.vista.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.mauricio.vista.data.Chan
import com.mauricio.vista.data.Fmt
import com.mauricio.vista.data.Video
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay

val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/** Recuerda qué tenía el foco en cada pantalla para regresar ahí al volver (tele). */
class FocusMemory {
    var key: String? = null
    val requester = FocusRequester()
}

fun Modifier.rememberFocus(mem: FocusMemory?, key: String): Modifier {
    if (mem == null) return this
    val base = this.onFocusChanged { if (it.isFocused) mem.key = key }
    return if (mem.key == key) base.focusRequester(mem.requester) else base
}

@Composable
fun RestoreFocus(mem: FocusMemory, fallback: FocusRequester? = null) {
    LaunchedEffect(Unit) {
        delay(80)
        val ok = mem.key != null && runCatching { mem.requester.requestFocus() }.isSuccess
        if (!ok && fallback != null) {
            repeat(6) {
                if (runCatching { fallback.requestFocus() }.isSuccess) return@LaunchedEffect
                delay(120)
            }
        }
    }
}

@Composable
fun Img(url: String?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop, fallback: String? = null) {
    var failed by remember(url) { mutableStateOf(false) }
    val model = if (url.isNullOrBlank() || failed) fallback else url
    if (model.isNullOrBlank()) return
    AsyncImage(
        model = model, contentDescription = null, modifier = modifier, contentScale = contentScale,
        onError = { if (!failed && !fallback.isNullOrBlank() && model == url) failed = true },
    )
}

private val cardShape = RoundedCornerShape(12.dp)

/** Miniatura con duración, «EN VIVO» y barra de avance. */
@Composable
fun Thumb(v: Video, progress: Float, modifier: Modifier = Modifier) {
    Box(modifier.background(C.Panel2)) {
        Img(v.thumbHq, Modifier.fillMaxSize())
        if (v.live) {
            Text(
                "EN VIVO", Modifier.align(Alignment.BottomEnd).padding(6.dp).background(C.Live, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold,
            )
        } else if (v.duration > 0) {
            Text(
                Fmt.duration(v.duration), Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color(0xD9000000), RoundedCornerShape(4.dp)).padding(horizontal = 5.dp, vertical = 1.dp),
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        if (progress > 0.01f) ProgressBar(progress, Modifier.align(Alignment.BottomCenter).fillMaxWidth())
    }
}

/** Tarjeta de video para la tele (se agranda y se ilumina al tener el foco). */
@Composable
fun VideoCard(
    v: Video,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp = 236.dp,
    progress: Float = 0f,
    showChannel: Boolean = true,
    onFocused: () -> Unit = {},
    onLongClick: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.width(width)) {
        Surface(
            onClick = onClick,
            onLongClick = onLongClick,
            modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() },
            shape = ClickableSurfaceDefaults.shape(cardShape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.07f),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, C.Hi), shape = cardShape)),
            colors = ClickableSurfaceDefaults.colors(containerColor = C.Panel2, focusedContainerColor = C.Panel2),
        ) { Thumb(v, progress, Modifier.fillMaxSize()) }
        Spacer(Modifier.height(8.dp))
        Text(v.title, color = if (focused) C.Hi else C.Mid, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 17.sp)
        val meta = Fmt.meta(v, showChannel)
        if (meta.isNotBlank()) Text(meta, color = C.Low, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
    }
}

/** Tarjeta de video para el celular: miniatura grande a todo lo ancho, como en YouTube. */
@Composable
fun PhoneVideoCard(
    v: Video,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    progress: Float = 0f,
    showChannel: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Surface(
        onClick = onClick, onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(0.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f, pressedScale = 0.98f),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = C.Panel, pressedContainerColor = C.Panel),
    ) {
        Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
            Thumb(v, progress, Modifier.fillMaxWidth().aspectRatio(16f / 9f).padding(horizontal = 12.dp).clip(RoundedCornerShape(14.dp)))
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(v.title, color = C.Hi, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
                val meta = Fmt.meta(v, showChannel)
                if (meta.isNotBlank()) Text(meta, color = C.Low, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
        }
    }
}

/** Renglón compacto (miniatura chica a la izquierda): para «Siguiente» y listas largas. */
@Composable
fun VideoRow(v: Video, onClick: () -> Unit, modifier: Modifier = Modifier, progress: Float = 0f, onLongClick: (() -> Unit)? = null, trailing: (@Composable () -> Unit)? = null) {
    Surface(
        onClick = onClick, onLongClick = onLongClick,
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(12.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        colors = ClickableSurfaceDefaults.colors(containerColor = Color.Transparent, focusedContainerColor = C.Panel2, pressedContainerColor = C.Panel),
    ) {
        Row(Modifier.padding(6.dp), verticalAlignment = Alignment.CenterVertically) {
            Thumb(v, progress, Modifier.width(150.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(v.title, color = C.Hi, fontSize = 14.sp, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 18.sp)
                Text(Fmt.meta(v), color = C.Low, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            }
            trailing?.invoke()
        }
    }
}

@Composable
fun Avatar(url: String?, name: String, size: Dp) {
    Box(Modifier.size(size).clip(CircleShape).background(C.Panel2), contentAlignment = Alignment.Center) {
        val initial = name.trim().firstOrNull()?.uppercase()
        if (initial != null) Text(initial, color = C.Mid, fontSize = (size.value * 0.4f).sp, fontWeight = FontWeight.Bold)
        else Icon(Icons.Filled.Person, null, tint = C.Low)
        Img(url, Modifier.fillMaxSize())
    }
}

/** Canal en círculo con su nombre debajo. */
@Composable
fun ChannelBubble(c: Chan, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 84.dp, onFocused: () -> Unit = {}, onLongClick: (() -> Unit)? = null) {
    var focused by remember { mutableStateOf(false) }
    Column(modifier.width(size + 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Surface(
            onClick = onClick, onLongClick = onLongClick,
            modifier = Modifier.size(size).onFocusChanged { focused = it.isFocused; if (it.isFocused) onFocused() },
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1.1f),
            border = ClickableSurfaceDefaults.border(focusedBorder = Border(BorderStroke(3.dp, C.Hi), shape = CircleShape)),
            colors = ClickableSurfaceDefaults.colors(containerColor = C.Panel2, focusedContainerColor = C.Panel2),
        ) { Avatar(c.avatar, c.name, size) }
        Text(
            c.name, Modifier.padding(top = 8.dp), color = if (focused) C.Hi else C.Mid, fontSize = 12.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun ProgressBar(p: Float, modifier: Modifier = Modifier, height: Dp = 3.dp) {
    Box(modifier.height(height).background(Color(0x66FFFFFF))) {
        Box(Modifier.fillMaxWidth(p.coerceIn(0f, 1f)).height(height).background(C.Accent))
    }
}

@Composable
fun Pill(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, onFocused: () -> Unit = {}) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        modifier = modifier.onFocusChanged { if (it.isFocused) onFocused() },
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.05f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (selected) C.Hi else C.Panel2,
            contentColor = if (selected) C.Bg else C.Mid,
            focusedContainerColor = C.Accent,
            focusedContentColor = C.OnAccent,
        ),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) { Icon(icon, null, Modifier.size(16.dp)); Spacer(Modifier.width(6.dp)) }
            Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
fun ActionButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, primary: Boolean = false, active: Boolean = false) {
    val shape = RoundedCornerShape(50)
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.06f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (primary) C.Strong else if (active) C.AccentSoft else C.Panel2,
            contentColor = if (primary) C.OnStrong else if (active) C.Accent else C.Hi,
            focusedContainerColor = C.Accent,
            focusedContentColor = C.OnAccent,
            pressedContainerColor = C.Accent,
        ),
    ) {
        Row(Modifier.padding(horizontal = if (text.isEmpty()) 11.dp else 18.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) Icon(icon, null, Modifier.size(20.dp))
            if (icon != null && text.isNotEmpty()) Spacer(Modifier.width(8.dp))
            if (text.isNotEmpty()) Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.padding(bottom = 10.dp), color = C.Hi, fontSize = 18.sp, fontWeight = FontWeight.Bold)
}

@Composable
fun Spinner(modifier: Modifier = Modifier, size: Dp = 36.dp) {
    val t = rememberInfiniteTransition(label = "spin")
    val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "a")
    Canvas(modifier.size(size)) {
        drawArc(
            brush = Brush.sweepGradient(listOf(C.Accent2, C.Accent)), startAngle = a, sweepAngle = 280f, useCenter = false,
            topLeft = Offset(4f, 4f), size = Size(this.size.width - 8f, this.size.height - 8f),
            style = Stroke(width = 6f, cap = StrokeCap.Round),
        )
    }
}

@Composable
fun CenterMessage(title: String, sub: String = "", loading: Boolean = false, action: (@Composable () -> Unit)? = null) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (loading) Spinner()
            Text(title, color = C.Hi, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
            if (sub.isNotBlank()) Text(sub, color = C.Mid, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.widthIn520())
            action?.invoke()
        }
    }
}

fun Modifier.widthIn520(): Modifier = this.then(Modifier.width(520.dp).let { if (DeviceUi.isTv) it else Modifier.fillMaxWidth() })

/**
 * Campo de texto pensado para el control remoto: se enfoca como un botón y con OK se abre el teclado.
 * En el celular se escribe directo.
 */
@Composable
fun TvTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    imeAction: ImeAction = ImeAction.Search,
    onImeAction: () -> Unit = {},
    leading: ImageVector? = null,
    boxRequester: FocusRequester = remember { FocusRequester() },
    startEditing: Boolean = false,
) {
    var editing by remember { mutableStateOf(startEditing) }
    var focused by remember { mutableStateOf(false) }
    val fieldFr = remember { FocusRequester() }
    val kb = LocalSoftwareKeyboardController.current
    val shape = RoundedCornerShape(50)

    fun exit() {
        editing = false
        kb?.hide()
        runCatching { boxRequester.requestFocus() }
    }

    Surface(
        onClick = { editing = true },
        modifier = modifier.focusRequester(boxRequester).onFocusChanged { focused = it.hasFocus },
        shape = ClickableSurfaceDefaults.shape(shape),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
        border = ClickableSurfaceDefaults.border(
            border = Border(BorderStroke(1.dp, if (editing) C.Accent else C.Line), shape = shape),
            focusedBorder = Border(BorderStroke(2.dp, C.Accent), shape = shape),
        ),
        colors = ClickableSurfaceDefaults.colors(containerColor = C.Panel, focusedContainerColor = C.Panel2, contentColor = C.Hi, focusedContentColor = C.Hi),
    ) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                Icon(leading, null, Modifier.size(20.dp), tint = if (focused || editing) C.Accent else C.Low)
                Spacer(Modifier.width(10.dp))
            }
            Box(Modifier.weight(1f)) {
                if (editing) {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                        singleLine = true,
                        textStyle = TextStyle(color = C.Hi, fontSize = 17.sp),
                        cursorBrush = SolidColor(C.Accent),
                        keyboardOptions = KeyboardOptions(imeAction = imeAction, autoCorrectEnabled = false),
                        keyboardActions = KeyboardActions(onAny = { exit(); onImeAction() }),
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(fieldFr)
                            .onPreviewKeyEvent { e ->
                                if (DeviceUi.realTv && e.type == KeyEventType.KeyDown && (e.key == Key.DirectionDown || e.key == Key.DirectionUp || e.key == Key.Back)) {
                                    exit(); true
                                } else false
                            },
                    )
                    if (value.isEmpty()) Text(placeholder, color = C.Low, fontSize = 17.sp)
                } else {
                    Text(value.ifEmpty { placeholder }, color = if (value.isEmpty()) C.Low else C.Hi, fontSize = 17.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    LaunchedEffect(editing) {
        if (editing) {
            delay(60)
            runCatching { fieldFr.requestFocus() }
            kb?.show()
        }
    }
}

/** Código QR dibujado en la pantalla (para que el celular abra una liga). */
@Composable
fun QrCode(text: String, modifier: Modifier = Modifier) {
    val bmp = remember(text) {
        runCatching {
            val m = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 512, 512, mapOf(com.google.zxing.EncodeHintType.MARGIN to 1))
            val b = android.graphics.Bitmap.createBitmap(m.width, m.height, android.graphics.Bitmap.Config.RGB_565)
            for (x in 0 until m.width) for (y in 0 until m.height) b.setPixel(x, y, if (m.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
            b.asImageBitmap()
        }.getOrNull()
    }
    if (bmp != null) Image(bmp, null, modifier.clip(RoundedCornerShape(12.dp)).background(Color.White).padding(8.dp))
}

/** Aviso breve que aparece abajo y se va solo. */
object Toast {
    var text by mutableStateOf<String?>(null)
    private var n = 0
    fun show(t: String) { text = t; n++ }
    @Composable
    fun Host(modifier: Modifier = Modifier) {
        val t = text ?: return
        LaunchedEffect(t, n) { delay(2800); text = null }
        Box(modifier.fillMaxSize().padding(bottom = 90.dp), contentAlignment = Alignment.BottomCenter) {
            Text(
                t, Modifier.background(C.Glass, RoundedCornerShape(50)).padding(horizontal = 20.dp, vertical = 12.dp),
                color = C.Hi, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            )
        }
    }
}
