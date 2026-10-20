package com.mauricio.vista.data

import java.util.Locale

/** Números, duraciones y fechas como se dicen en México. */
object Fmt {
    fun duration(sec: Long): String {
        if (sec <= 0) return ""
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    fun clock(ms: Long): String = duration((ms / 1000).coerceAtLeast(0)).ifBlank { "0:00" }

    fun count(n: Long): String = when {
        n < 0 -> ""
        n < 1_000 -> "$n"
        n < 1_000_000 -> trim(n / 1_000.0) + " mil"
        n < 1_000_000_000 -> trim(n / 1_000_000.0) + " M"
        else -> trim(n / 1_000_000_000.0) + " mil M"
    }

    private fun trim(d: Double): String =
        if (d >= 100 || d % 1.0 < 0.05) d.toLong().toString() else String.format(Locale.US, "%.1f", d)

    fun views(n: Long): String = if (n < 0) "" else count(n) + if (n == 1L) " vista" else " vistas"

    fun subs(n: Long): String = if (n < 0) "" else count(n) + " suscriptores"

    fun ago(ms: Long): String {
        if (ms <= 0) return ""
        val d = (System.currentTimeMillis() - ms) / 1000
        return when {
            d < 60 -> "hace un momento"
            d < 3600 -> plural(d / 60, "minuto")
            d < 86_400 -> plural(d / 3600, "hora")
            d < 7 * 86_400 -> plural(d / 86_400, "día")
            d < 30 * 86_400 -> plural(d / (7 * 86_400), "semana")
            d < 365 * 86_400 -> (d / (30 * 86_400)).let { if (it == 1L) "hace 1 mes" else "hace $it meses" }
            else -> plural(d / (365 * 86_400), "año")
        }
    }

    private fun plural(n: Long, w: String) = "hace $n $w" + if (n == 1L) "" else "s"

    /** «hace 2 días · 12 mil vistas» (lo reciente primero, que es lo que más importa). */
    fun whenViews(v: Video): String = listOfNotNull(
        if (v.live) "EN VIVO" else (v.uploaded.ifBlank { ago(v.publishedMs) }).takeIf { it.isNotBlank() }?.let { shortAgo(it) },
        if (v.live) null else views(v.views).takeIf { it.isNotBlank() },
    ).joinToString(" · ")

    /** «hace 2 semanas» → «hace 2 sem.» para que quepa. */
    fun shortAgo(t: String): String = t.replace("semanas", "sem.").replace("semana", "sem.").replace("minutos", "min").replace("minuto", "min")
        .replace("Transmitido ", "").replace("Streamed ", "")

    /** «Canal · hace 2 días · 12 mil vistas» */
    fun meta(v: Video, withChannel: Boolean = true): String = listOfNotNull(
        v.channel.takeIf { withChannel && it.isNotBlank() },
        whenViews(v).takeIf { it.isNotBlank() },
    ).joinToString(" · ")

    fun quality(h: Int): String = when {
        h >= 2160 -> "4K"
        h >= 1440 -> "1440p"
        h > 0 -> "${h}p"
        else -> "Automática"
    }

    fun bytes(b: Long): String = when {
        b <= 0 -> ""
        b < 1_048_576 -> "${b / 1024} KB"
        b < 1_073_741_824 -> String.format(Locale.US, "%.0f MB", b / 1_048_576.0)
        else -> String.format(Locale.US, "%.1f GB", b / 1_073_741_824.0)
    }
}
