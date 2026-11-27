package com.vasik96.spojeuk

import android.content.Context
import java.util.concurrent.ConcurrentHashMap

/**
 * Druh dopravy linek, který jízdní řád nemá (GTFS bez route_type: trolejbus i mimopražská tramvaj jsou v něm
 * "autobus"). Server u živých vozů posílá `mode`; jakmile se vůz spáruje se spojem, zapamatuje se druh pro
 * dvojici dopravce + linka a odjezdy, výsledky hledání i trasy spojů z jízdního řádu pak mají správnou barvu.
 *
 * Učí se jen druhy jiné než autobus: trolejbusovou linku dočasně obsluhovanou autobusy (výluka) to nepřepíše.
 * Ukládá se v zařízení, takže to platí i po restartu, než přijdou první polohy.
 */
object LineKinds {
    private const val PREFS = "line_kinds"
    private const val KEY = "kinds"
    private val LEARNABLE = setOf("trolleybus", "tram", "metro", "ferry", "funicular")

    private val kinds = ConcurrentHashMap<String, String>()
    @Volatile private var ctx: Context? = null

    fun init(context: Context) {
        val c = context.applicationContext
        ctx = c
        runCatching {
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY, emptySet())?.forEach { e ->
                val i = e.lastIndexOf('|')
                if (i > 0) kinds[e.substring(0, i)] = e.substring(i + 1)
            }
        }
    }

    private fun key(agency: String, line: String) = agency.trim() + "|" + line.trim().uppercase()

    /** Druh linky ze živých dat (trolleybus, tram…), null = nezjištěno (bere se kategorie z jízdního řádu). */
    fun modeOf(agency: String, line: String): String? = if (kinds.isEmpty()) null else kinds[key(agency, line)]

    internal fun learn(agency: String, line: String, mode: String) {
        if (mode !in LEARNABLE || agency.isBlank() || line.isBlank() || line == "?") return
        val k = key(agency, line)
        if (kinds.put(k, mode) == mode) return
        val c = ctx ?: return
        val snapshot = kinds.entries.mapTo(HashSet()) { "${it.key}|${it.value}" }
        runCatching { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putStringSet(KEY, snapshot).apply() }
    }

    internal fun clearForTest() = kinds.clear()
}
