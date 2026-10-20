package com.mauricio.vista.data

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.MessageDigest
import java.util.UUID

/**
 * Activación de Vista con un código del Centro de control (el mismo de CineCasa).
 * Cada código es de Vista y deja usarla en cierto número de aparatos; desde el panel se puede
 * desactivar, renovar o liberar aparatos. Sin internet se sigue usando hasta [GRACE] días.
 */
object License {
    private const val APP = "vista"
    private const val URL = "https://wnwecaapchcfhcljsvli.supabase.co/rest/v1/rpc/"
    private const val KEY = "sb_publishable_vnkSLd5EzN53_LBv76YxUA_QRo2u8ok"
    private const val GRACE = 10L * 86_400_000
    private const val RECHECK = 6L * 3_600_000

    sealed interface State {
        data object Ok : State
        data object Checking : State
        /** Hay que escribir un código (o pedir la prueba). [why] explica por qué, si ya había uno. */
        data class Need(val why: String? = null) : State
    }

    private lateinit var gp: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow<State>(State.Checking)
    val state: StateFlow<State> get() = _state

    val code: String get() = gp.getString("lic_code", "").orEmpty()
    private var lastOk: Long
        get() = gp.getLong("lic_ok", 0L)
        set(v) { gp.edit().putLong("lic_ok", v).apply() }

    lateinit var deviceId: String; private set
    /** Huella que sobrevive a desinstalar (para que la prueba gratis sea una sola vez por aparato). */
    private var fp: String = ""
    val deviceName: String get() = "${Build.MANUFACTURER} ${Build.MODEL}".trim().take(60)

    fun init(ctx: Context) {
        gp = ctx.getSharedPreferences("vista", Context.MODE_PRIVATE)
        fp = runCatching {
            val raw = android.provider.Settings.Secure.getString(ctx.contentResolver, android.provider.Settings.Secure.ANDROID_ID).orEmpty()
            if (raw.isBlank()) "" else MessageDigest.getInstance("SHA-256").digest("vista:$raw".toByteArray()).joinToString("") { "%02x".format(it) }
        }.getOrDefault("")
        deviceId = gp.getString("lic_dev", null) ?: (fp.take(32).ifBlank { UUID.randomUUID().toString().replace("-", "") }).also { gp.edit().putString("lic_dev", it).apply() }
        _state.value = when {
            code.isBlank() -> State.Need()
            System.currentTimeMillis() - lastOk < GRACE -> State.Ok
            else -> State.Checking
        }
        if (code.isNotBlank()) scope.launch {
            while (true) { check(); delay(RECHECK) }
        }
    }

    /** Pregunta al servidor si este aparato sigue activado. */
    suspend fun check() {
        val c = code.ifBlank { return }
        when (val r = call("cc_app_check", JSONObject().put("p_code", c).put("p_device", deviceId))) {
            "ok" -> { lastOk = System.currentTimeMillis(); _state.value = State.Ok }
            null -> if (System.currentTimeMillis() - lastOk >= GRACE) _state.value = State.Need("No se pudo revisar tu suscripción. Conéctate a internet y vuelve a intentar.")
            else -> _state.value = State.Need(why(r))
        }
    }

    /** Activa este aparato con un código. Devuelve null si quedó listo, o el mensaje de por qué no. */
    suspend fun activate(raw: String): String? {
        val c = raw.trim().uppercase().replace(" ", "")
        if (c.length < 8) return "Escribe el código completo (por ejemplo VI-AB12-CD34)."
        val r = call("cc_app_activate", JSONObject().put("p_code", c).put("p_device", deviceId).put("p_name", deviceName).put("p_fp", fp))
            ?: return "No hay conexión. Revisa el internet y vuelve a intentar."
        if (r != "ok") return why(r)
        gp.edit().putString("lic_code", c).putLong("lic_ok", System.currentTimeMillis()).apply()
        _state.value = State.Ok
        return null
    }

    /** Prueba gratis automática (una por aparato). Devuelve null si quedó lista, o el mensaje. */
    suspend fun trial(): String? {
        val txt = callRaw("cc_app_trial", JSONObject().put("p_device", deviceId).put("p_name", deviceName).put("p_fp", fp))
            ?: return "No hay conexión. Revisa el internet y vuelve a intentar."
        val o = runCatching { JSONObject(txt) }.getOrNull() ?: return "No se pudo pedir la prueba."
        o.optString("error").takeIf { it.isNotBlank() }?.let {
            return when (it) {
                "trial_used" -> "Este aparato ya usó su prueba gratis. Pide tu código a quien te recomendó Vista."
                "off" -> "Por ahora no hay pruebas gratis. Pide tu código a quien te recomendó Vista."
                "busy" -> "Hay muchas pruebas en este momento. Intenta en un rato."
                else -> "No se pudo pedir la prueba."
            }
        }
        val c = o.optString("code")
        if (o.optString("result") != "ok" || c.isBlank()) return why(o.optString("result"))
        gp.edit().putString("lic_code", c).putLong("lic_ok", System.currentTimeMillis()).apply()
        _state.value = State.Ok
        return null
    }

    /** Llegó un código desde otro aparato de la casa: se intenta activar aquí si hace falta. */
    fun adopt(c: String) {
        if (c.isBlank() || (_state.value == State.Ok && code.isNotBlank())) return
        scope.launch { activate(c) }
    }

    /** Tipo, vencimiento y aparatos (para Ajustes). */
    suspend fun info(): JSONObject? {
        val c = code.ifBlank { return null }
        return callRaw("cc_app_info", JSONObject().put("p_code", c).put("p_device", deviceId))
            ?.takeIf { it.isNotBlank() && it != "null" }?.let { runCatching { JSONObject(it) }.getOrNull() }
    }

    fun forget() {
        gp.edit().remove("lic_code").remove("lic_ok").apply()
        _state.value = State.Need()
    }

    private fun why(r: String): String = when (r) {
        "unknown" -> "Ese código no existe o no es de Vista. Revísalo letra por letra."
        "revoked" -> "Este código fue desactivado. Habla con quien te lo vendió."
        "expired" -> "Tu suscripción venció. Pide la renovación a quien te la vendió; tu código sigue siendo el mismo."
        "removed" -> "Este aparato fue liberado del código. Vuelve a escribirlo para activarlo."
        "full" -> "Ese código ya está en todos los aparatos que permite. Pide que te liberen uno o que agreguen otro."
        "trial_used" -> "Este aparato ya usó su prueba gratis."
        else -> "No se pudo activar ($r)."
    }

    private suspend fun call(fn: String, body: JSONObject): String? = callRaw(fn, body)?.trim()?.trim('"')

    private suspend fun callRaw(fn: String, body: JSONObject): String? = withContext(Dispatchers.IO) {
        body.put("p_app", APP)
        runCatching {
            val req = Request.Builder().url(URL + fn)
                .header("apikey", KEY)
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            Net.client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string().orEmpty() else null }
        }.getOrNull()
    }
}
