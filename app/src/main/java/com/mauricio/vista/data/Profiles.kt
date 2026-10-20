package com.mauricio.vista.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Perfiles («¿Quién está viendo?»): cada uno con sus suscripciones, historial, recomendaciones,
 * listas, cola y ajustes. El infantil usa el modo restringido de YouTube y bloquea lo que tiene restricción de edad.
 */
object Profiles {
    data class Profile(val id: String, val name: String, val color: Int, val kids: Boolean = false, val pin: String = "") {
        val initial get() = name.trim().firstOrNull()?.uppercase() ?: "?"
        fun toJson(): JSONObject = JSONObject().put("id", id).put("n", name).put("c", color).put("k", kids).put("p", pin)
        companion object {
            fun fromJson(o: JSONObject) = Profile(o.getString("id"), o.optString("n"), o.optInt("c"), o.optBoolean("k"), o.optString("p"))
        }
    }

    /** Colores suaves para los perfiles. */
    val colors = listOf(0xFF7FB3FF.toInt(), 0xFF6FD3C3.toInt(), 0xFFF2C56B.toInt(), 0xFFE7A0C8.toInt(), 0xFFB9A4F5.toInt(), 0xFFF29E7C.toInt(), 0xFF9FD38A.toInt())

    private lateinit var gp: SharedPreferences
    private lateinit var app: Context

    private val _list = MutableStateFlow<List<Profile>>(emptyList())
    val list: StateFlow<List<Profile>> get() = _list
    private val _current = MutableStateFlow(Profile("p0", "Yo", colors[0]))
    val current: StateFlow<Profile> get() = _current

    fun init(ctx: Context) {
        app = ctx.applicationContext
        gp = ctx.getSharedPreferences("vista", Context.MODE_PRIVATE)
        val l = runCatching {
            val a = JSONArray(gp.getString("profiles", "[]"))
            List(a.length()) { Profile.fromJson(a.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        _list.value = l.ifEmpty { listOf(Profile("p0", "Yo", colors[0])) }
        val cur = gp.getString("profile", "p0")
        _current.value = _list.value.firstOrNull { it.id == cur } ?: _list.value.first()
    }

    private fun save(l: List<Profile>) {
        _list.value = l
        val a = JSONArray(); l.forEach { a.put(it.toJson()) }
        gp.edit().putString("profiles", a.toString()).apply()
    }

    val isKids: Boolean get() = _current.value.kids

    /** Archivo de ajustes del perfil actual (el principal conserva el de siempre). */
    fun prefsName(): String = _current.value.id.let { if (it == "p0") "vista" else "vista_$it" }

    /** Carpeta de archivos del perfil actual. */
    fun dir(ctx: Context): File = _current.value.id.let { if (it == "p0") ctx.filesDir else File(ctx.filesDir, "perfiles/$it").apply { mkdirs() } }

    /** Al abrir: preguntar quién ve si hay más de un perfil (y no se pidió dejar fijo uno). */
    fun shouldAsk(): Boolean = _list.value.size > 1 && gp.getBoolean("askProfile", true)

    var askOnStart: Boolean
        get() = gp.getBoolean("askProfile", true)
        set(v) { gp.edit().putBoolean("askProfile", v).apply() }

    fun add(name: String, kids: Boolean, pin: String): Profile {
        val p = Profile("p" + System.currentTimeMillis().toString(36), name.trim().ifBlank { if (kids) "Niños" else "Perfil" }, colors[_list.value.size % colors.size], kids, pin)
        save(_list.value + p)
        if (kids) {
            // Arranca con temas para niños y sin Shorts
            app.getSharedPreferences("vista_${p.id}", Context.MODE_PRIVATE).edit()
                .putString("topics", JSONArray(listOf("Para niños")).toString()).putBoolean("topicsAsked", true)
                .putBoolean("hideShorts", true).apply()
        }
        return p
    }

    fun update(p: Profile) {
        save(_list.value.map { if (it.id == p.id) p else it })
        if (_current.value.id == p.id) _current.value = p
    }

    fun delete(id: String) {
        if (id == "p0") return
        save(_list.value.filterNot { it.id == id })
        runCatching { File(app.filesDir, "perfiles/$id").deleteRecursively() }
        runCatching { app.deleteSharedPreferences("vista_$id") }
        if (_current.value.id == id) select(_list.value.first())
    }

    /** Cambia de perfil y vuelve a cargar todo lo de esa persona. */
    fun select(p: Profile) {
        _current.value = p
        gp.edit().putString("profile", p.id).apply()
        reload(app)
    }

    fun reload(ctx: Context) {
        Store.init(ctx)
        Feed.init(ctx)
        Recs.init(ctx)
        Queue.init(ctx)
        Playlists.init(ctx)
    }
}
