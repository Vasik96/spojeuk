package com.vasik96.spojeuk

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.content.edit

/** Historie hledaných spojení (odkud → kam). Ukládá se jen lokálně v zařízení. */
class TripHistory(context: Context) {
    /** [pinnedAt] > 0 = oblíbené spojení (připnuté nahoře na hlavní obrazovce), hodnota = kdy se připnulo. */
    private class Entry(val from: String, val to: String, var last: Long, var pinnedAt: Long = 0L)

    private val prefs = context.getSharedPreferences("trip_history", Context.MODE_PRIVATE)
    private val entries = ArrayList<Entry>()

    init {
        runCatching {
            val arr = JSONArray(prefs.getString("pairs", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                entries.add(Entry(o.getString("f"), o.getString("t"), o.getLong("l"), o.optLong("p", 0L)))
            }
        }
    }

    private fun save() {
        val arr = JSONArray()
        for (x in entries) {
            val o = JSONObject().put("f", x.from).put("t", x.to).put("l", x.last)
            if (x.pinnedAt > 0) o.put("p", x.pinnedAt)
            arr.put(o)
        }
        prefs.edit { putString("pairs", arr.toString()) }
    }

    @Synchronized
    fun record(from: String, to: String) {
        val now = System.currentTimeMillis()
        val e = entries.firstOrNull { it.from == from && it.to == to }
        if (e != null) e.last = now else entries.add(Entry(from, to, now))
        if (entries.size > 100) {
            // oblíbená se nikdy nezahazují
            entries.sortWith(compareByDescending<Entry> { it.pinnedAt > 0 }.thenByDescending { it.last })
            while (entries.size > 100 && entries.last().pinnedAt == 0L) entries.removeAt(entries.lastIndex)
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

    /** Naposledy hledaná spojení (odkud, kam), nejnovější první; bez oblíbených (ta mají vlastní seznam). */
    @Synchronized
    fun recent(limit: Int): List<Pair<String, String>> =
        entries.filter { it.pinnedAt == 0L }.sortedByDescending { it.last }.take(limit).map { it.from to it.to }

    /** Oblíbená spojení v pořadí připnutí. */
    @Synchronized
    fun pinned(): List<Pair<String, String>> =
        entries.filter { it.pinnedAt > 0 }.sortedBy { it.pinnedAt }.map { it.from to it.to }

    /** Připne / odepne spojení (připnout jde i dvojici, která v historii není, třeba opačný směr). */
    @Synchronized
    fun setPinned(from: String, to: String, pinned: Boolean) {
        val e = entries.firstOrNull { it.from == from && it.to == to }
            ?: Entry(from, to, System.currentTimeMillis()).also { entries.add(it) }
        e.pinnedAt = if (pinned) System.currentTimeMillis() else 0L
        save()
    }

    /** Odebraná položka (pro "Zpět"): vrátí se i s časem posledního hledání a připnutím. */
    class Removed internal constructor(internal val from: String, internal val to: String, internal val last: Long, internal val pinnedAt: Long)

    /** Odebere jednu dvojici z historie; vrátí ji pro případné [restore]. */
    @Synchronized
    fun remove(from: String, to: String): Removed? {
        val e = entries.firstOrNull { it.from == from && it.to == to } ?: return null
        entries.remove(e)
        save()
        return Removed(e.from, e.to, e.last, e.pinnedAt)
    }

    /** Vrátí odebranou položku zpět (tlačítko "Zpět" po odebrání). */
    @Synchronized
    fun restore(r: Removed) {
        if (entries.any { it.from == r.from && it.to == r.to }) return
        entries.add(Entry(r.from, r.to, r.last, r.pinnedAt))
        save()
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