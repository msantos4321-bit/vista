package com.mauricio.vista

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.mauricio.vista.data.Remote
import com.mauricio.vista.data.Store
import com.mauricio.vista.ui.App
import com.mauricio.vista.ui.DeviceUi
import com.mauricio.vista.ui.PlayerState
import com.mauricio.vista.ui.Toast
import com.mauricio.vista.ui.VistaTheme
import com.mauricio.vista.ui.incomingLink

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceUi.init(this)
        if (!DeviceUi.isTv) enableEdgeToEdge()
        com.mauricio.vista.ui.C.apply(Store.theme)
        // Todos los aparatos escuchan en la red de la casa: así se puede pasar la cuenta a una tele, tableta u otro celular
        Remote.isTv = DeviceUi.realTv
        com.mauricio.vista.data.Recs.lightMode = DeviceUi.realTv
        Remote.startServer()
        if (savedInstanceState == null) readIntent(intent)
        setContent {
            if (DeviceUi.isTv) {
                // Todas las teles reportan tamaños distintos: lienzo fijo de 960 de ancho para que se vea igual en 720p, 1080p o 4K
                val dm = resources.displayMetrics
                val w = maxOf(dm.widthPixels, dm.heightPixels).toFloat()
                CompositionLocalProvider(LocalDensity provides Density(density = w / 960f, fontScale = 1f)) { VistaTheme { App() } }
            } else {
                VistaTheme { App() }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        readIntent(intent)
    }

    private fun readIntent(i: Intent?) {
        i ?: return
        when (i.action) {
            Intent.ACTION_SEND -> i.getStringExtra(Intent.EXTRA_TEXT)?.let { incomingLink.value = it }
            Intent.ACTION_VIEW -> {
                val d = i.data ?: return
                if (d.scheme == "vista") {
                    // vista://tele?h=192.168.1.20:8723 → este celular queda conectado a esa tele
                    d.getQueryParameter("h")?.takeIf { it.isNotBlank() }?.let { h ->
                        val dev = Store.Device(h, d.getQueryParameter("n").orEmpty().ifBlank { "Otro aparato" }, d.getQueryParameter("tv") != "0")
                        Store.addDevice(dev)
                        com.mauricio.vista.ui.linkedDevice.value = dev
                    }
                } else incomingLink.value = d.toString()
            }
        }
    }

    // Imagen en imagen: al salir de la app con un video, se sigue viendo en una ventanita
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!DeviceUi.isTv && PlayerState.active && PlayerState.playing && !PlayerState.audioOnly && Build.VERSION.SDK_INT >= 26) {
            runCatching { enterPictureInPictureMode(PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()) }
        }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        PlayerState.inPip = isInPictureInPictureMode
    }
}
