package com.mauricio.vista.ui

import android.app.Activity
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import java.lang.ref.WeakReference

/** true = celular (pantalla chica): barra de pestañas abajo y pantallas pensadas para el dedo. */
val LocalCompact = staticCompositionLocalOf { false }

object DeviceUi {
    /** Diseño de tele (tele real, o celular en modo presentación). */
    var isTv = false
        private set
    /** Es una tele de verdad (control remoto). */
    var realTv = false
        private set
    /** Celular mostrando el diseño de tele, para grabar demostraciones. */
    var tvLayout = false
        private set
    private var act: WeakReference<Activity>? = null
    private var playing = false

    /** Candado de rotación (solo celular/tableta). */
    var rotationLocked by mutableStateOf(false)
        private set

    fun init(a: Activity) {
        act = WeakReference(a)
        val ui = (a.resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_TELEVISION
        realTv = ui || a.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        tvLayout = !realTv && a.getSharedPreferences("vista_ui", Context.MODE_PRIVATE).getBoolean("tvLayout", false)
        isTv = realTv || tvLayout
        rotationLocked = a.getSharedPreferences("vista_ui", Context.MODE_PRIVATE).getBoolean("rotLock", false)
        apply()
    }

    fun isCompact(a: Activity) = !isTv && a.resources.configuration.smallestScreenWidthDp < 600

    /** Activa o quita el diseño de tele en el celular y reinicia la pantalla. */
    fun setTvLayout(on: Boolean) {
        val a = act?.get() ?: return
        a.getSharedPreferences("vista_ui", Context.MODE_PRIVATE).edit().putBoolean("tvLayout", on).commit()
        a.recreate()
    }

    fun toggleRotationLock() {
        rotationLocked = !rotationLocked
        val a = act?.get()
        val land = a?.resources?.configuration?.orientation == Configuration.ORIENTATION_LANDSCAPE
        a?.getSharedPreferences("vista_ui", Context.MODE_PRIVATE)?.edit()?.putBoolean("rotLock", rotationLocked)?.putBoolean("rotLockLand", land)?.apply()
        apply()
    }

    /** Videos siempre a lo ancho (como en iPhone); fuera del video, gira libre o se queda fijo si hay candado. */
    fun setPlaying(p: Boolean) {
        playing = p
        apply()
        // En video se esconden las barras del sistema (pantalla completa real)
        val a = act?.get() ?: return
        if (isTv) return
        val c = androidx.core.view.WindowCompat.getInsetsController(a.window, a.window.decorView)
        if (p) {
            c.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            c.hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
        } else c.show(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    }

    /** Íconos de la barra de estado oscuros sobre fondo claro y al revés. */
    fun styleBars(dark: Boolean) {
        val a = act?.get() ?: return
        if (isTv) return
        val c = androidx.core.view.WindowCompat.getInsetsController(a.window, a.window.decorView)
        c.isAppearanceLightStatusBars = !dark
        c.isAppearanceLightNavigationBars = !dark
    }

    private fun apply() {
        val a = act?.get() ?: return
        if (realTv) return
        if (tvLayout) {
            a.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            androidx.core.view.WindowCompat.getInsetsController(a.window, a.window.decorView).apply {
                hide(androidx.core.view.WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
            return
        }
        a.requestedOrientation = when {
            playing -> ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            // Al salir de un video regresa a la orientación en que se puso el candado (no a la del video)
            rotationLocked -> if (a.getSharedPreferences("vista_ui", Context.MODE_PRIVATE).getBoolean("rotLockLand", false))
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
    }
}

/** Pon esto en las pantallas de video: gira a horizontal mientras se ve y regresa al salir. */
@Composable
fun LandscapeWhilePlaying() {
    DisposableEffect(Unit) {
        DeviceUi.setPlaying(true)
        onDispose { DeviceUi.setPlaying(false) }
    }
}

/** Columnas de videos fuera de la tele: 1 en celular, 2 o 3 en tableta (según el ancho). */
@Composable
fun phoneCols(): Int {
    val w = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    return when {
        w >= 1000 -> 3
        w >= 600 -> 2
        else -> 1
    }
}
