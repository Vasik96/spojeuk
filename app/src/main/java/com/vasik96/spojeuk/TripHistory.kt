package com.vasik96.spojeuk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.content.edit

/** Historie hledaných spojení (odkud → kam). Ukládá se jen lokálně v zařízení. */
class TripHistory(context: Context) {
    private class Entry(val from: String, val to: String, var last: Long)

    private val prefs = context.getSharedPreferences("trip_history", Context.MODE_PRIVATE)
    private val entries = ArrayList<Entry>()

    init {
        runCatching {
            val arr = JSONArray(prefs.getString("pairs", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                entries.add(Entry(o.getString("f"), o.getString("t"), o.getLong("l")))
            }
        }
    }

    private fun save() {
        val arr = JSONArray()
        for (x in entries) arr.put(JSONObject().put("f", x.from).put("t", x.to).put("l", x.last))
        prefs.edit { putString("pairs", arr.toString()) }
    }

    @Synchronized
    fun record(from: String, to: String) {
        val now = System.currentTimeMillis()
        val e = entries.firstOrNull { it.from == from && it.to == to }
        if (e != null) e.last = now else entries.add(Entry(from, to, now))
        if (entries.size > 100) {
            entries.sortByDescending { it.last }
            while (entries.size > 100) entries.removeAt(entries.lastIndex)
        }
        save()
    }

    /**
     * [near] = (název zastávky, vzdálenost), seřazené od nejbližší.
     * Vezme nejbližší zastávku, která má historii, a z ní naposledy použitou dvojici.
     */
    @Synchronized
    fun suggest(near: List<Pair<String, Double>>): Pair<String, String>? {
        for ((name, _) in near) {
            val e = entries.filter { it.from == name }.maxByOrNull { it.last }
            if (e != null) return e.from to e.to
        }
        return null
    }

    /** Smaže všechny dvojice, ve kterých je zastávka odkud i kam. */
    @Synchronized
    fun forget(name: String) {
        if (entries.removeAll { it.from == name || it.to == name }) save()
    }
}

/**
 * Mazání zastávky z "používaných" bez zásahu do StopUsageManager:
 * uloží se počet v okamžiku smazání a od něj se odečítá. Po novém použití zastávka začne od 1.
 */
class UsageBaseline(context: Context) {
    private val prefs = context.getSharedPreferences("usage_baseline", Context.MODE_PRIVATE)
    fun get(name: String): Int = prefs.getInt(name, 0)
    fun set(name: String, value: Int) = prefs.edit().putInt(name, value).apply()
}