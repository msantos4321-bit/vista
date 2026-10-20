package com.mauricio.vista.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit

/**
 * Celular ↔ tele en la misma red de la casa, sin nube:
 * la tele abre una pequeña página (puerto 8723) para mandarle videos, búsquedas y suscripciones.
 */
object Remote {
    const val PORT = 8723

    sealed interface Cmd {
        data class Play(val url: String) : Cmd
        data class Search(val q: String) : Cmd
        data class Imported(val added: Int, val total: Int) : Cmd
        data class Account(val summary: String) : Cmd
    }

    /** Lo pone la actividad al arrancar. */
    var isTv = false

    /** Nombre amable de este aparato («Tele Sala», «Tableta Samsung»…). */
    fun deviceName(): String {
        val m = (android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() } + " " + android.os.Build.MODEL).let { if (android.os.Build.MODEL.startsWith(android.os.Build.MANUFACTURER, true)) android.os.Build.MODEL else it }
        return (if (isTv) "Tele " else "") + m
    }

    private val _cmds = MutableSharedFlow<Cmd>(extraBufferCapacity = 8)
    val cmds: SharedFlow<Cmd> get() = _cmds

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var server: ServerSocket? = null

    // ------------------------------------------------------------------
    // Dirección de este aparato en la red de la casa
    // ------------------------------------------------------------------
    fun localIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress.orEmpty() }
            .firstOrNull { it.startsWith("192.168.") || it.startsWith("10.") || it.startsWith("172.") }
    }.getOrNull()

    fun address(): String? = localIp()?.let { "http://$it:$PORT" }

    // ------------------------------------------------------------------
    // Servidor (en la tele)
    // ------------------------------------------------------------------
    fun startServer() {
        if (server != null) return
        scope.launch {
            try {
                val ss = ServerSocket(PORT)
                server = ss
                while (true) {
                    val s = ss.accept()
                    scope.launch { runCatching { handle(s) }; runCatching { s.close() } }
                }
            } catch (_: Exception) {
                server = null
            }
        }
    }

    private fun handle(s: Socket) {
        s.soTimeout = 20_000
        val input = s.getInputStream().buffered()
        val first = readLine(input) ?: return
        val parts = first.split(" ")
        if (parts.size < 2) return
        val method = parts[0]
        val path = parts[1].substringBefore('?')
        var length = 0
        while (true) {
            val h = readLine(input) ?: break
            if (h.isEmpty()) break
            if (h.startsWith("content-length:", true)) length = h.substringAfter(':').trim().toIntOrNull() ?: 0
        }
        val body = if (length > 0 && length < 30_000_000) ByteArray(length).also { b ->
            var off = 0
            while (off < length) { val n = input.read(b, off, length - off); if (n < 0) break; off += n }
        } else ByteArray(0)

        val (code, type, out) = when {
            method == "GET" && (path == "/" || path == "/index.html") -> Triple(200, "text/html; charset=utf-8", page().toByteArray())
            method == "GET" && path == "/estado" -> Triple(200, "application/json", JSONObject().put("ok", true).put("app", "Vista").put("modelo", android.os.Build.MODEL).put("nombre", deviceName()).put("tv", isTv).toString().toByteArray())
            method == "POST" && path == "/cuenta" -> {
                val r = runCatching { Store.importAccount(JSONObject(String(body, Charsets.UTF_8))) }
                r.getOrNull()?.let { _cmds.tryEmit(Cmd.Account(it)) }
                Triple(if (r.isSuccess) 200 else 400, "application/json", JSONObject().put("ok", r.isSuccess).put("resumen", r.getOrNull() ?: "").toString().toByteArray())
            }
            method == "POST" && path == "/ver" -> {
                val url = Yt.findLink(String(body)) ?: Yt.videoId(String(body).trim())?.let { Yt.watchUrl(it) }
                if (url != null) { _cmds.tryEmit(Cmd.Play(url)); Triple(200, "application/json", """{"ok":true}""".toByteArray()) }
                else Triple(400, "application/json", """{"ok":false,"error":"No encontré una liga de YouTube"}""".toByteArray())
            }
            method == "POST" && path == "/buscar" -> {
                val q = String(body).trim()
                if (q.isNotBlank()) _cmds.tryEmit(Cmd.Search(q))
                Triple(200, "application/json", """{"ok":true}""".toByteArray())
            }
            method == "POST" && path == "/importar" -> {
                val r = runBlocking { importBytes(body) }
                _cmds.tryEmit(Cmd.Imported(r.first, r.second))
                Triple(200, "application/json", JSONObject().put("ok", true).put("nuevos", r.first).put("total", r.second).toString().toByteArray())
            }
            method == "OPTIONS" -> Triple(204, "text/plain", ByteArray(0))
            else -> Triple(404, "text/plain", "No existe".toByteArray())
        }
        val os = s.getOutputStream()
        val head = "HTTP/1.1 $code OK\r\nContent-Type: $type\r\nContent-Length: ${out.size}\r\n" +
            "Access-Control-Allow-Origin: *\r\nAccess-Control-Allow-Headers: *\r\nConnection: close\r\n\r\n"
        os.write(head.toByteArray())
        os.write(out)
        os.flush()
    }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    // ------------------------------------------------------------------
    // Importar suscripciones (archivo de Google Takeout, lista de ligas o copia de otra Vista)
    // ------------------------------------------------------------------
    /** Devuelve (nuevas, total). */
    suspend fun importBytes(bytes: ByteArray): Pair<Int, Int> = withContext(Dispatchers.IO) {
        val chans = parse(bytes)
        // Las que no vienen con /channel/UC… se convierten (pocas, normalmente)
        val fixed = chans.map { c -> if (c.channelId != null) c else (Yt.resolveChannel(c.url)?.let { r -> r.copy(name = c.name.ifBlank { r.name }) } ?: c) }
            .filter { it.channelId != null }
        val added = Store.subscribeAll(fixed)
        added to fixed.size
    }

    /** Archivos que traía el último .zip (para explicar si no había suscripciones). */
    var lastZipFiles = ""

    private fun parse(bytes: ByteArray): List<Chan> {
        if (bytes.isEmpty()) return emptyList()
        // Archivo .zip de Google Takeout: se leen todos los .csv / .json que traiga adentro
        if (bytes.size > 4 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) {
            val out = LinkedHashMap<String, Chan>()
            lastZipFiles = ""
            runCatching {
                java.util.zip.ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        val name = e.name.lowercase()
                        if (!e.isDirectory) lastZipFiles += (if (lastZipFiles.isEmpty()) "" else ", ") + e.name.substringAfterLast('/')
                        if (!e.isDirectory && (name.endsWith(".csv") || name.endsWith(".json") || name.endsWith(".txt"))) {
                            val content = zip.readBytes()
                            parseText(String(content, Charsets.UTF_8)).forEach { c -> out.getOrPut(c.channelId ?: c.url) { c } }
                        }
                    }
                }
            }
            return out.values.toList()
        }
        return parseText(String(bytes, Charsets.UTF_8))
    }

    /** CSV de Takeout («Id. del canal, URL del canal, Título»), JSON, o texto con ligas. */
    private fun parseText(raw: String): List<Chan> {
        val text = raw.removePrefix("\uFEFF")
        // Copia de otra Vista
        if (text.trimStart().startsWith("{") && text.contains("\"vista\"")) {
            return runCatching {
                val a = JSONObject(text).getJSONArray("subs")
                List(a.length()) { Chan.fromJson(a.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        }
        val out = LinkedHashMap<String, Chan>()
        val idRx = Regex("UC[\\w-]{22}")
        // Renglones del CSV: el nombre del canal es lo que va después de la segunda coma
        text.lineSequence().forEach { line ->
            val id = idRx.find(line)?.value ?: return@forEach
            val cols = line.split(",")
            val title = if (cols.size >= 3) cols.drop(2).joinToString(",").trim().trim('"') else ""
            out.getOrPut(id) { Chan("https://www.youtube.com/channel/$id", title) }
        }
        // Formato JSON viejo de Takeout: "channelId": "UC…", "title": "…"
        Regex("\"channelId\"\\s*:\\s*\"(UC[\\w-]{22})\"[^}]*?\"title\"\\s*:\\s*\"([^\"]*)\"").findAll(text).forEach { m ->
            out[m.groupValues[1]] = Chan("https://www.youtube.com/channel/${m.groupValues[1]}", m.groupValues[2])
        }
        // Ligas tipo youtube.com/@canal
        Regex("https?://(?:www\\.|m\\.)?youtube\\.com/(?:@[\\w.-]+|c/[\\w.-]+|user/[\\w.-]+)").findAll(text).forEach { m ->
            out.getOrPut(m.value) { Chan(m.value, "") }
        }
        return out.values.toList()
    }

    /** Copia de mis suscripciones para pasarla a otro aparato. */
    fun exportJson(): String {
        val a = JSONArray(); Store.subs.value.forEach { a.put(it.toJson()) }
        return JSONObject().put("vista", 1).put("subs", a).toString()
    }

    // ------------------------------------------------------------------
    // Cliente (en el celular): manda cosas a la tele guardada
    // ------------------------------------------------------------------
    private val http by lazy { Net.client.newBuilder().connectTimeout(4, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build() }

    private suspend fun post(path: String, body: ByteArray, type: String, to: String = Store.tvAddress): Boolean = withContext(Dispatchers.IO) {
        val host = to.ifBlank { return@withContext false }
        runCatching {
            http.newCall(Request.Builder().url("http://$host$path").post(body.toRequestBody(type.toMediaType())).build()).execute().use { it.isSuccessful }
        }.getOrDefault(false)
    }

    suspend fun sendPlay(url: String) = post("/ver", url.toByteArray(), "text/plain")
    suspend fun sendSearch(q: String) = post("/buscar", q.toByteArray(), "text/plain")
    suspend fun sendSubs() = post("/importar", exportJson().toByteArray(), "application/json")
    /** Pasa la cuenta completa (suscripciones, temas, «Ver después», historial y ajustes). */
    suspend fun sendAccount(host: String) = post("/cuenta", Store.exportAccount().toString().toByteArray(), "application/json", host)

    suspend fun ping(host: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url("http://$host/estado").build()).execute().use { it.isSuccessful && it.body?.string().orEmpty().contains("Vista") }
        }.getOrDefault(false)
    }

    // ------------------------------------------------------------------
    // Página que ve el celular al escanear el código de la tele
    // ------------------------------------------------------------------
    private fun page(): String {
        val host = localIp().orEmpty() + ":" + PORT
        return """<!doctype html><html lang="es"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<title>Vista · Control</title>
<style>
:root{--bg:#161A20;--p:#222831;--p2:#2B323D;--hi:#E9ECF1;--mid:#B3BAC5;--low:#858D9A;--ac:#7FB3FF;--ac2:#6FD3C3}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--hi);font:16px/1.45 -apple-system,system-ui,Segoe UI,Roboto,sans-serif;padding:20px 16px 40px}
h1{font-size:26px;margin:4px 0 2px}.sub{color:var(--low);margin:0 0 18px}
.card{background:var(--p);border-radius:16px;padding:16px;margin:0 0 14px}
.card h2{font-size:17px;margin:0 0 4px}.card p{color:var(--mid);font-size:14px;margin:0 0 10px}
input,textarea{width:100%;background:var(--p2);color:var(--hi);border:1px solid #3A4250;border-radius:12px;padding:12px;font:inherit}
textarea{min-height:90px}
button,.btn{display:inline-block;margin-top:10px;width:100%;border:0;border-radius:12px;padding:13px;font:600 16px inherit;color:#0E1A2B;background:linear-gradient(135deg,var(--ac),var(--ac2));text-align:center;text-decoration:none}
.msg{margin-top:8px;font-size:14px;color:var(--mid);min-height:1em}
.logo{display:inline-grid;place-items:center;width:44px;height:44px;border-radius:12px;background:linear-gradient(135deg,var(--ac),var(--ac2));font-size:22px;vertical-align:middle;margin-right:8px;color:#0E1A2B}
</style></head><body>
<h1><span class="logo">▶</span>Vista</h1><p class="sub">Control de ${deviceName()} desde tu celular</p>
<div class="card"><h2>Pasar mi cuenta / conectar mi app</h2><p>Si ya tienes Vista en este celular, toca aquí: queda conectado y te ofrece pasar tu cuenta completa (suscripciones, temas, «Ver después» e historial) a ${deviceName()}.</p>
<a class="btn" href="vista://tele?h=$host&n=${java.net.URLEncoder.encode(deviceName(), "UTF-8")}&tv=${if (isTv) 1 else 0}">Conectar mi app Vista</a></div>
<div class="card"><h2>Mandar un video aquí</h2><p>Pega una liga de YouTube (también puedes compartirla desde la app de YouTube y copiarla).</p>
<input id="u" placeholder="https://youtu.be/…"><button onclick="send('/ver',u.value,'m1')">Ver en ${deviceName()}</button><div class="msg" id="m1"></div></div>
<div class="card"><h2>Buscar</h2><p>Escribe aquí en lugar de usar el control.</p>
<input id="q" placeholder="¿Qué quieres ver?"><button onclick="send('/buscar',q.value,'m2')">Buscar</button><div class="msg" id="m2"></div></div>
<div class="card"><h2>Pasar mis suscripciones</h2><p>Sube el archivo de Google Takeout (el .zip completo o «subscriptions.csv»), o pega ligas de canales, una por línea.</p>
<input type="file" id="f"><button onclick="up()">Subir archivo</button>
<textarea id="t" placeholder="https://www.youtube.com/@canal"></textarea><button onclick="send('/importar',t.value,'m3')">Agregar canales</button><div class="msg" id="m3"></div></div>
<script>
async function send(p,b,m){const e=document.getElementById(m);e.textContent='Enviando…';try{const r=await fetch(p,{method:'POST',body:b});const j=await r.json();e.textContent=j.ok?(j.nuevos!==undefined?('Listo: '+j.nuevos+' canales nuevos de '+j.total+'.'):'¡Listo!'):(j.error||'No se pudo');}catch(x){e.textContent='No se pudo conectar. Revisa que Vista esté abierta en el otro aparato.';}}
async function up(){const fl=f.files[0];if(!fl){m3.textContent='Elige primero el archivo.';return;}m3.textContent='Subiendo y revisando canales… (puede tardar un minuto)';const b=await fl.arrayBuffer();send('/importar',b,'m3');}
</script></body></html>"""
    }
}
