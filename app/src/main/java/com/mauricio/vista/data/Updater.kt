package com.mauricio.vista.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.mauricio.vista.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.File

/**
 * Actualizaciones automáticas: la app revisa en GitHub si hay una versión más nueva,
 * la descarga y abre el instalador. No hay que volver a escribir ninguna dirección.
 */
object Updater {
    private const val API = "https://api.github.com/repos/msantos4321-bit/vista/releases/latest"

    data class Release(val versionCode: Int, val name: String, val apkUrl: String, val notes: String)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val r: Release) : State
        data class Downloading(val r: Release, val pct: Int) : State
        data class Ready(val r: Release, val file: File) : State
        data class Failed(val msg: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> get() = _state

    /** Solo se ofrece una vez por versión al abrir la app (en Ajustes siempre se puede). */
    var dismissedCode: Int = 0

    suspend fun check(): Release? = withContext(Dispatchers.IO) {
        _state.value = State.Checking
        try {
            val req = Request.Builder().url(API).header("Accept", "application/vnd.github+json").build()
            val txt = Net.quick.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                r.body?.string().orEmpty()
            }
            val o = JSONObject(txt)
            val tag = o.optString("tag_name") // v1.0.N
            val code = tag.substringAfterLast('.').toIntOrNull() ?: 0
            val assets = o.optJSONArray("assets")
            var url = ""
            if (assets != null) for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                if (a.optString("name").endsWith(".apk")) { url = a.optString("browser_download_url"); break }
            }
            if (code > BuildConfig.VERSION_CODE && url.isNotBlank()) {
                val r = Release(code, o.optString("name").ifBlank { tag }, url, o.optString("body"))
                _state.value = State.Available(r)
                r
            } else {
                _state.value = State.UpToDate
                null
            }
        } catch (e: Exception) {
            _state.value = State.Failed("No se pudo revisar: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    suspend fun download(ctx: Context, r: Release): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(ctx.cacheDir, "update").apply { mkdirs() }
            dir.listFiles()?.forEach { it.delete() }
            val f = File(dir, "Vista-${r.versionCode}.apk")
            _state.value = State.Downloading(r, 0)
            Net.client.newCall(Request.Builder().url(r.apkUrl).build()).execute().use { resp ->
                if (!resp.isSuccessful) throw java.io.IOException("HTTP ${resp.code}")
                val body = resp.body ?: throw java.io.IOException("Descarga vacía")
                val total = body.contentLength()
                body.byteStream().use { input ->
                    f.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        var last = -1
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                val pct = (read * 100 / total).toInt()
                                if (pct != last) { last = pct; _state.value = State.Downloading(r, pct) }
                            }
                        }
                    }
                }
            }
            _state.value = State.Ready(r, f)
            f
        } catch (e: Exception) {
            _state.value = State.Failed("No se pudo descargar: ${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    /** ¿Ya tiene permiso para instalar? En Android 8+ hay que darlo una sola vez. */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || ctx.packageManager.canRequestPackageInstalls()

    fun openInstallPermission(ctx: Context) {
        val tries = listOf(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")),
            Intent(Settings.ACTION_SECURITY_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (i in tries) {
            try { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (_: Exception) {}
        }
    }

    fun install(ctx: Context, f: File): Boolean = try {
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        true
    } catch (e: Exception) {
        _state.value = State.Failed("No se pudo abrir el instalador: ${e.message ?: e.javaClass.simpleName}")
        false
    }

    fun reset() { _state.value = State.Idle }
}
